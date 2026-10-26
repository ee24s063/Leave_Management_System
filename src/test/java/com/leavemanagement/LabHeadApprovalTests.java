package com.leavemanagement;

import com.leavemanagement.entity.*;
import com.leavemanagement.repository.*;
import com.leavemanagement.security.AppUserDetails;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HR staff's leave and comp-off go to their Lab Head or their chosen Team Lead; either one's approval is final,
 * and other HR users cannot act on them. The Lab Head's name is configurable: here it is renamed "Director".
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:sqlite:target/test-${random.uuid}.db?date_class=TEXT",
        "app.role-names.lab-head=Director"})
class LabHeadApprovalTests {

    @Autowired MockMvc mockMvc;
    @Autowired EmployeeRepository employeeRepository;
    @Autowired UserAccountRepository userAccountRepository;
    @Autowired LeaveTypeRepository leaveTypeRepository;
    @Autowired LeaveBalanceRepository leaveBalanceRepository;
    @Autowired PasswordEncoder passwordEncoder;

    UserAccount hrAccount, headAccount, leadAccount, memberAccount, hrStaffAccount;
    Employee hr, head, lead, member, hrStaff;
    LeaveType casual;
    LocalDate start = LocalDate.now().plusDays(10);
    int year = start.getYear();

    @BeforeEach
    void setUp() {
        hr = employee("Hana", "hr@test.com", null, null);
        hrAccount = account(hr, Role.HR_ADMIN);
        head = employee("Dev", "head@test.com", null, null);
        headAccount = account(head, Role.LAB_HEAD);
        lead = employee("Lee", "lead@test.com", null, null);
        leadAccount = account(lead, Role.TEAM_LEAD);
        member = employee("Mia", "member@test.com", lead.getEmployeeId(), null);
        memberAccount = account(member, Role.EMPLOYEE);
        hrStaff = employee("Hari", "hari@test.com", lead.getEmployeeId(), head.getEmployeeId());
        hrStaffAccount = account(hrStaff, Role.HR_ADMIN);

        casual = leaveTypeRepository.save(new LeaveType(null, "Casual Leave", 12, null, null));
        for (Employee e : new Employee[]{member, hrStaff}) {
            leaveBalanceRepository.save(new LeaveBalance(null, e.getEmployeeId(), casual.getLeaveTypeId(),
                    new BigDecimal("12"), BigDecimal.ZERO, BigDecimal.ZERO, year));
        }
    }

    // --- Routing and who may act ---

    @Test
    void hrStaffRequestWaitsForTheirLabHeadOrTeamLead() throws Exception {
        apply(hrStaffAccount).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_HEAD"));
    }

