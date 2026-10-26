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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Permanent deletion: admin account only, the name must be typed, refused while the person approves others,
 * and all of the person's data (including approvals they gave) is removed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "spring.datasource.url=jdbc:sqlite:target/test-${random.uuid}.db?date_class=TEXT")
class EmployeeDeletionTests {

    @Autowired MockMvc mockMvc;
    @Autowired EmployeeRepository employeeRepository;
    @Autowired UserAccountRepository userAccountRepository;
    @Autowired LeaveTypeRepository leaveTypeRepository;
    @Autowired LeaveBalanceRepository leaveBalanceRepository;
    @Autowired LeaveRequestRepository leaveRequestRepository;
    @Autowired CompOffRequestRepository compOffRequestRepository;
    @Autowired LeaveApprovalRepository leaveApprovalRepository;
    @Autowired LeaveTransactionRepository leaveTransactionRepository;
    @Autowired PasswordEncoder passwordEncoder;

    UserAccount adminAccount, hrAccount, leadAccount, memberAccount;
    Employee hr, lead, member;
    LeaveType casual;
    LocalDate start = LocalDate.now().plusDays(10);
    int year = start.getYear();

    @BeforeEach
    void setUp() {
        adminAccount = account(null, "admin@test.local", Role.HR_ADMIN);
        hr = employee("Hana", "hr@test.com", null);
        hrAccount = account(hr, hr.getEmail(), Role.HR_ADMIN);
        lead = employee("Lee", "lead@test.com", null);
        leadAccount = account(lead, lead.getEmail(), Role.TEAM_LEAD);
        member = employee("Mia", "member@test.com", lead.getEmployeeId());
        memberAccount = account(member, member.getEmail(), Role.EMPLOYEE);

        casual = leaveTypeRepository.save(new LeaveType(null, "Casual Leave", 12, null, null));
        leaveBalanceRepository.save(new LeaveBalance(null, member.getEmployeeId(), casual.getLeaveTypeId(),
                new BigDecimal("12"), BigDecimal.ZERO, BigDecimal.ZERO, year));
    }

    @Test
    void adminDeletesTheEmployeeAndAllOfTheirData() throws Exception {
        int leaveId = submitAndApprove();
        postJson("/api/comp-off", memberAccount, """
                {"workedFrom":"%s","workedTo":"%s","reason":"Release weekend"}
                """.formatted(LocalDate.now().minusDays(2), LocalDate.now().minusDays(2)))
                .andExpect(status().isCreated());

        delete(member, "  mia   test ", adminAccount).andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Mia Test was deleted permanently"));

        assertThat(employeeRepository.findById(member.getEmployeeId())).isEmpty();
        assertThat(userAccountRepository.findByEmployeeId(member.getEmployeeId())).isEmpty();
        assertThat(leaveRequestRepository.findById(leaveId)).isEmpty();
        assertThat(compOffRequestRepository.findByEmployeeId(member.getEmployeeId())).isEmpty();
        assertThat(leaveBalanceRepository.findByEmployeeId(member.getEmployeeId())).isEmpty();
        assertThat(leaveTransactionRepository.findAll()).noneMatch(t -> t.getEmployeeId().equals(member.getEmployeeId()));
        assertThat(leaveApprovalRepository.findAll()).isEmpty();
        // Everyone else is untouched
        assertThat(employeeRepository.findById(lead.getEmployeeId())).isPresent();
        assertThat(employeeRepository.findById(hr.getEmployeeId())).isPresent();
    }

