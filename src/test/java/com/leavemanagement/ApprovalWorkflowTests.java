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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 2: Team Lead approval followed by HR final approval
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "spring.datasource.url=jdbc:sqlite:target/test-${random.uuid}.db?date_class=TEXT")
class ApprovalWorkflowTests {

    @Autowired MockMvc mockMvc;
    @Autowired EmployeeRepository employeeRepository;
    @Autowired UserAccountRepository userAccountRepository;
    @Autowired LeaveTypeRepository leaveTypeRepository;
    @Autowired LeaveBalanceRepository leaveBalanceRepository;
    @Autowired LeaveRequestRepository leaveRequestRepository;
    @Autowired PasswordEncoder passwordEncoder;

    UserAccount hrAccount, leadAccount, memberAccount, soloAccount;
    Employee hr, lead, member, solo;
    LeaveType casual;
    LocalDate start = LocalDate.now().plusDays(10);
    int year = start.getYear();

    @BeforeEach
    void setUp() {
        hr = employee("Hana", "hr@test.com", null);
        hrAccount = account(hr, Role.HR_ADMIN);
        lead = employee("Lee", "lead@test.com", null);
        leadAccount = account(lead, Role.TEAM_LEAD);
        member = employee("Mia", "member@test.com", lead.getEmployeeId());
        memberAccount = account(member, Role.EMPLOYEE);
        solo = employee("Sam", "solo@test.com", null); // no reporting lead
        soloAccount = account(solo, Role.EMPLOYEE);

        casual = leaveTypeRepository.save(new LeaveType(null, "Casual Leave", 12, null, null));
        for (Employee e : new Employee[]{lead, member, solo}) {
            leaveBalanceRepository.save(new LeaveBalance(null, e.getEmployeeId(), casual.getLeaveTypeId(), new BigDecimal("12"), BigDecimal.ZERO, BigDecimal.ZERO, year));
        }
    }

    // --- Where a new request goes ---

    @Test
    void requestWithReportingLeadWaitsForTheLead() throws Exception {
        apply(memberAccount, 2).andExpect(jsonPath("$.status").value("PENDING_TL"))
                .andExpect(jsonPath("$.reportingLeadName").value("Lee Test"));
    }

    @Test
    void requestWithoutReportingLeadGoesStraightToHr() throws Exception {
        apply(soloAccount, 2).andExpect(jsonPath("$.status").value("PENDING_HR"));
    }

    @Test
    void teamLeadsOwnRequestGoesStraightToHr() throws Exception {
        lead.setReporting_LeadId(hr.getEmployeeId());
        employeeRepository.save(lead);
        apply(leadAccount, 2).andExpect(jsonPath("$.status").value("PENDING_HR"));
    }

    // --- Approval steps ---

    @Test
    void teamLeadCannotGiveFinalApproval() throws Exception {
        int id = submit(memberAccount, 2);
        approve(id, leadAccount).andExpect(jsonPath("$.status").value("PENDING_HR"));
        approve(id, leadAccount).andExpect(status().isForbidden());
        assertThat(balanceOf(member).getUsedDays()).isZero();
    }

    @Test
    void hrApprovesDirectlyWhenLeadIsAway() throws Exception {
        int id = submit(memberAccount, 2);
        approve(id, hrAccount)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approvals", hasSize(2)))
                .andExpect(jsonPath("$.approvals[0].level").value("TEAM_LEAD"))
                .andExpect(jsonPath("$.approvals[0].onBehalf").value(true))
                .andExpect(jsonPath("$.approvals[0].approverName").value("Hana Test"))
                .andExpect(jsonPath("$.approvals[1].level").value("HR"))
                .andExpect(jsonPath("$.approvals[1].onBehalf").value(false));
        assertThat(balanceOf(member).getUsedDays()).isEqualByComparingTo("2");
    }

    @Test
    void hrWhoIsTheReportingLeadIsNotMarkedOnBehalf() throws Exception {
        member.setReporting_LeadId(hr.getEmployeeId());
        employeeRepository.save(member);
        int id = submit(memberAccount, 2);
        approve(id, hrAccount)
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approvals[0].onBehalf").value(false));
    }

    @Test
    void leadRejectionEndsTheRequest() throws Exception {
        int id = submit(memberAccount, 2);
        reject(id, leadAccount, "Release week").andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.approvals[0].action").value("REJECTED"))
                .andExpect(jsonPath("$.approvals[0].comments").value("Release week"));
        approve(id, hrAccount).andExpect(status().isConflict());
        assertThat(balanceOf(member).getUsedDays()).isZero();
    }

    @Test
    void hrCanRejectAfterLeadApproval() throws Exception {
        int id = submit(memberAccount, 2);
        approve(id, leadAccount);
        reject(id, hrAccount, "Year-end freeze")
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.approvals", hasSize(2)))
                .andExpect(jsonPath("$.approvals[1].level").value("HR"));
    }

    @Test
    void teamLeadCannotRejectAfterHandingToHr() throws Exception {
        int id = submit(memberAccount, 2);
        approve(id, leadAccount);
        reject(id, leadAccount, "Changed my mind").andExpect(status().isForbidden());
    }

    @Test
    void finalApprovalMayTakeTheBalanceBelowZero() throws Exception {
        int id = submit(soloAccount, 13);
        mockMvc.perform(get("/api/leave-requests/" + id).with(as(hrAccount)))
                .andExpect(jsonPath("$.balanceAfterApproval").value(-1));
        approve(id, hrAccount).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        assertThat(balanceOf(solo).getRemainingDays()).isEqualByComparingTo("-1");
    }