    @Test
    void otherHrCannotApproveOrRejectAnHrColleague() throws Exception {
        int id = submit(hrStaffAccount);
        approve(id, hrAccount).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message", containsString("Director")));
        reject(id, hrAccount).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/leave-requests/pending/hr").with(as(hrAccount)))
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void labHeadApprovalIsFinal() throws Exception {
        int id = submit(hrStaffAccount);
        approve(id, headAccount).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approvals", hasSize(1)))
                .andExpect(jsonPath("$.approvals[0].level").value("LAB_HEAD"))
                .andExpect(jsonPath("$.approvals[0].approverName").value("Dev Test"));
        assertThat(balanceOf(hrStaff).getUsedDays()).isEqualByComparingTo("2");
        approve(id, leadAccount).andExpect(status().isConflict());
    }

    @Test
    void chosenTeamLeadMayApproveInstead() throws Exception {
        int id = submit(hrStaffAccount);
        approve(id, leadAccount).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approvals[0].level").value("TEAM_LEAD"));
        assertThat(balanceOf(hrStaff).getUsedDays()).isEqualByComparingTo("2");
    }

    @Test
    void labHeadCanReject() throws Exception {
        int id = submit(hrStaffAccount);
        reject(id, headAccount).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.approvals[0].level").value("LAB_HEAD"));
    }

    @Test
    void labHeadActsOnlyForTheHrStaffTheyHead() throws Exception {
        int memberRequest = submit(memberAccount);
        approve(memberRequest, headAccount).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/leave-requests/employee/" + member.getEmployeeId()).with(as(headAccount)))
                .andExpect(status().isForbidden());
    }

    @Test
    void bothApproversSeeTheRequestInTheirQueue() throws Exception {
        submit(hrStaffAccount);
        mockMvc.perform(get("/api/leave-requests/pending/team").with(as(headAccount)))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].status").value("PENDING_HEAD"));
        mockMvc.perform(get("/api/leave-requests/pending/team").with(as(leadAccount)))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].employeeName").value("Hari Test"));
        mockMvc.perform(get("/api/employees").with(as(headAccount)))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].email").value("hari@test.com"));
    }

    @Test
    void hrStaffWithoutAnyApproverCannotApply() throws Exception {
        Employee newHr = employee("Nia", "nia@test.com", null, null);
        UserAccount newHrAccount = account(newHr, Role.HR_ADMIN);
        apply(newHrAccount).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("choose your Director")));
    }

    @Test
    void hrStaffCompOffGoesToTheLabHead() throws Exception {
        String body = """
                {"workedFrom":"%s","workedTo":"%s","reason":"Audit weekend"}
                """.formatted(LocalDate.now().minusDays(2), LocalDate.now().minusDays(2));
        String json = mockMvc.perform(post("/api/comp-off").with(as(hrStaffAccount)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_HEAD"))
                .andReturn().getResponse().getContentAsString();
        int id = Integer.parseInt(json.replaceAll(".*\"compOffRequestId\":(\\d+).*", "$1"));
        mockMvc.perform(post("/api/comp-off/" + id + "/approve").with(as(hrAccount)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/comp-off/" + id + "/approve").with(as(headAccount)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    // --- Creating HR staff ---

    @Test
    void creatingHrStaffNeedsALabHead() throws Exception {
        String withoutHead = newHrJson(null, lead.getEmployeeId());
        createEmployee(withoutHead).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Choose the Director who approves this HR employee's leave"));

        createEmployee(newHrJson(lead.getEmployeeId(), null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("The chosen approver must have the Director role"));

        createEmployee(newHrJson(head.getEmployeeId(), hr.getEmployeeId())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("For HR staff, the second approver must be a Team Lead"));

        createEmployee(newHrJson(head.getEmployeeId(), lead.getEmployeeId())).andExpect(status().isCreated())
                .andExpect(jsonPath("$.employee.headApproverId").value(head.getEmployeeId()))
                .andExpect(jsonPath("$.employee.headApproverName").value("Dev Test"))
                .andExpect(jsonPath("$.employee.reportingLeadName").value("Lee Test"));
    }

    @Test
    void pagesShowTheConfiguredName() throws Exception {
        mockMvc.perform(get("/leave-requests").with(as(headAccount)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Director")));
        mockMvc.perform(get("/employees").with(as(hrAccount)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<option value=\"LAB_HEAD\">Director</option>")));
    }

    // --- Helpers ---

    private String newHrJson(Integer headId, Integer leadId) {
        return """
                {"firstName":"Nia","lastName":"Test","email":"nia.new@test.com","department":"HR","designation":"HR Officer",
                 "dateOfJoining":"2025-06-01","salary":50000,"role":"HR_ADMIN","headApproverId":%s,"reporting_LeadId":%s}
                """.formatted(headId, leadId);
    }

    private ResultActions createEmployee(String body) throws Exception {
        return mockMvc.perform(post("/api/employees").with(as(hrAccount)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions apply(UserAccount account) throws Exception {
        String body = """
                {"leaveTypeId":%d,"startDate":"%s","endDate":"%s","reason":"Holiday"}
                """.formatted(casual.getLeaveTypeId(), start, start.plusDays(1));
        return mockMvc.perform(post("/api/leave-requests").with(as(account)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private int submit(UserAccount account) throws Exception {
        String json = apply(account).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return Integer.parseInt(json.replaceAll(".*\"leaveRequestId\":(\\d+).*", "$1"));
    }

    private ResultActions approve(int id, UserAccount account) throws Exception {
        return mockMvc.perform(post("/api/leave-requests/" + id + "/approve").with(as(account)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{}"));
    }

    private ResultActions reject(int id, UserAccount account) throws Exception {
        return mockMvc.perform(post("/api/leave-requests/" + id + "/reject").with(as(account)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"comments\":\"Audit week\"}"));
    }

    private LeaveBalance balanceOf(Employee e) {
        return leaveBalanceRepository
                .findByEmployeeIdAndLeaveTypeIdAndYear(e.getEmployeeId(), casual.getLeaveTypeId(), year).orElseThrow();
    }

    private RequestPostProcessor as(UserAccount account) {
        return user(new AppUserDetails(userAccountRepository.findById(account.getUserId()).orElseThrow()));
    }

    private Employee employee(String firstName, String email, Integer leadId, Integer headId) {
        Employee e = new Employee();
        e.setFirstName(firstName);
        e.setLastName("Test");
        e.setEmail(email);
        e.setDepartment("IT");
        e.setDesignation("Engineer");
        e.setDateOfJoining(LocalDate.of(2024, 1, 15));
        e.setSalary(50000.0);
        e.setStatus(EmployeeStatus.ACTIVE);
        e.setReporting_LeadId(leadId);
        e.setHeadApproverId(headId);
        return employeeRepository.save(e);
    }

    private UserAccount account(Employee e, Role role) {
        UserAccount a = new UserAccount();
        a.setUsername(e.getEmail());
        a.setPasswordHash(passwordEncoder.encode("Secret123!"));
        a.setRole(role);
        a.setEmployeeId(e.getEmployeeId());
        a.setEnabled(true);
        a.setMustChangePassword(false);
        return userAccountRepository.save(a);
    }
}