    @Test
    void approvalsTheyGaveAreRemovedButAdjustmentsTheyMadeForOthersStay() throws Exception {
        int leaveId = submitAndApprove(); // approved by Lee (Team Lead) and Hana (HR)
        postJson("/api/leave-balance/adjust", hrAccount, """
                {"employeeId":%d,"leaveTypeId":%d,"year":%d,"days":1,"reason":"Extra day for the release"}
                """.formatted(member.getEmployeeId(), casual.getLeaveTypeId(), year))
                .andExpect(status().isOk());

        delete(hr, "Hana Test", adminAccount).andExpect(status().isOk());

        assertThat(leaveRequestRepository.findById(leaveId)).isPresent();
        assertThat(leaveApprovalRepository.findAll()).hasSize(1)
                .allMatch(a -> a.getApproverEmployeeId().equals(lead.getEmployeeId()));
        LeaveTransaction adjustment = leaveTransactionRepository.findAll().stream()
                .filter(t -> t.getType() == TransactionType.ADJUSTMENT).findFirst().orElseThrow();
        assertThat(adjustment.getCreatedByUserId()).isNull();
        assertThat(adjustment.getCreatedByName()).isEqualTo("Hana Test");
        assertThat(leaveBalanceRepository.findByEmployeeIdAndLeaveTypeIdAndYear(member.getEmployeeId(),
                casual.getLeaveTypeId(), year).orElseThrow().getRemainingDays()).isEqualByComparingTo("12"); // 12 - 1 taken + 1 adjusted
    }

    @Test
    void onlyTheAdminAccountMayDelete() throws Exception {
        delete(member, "Mia Test", hrAccount).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message", containsString("Only the admin account")));
        delete(member, "Mia Test", leadAccount).andExpect(status().isForbidden());
        assertThat(employeeRepository.findById(member.getEmployeeId())).isPresent();
    }

    @Test
    void theNameMustBeTypedToConfirm() throws Exception {
        delete(member, "Mia", adminAccount).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Type the employee's full name (Mia Test) to confirm"));
        assertThat(employeeRepository.findById(member.getEmployeeId())).isPresent();
    }

    @Test
    void refusedWhileTheyApproveOthers() throws Exception {
        delete(lead, "Lee Test", adminAccount).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Lee Test is the Team Lead of Mia Test. Choose another approver for them first."));
        assertThat(employeeRepository.findById(lead.getEmployeeId())).isPresent();
    }

    @Test
    void meTellsThePageWhoIsTheAdmin() throws Exception {
        mockMvc.perform(get("/api/me").with(as(adminAccount))).andExpect(jsonPath("$.admin").value(true));
        mockMvc.perform(get("/api/me").with(as(hrAccount))).andExpect(jsonPath("$.admin").value(false));
    }

    // --- Helpers ---

    /** Mia applies; Lee and then Hana approve. Returns the request id. */
    private int submitAndApprove() throws Exception {
        String json = postJson("/api/leave-requests", memberAccount, """
                {"leaveTypeId":%d,"startDate":"%s","endDate":"%s","reason":"Holiday"}
                """.formatted(casual.getLeaveTypeId(), start, start))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        int id = Integer.parseInt(json.replaceAll(".*\"leaveRequestId\":(\\d+).*", "$1"));
        postJson("/api/leave-requests/" + id + "/approve", leadAccount, "{}").andExpect(status().isOk());
        postJson("/api/leave-requests/" + id + "/approve", hrAccount, "{}")
                .andExpect(jsonPath("$.status").value("APPROVED"));
        return id;
    }

    private ResultActions delete(Employee e, String typedName, UserAccount account) throws Exception {
        return postJson("/api/employees/" + e.getEmployeeId() + "/delete-permanently", account,
                "{\"confirmName\":\"" + typedName + "\"}");
    }

    private ResultActions postJson(String url, UserAccount account, String body) throws Exception {
        return mockMvc.perform(post(url).with(as(account)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body));
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

    private UserAccount account(Employee e, String username, Role role) {
        UserAccount a = new UserAccount();
        a.setUsername(username);
        a.setPasswordHash(passwordEncoder.encode("Secret123!"));
        a.setRole(role);
        a.setEmployeeId(e == null ? null : e.getEmployeeId());
        a.setEnabled(true);
        a.setMustChangePassword(false);
        return userAccountRepository.save(a);
    }
}