    @Test
    void pendingRequestShowsBalanceAfterApproval() throws Exception {
        apply(memberAccount, 3).andExpect(jsonPath("$.balanceAfterApproval").value(9));
    }

    // --- Cancelling ---

    @Test
    void employeeWithdrawsPendingRequest() throws Exception {
        int id = submit(memberAccount, 2);
        cancel(id, memberAccount).andExpect(jsonPath("$.status").value("CANCELLED"));
        approve(id, leadAccount).andExpect(status().isConflict());
    }

    @Test
    void cancellingApprovedLeaveRestoresDays() throws Exception {
        int id = submit(soloAccount, 3);
        approve(id, hrAccount);
        assertThat(balanceOf(solo).getRemainingDays()).isEqualByComparingTo("9");
        cancel(id, soloAccount).andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(balanceOf(solo).getRemainingDays()).isEqualByComparingTo("12");
    }

    @Test
    void approvedLeaveThatHasStartedCanOnlyBeCancelledByHr() throws Exception {
        LeaveRequest started = new LeaveRequest(null, solo.getEmployeeId(), casual.getLeaveTypeId(),
                LocalDate.now().minusDays(1), LocalDate.now().plusDays(1), "Trip", LeaveStatus.APPROVED, LocalDate.now().minusDays(5), null);
        started = leaveRequestRepository.save(started);
        cancel(started.getLeaveRequestId(), soloAccount).andExpect(status().isConflict());
        cancel(started.getLeaveRequestId(), hrAccount).andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    // --- Queues and history ---

    @Test
    void teamQueueShowsOnlyTheLeadsReportsWaitingForThem() throws Exception {
        submit(memberAccount, 2);
        submit(soloAccount, 2);
        mockMvc.perform(get("/api/leave-requests/pending/team").with(as(leadAccount)))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].employeeName").value("Mia Test"));
        mockMvc.perform(get("/api/leave-requests/pending/team").with(as(memberAccount)))
                .andExpect(status().isForbidden());
    }

    @Test
    void hrQueueListsWaitingForHrFirstThenWaitingForLead() throws Exception {
        submit(memberAccount, 2); // PENDING_TL
        submit(soloAccount, 2);   // PENDING_HR
        mockMvc.perform(get("/api/leave-requests/pending/hr").with(as(hrAccount)))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].status").value("PENDING_HR"))
                .andExpect(jsonPath("$[1].status").value("PENDING_TL"));
        mockMvc.perform(get("/api/leave-requests/pending/hr").with(as(leadAccount)))
                .andExpect(status().isForbidden());
    }

    @Test
    void historyIsVisibleToTheEmployeeButNotToOthers() throws Exception {
        int id = submit(memberAccount, 2);
        approve(id, leadAccount);
        mockMvc.perform(get("/api/leave-requests/" + id + "/history").with(as(memberAccount)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].approverName").value("Lee Test"));
        mockMvc.perform(get("/api/leave-requests/" + id + "/history").with(as(soloAccount)))
                .andExpect(status().isForbidden());
    }

    // --- Same day twice ---

    @Test
    void cannotApplyTwiceForTheSameDayUntilTheFirstIsCancelled() throws Exception {
        int first = submit(memberAccount, 3);
        applyFor(memberAccount, start.plusDays(2), start.plusDays(4))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("already applied for leave")));

        cancel(first, memberAccount).andExpect(status().isOk());
        applyFor(memberAccount, start.plusDays(2), start.plusDays(4)).andExpect(status().isCreated());
    }

    @Test
    void anotherEmployeeMayTakeTheSameDay() throws Exception {
        submit(memberAccount, 2);
        apply(soloAccount, 2);
    }

    // --- Helpers ---

    private ResultActions apply(UserAccount account, int days) throws Exception {
        return applyFor(account, start, start.plusDays(days - 1)).andExpect(status().isCreated());
    }

    private ResultActions applyFor(UserAccount account, LocalDate from, LocalDate to) throws Exception {
        String body = """
                {"leaveTypeId":%d,"startDate":"%s","endDate":"%s","reason":"Holiday"}
                """.formatted(casual.getLeaveTypeId(), from, to);
        return mockMvc.perform(post("/api/leave-requests").with(as(account)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private int submit(UserAccount account, int days) throws Exception {
        String json = apply(account, days).andReturn().getResponse().getContentAsString();
        return Integer.parseInt(json.replaceAll(".*\"leaveRequestId\":(\\d+).*", "$1"));
    }

    private ResultActions approve(int id, UserAccount account) throws Exception {
        return mockMvc.perform(post("/api/leave-requests/" + id + "/approve").with(as(account)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{}"));
    }

    private ResultActions reject(int id, UserAccount account, String reason) throws Exception {
        return mockMvc.perform(post("/api/leave-requests/" + id + "/reject").with(as(account)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"comments\":\"" + reason + "\"}"));
    }

    private ResultActions cancel(int id, UserAccount account) throws Exception {
        return mockMvc.perform(post("/api/leave-requests/" + id + "/cancel").with(as(account)).with(csrf()));
    }

    private LeaveBalance balanceOf(Employee e) {
        return leaveBalanceRepository
                .findByEmployeeIdAndLeaveTypeIdAndYear(e.getEmployeeId(), casual.getLeaveTypeId(), year).orElseThrow();
    }

    private RequestPostProcessor as(UserAccount account) {
        return user(new AppUserDetails(userAccountRepository.findById(account.getUserId()).orElseThrow()));
    }

    private Employee employee(String firstName, String email, Integer leadId) {
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
