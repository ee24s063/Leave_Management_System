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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import jakarta.servlet.http.Cookie;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Checks the Phase 1 access rules: login (JWT), roles, own-data and own-team restrictions
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "spring.datasource.url=jdbc:sqlite:target/test-${random.uuid}.db?date_class=TEXT")
class SecurityAccessTests {

    @Autowired MockMvc mockMvc;
    @Autowired EmployeeRepository employeeRepository;
    @Autowired UserAccountRepository userAccountRepository;
    @Autowired LeaveTypeRepository leaveTypeRepository;
    @Autowired LeaveBalanceRepository leaveBalanceRepository;
    @Autowired LeaveRequestRepository leaveRequestRepository;
    @Autowired PasswordEncoder passwordEncoder;

    UserAccount hrAccount, leadAccount, memberAccount, otherAccount;
    Employee hr, lead, member, other;
    LeaveType casual;
    LeaveRequest memberRequest, otherRequest, leadRequest;
    LocalDate start = LocalDate.now().plusDays(10);
    int year = start.getYear();

    @BeforeEach
    void setUp() {
        hr = employee("Hana", "hr@test.com", null);
        hrAccount = account(hr, Role.HR_ADMIN, false);
        lead = employee("Lee", "lead@test.com", null);
        leadAccount = account(lead, Role.TEAM_LEAD, false);
        member = employee("Mia", "member@test.com", lead.getEmployeeId());
        memberAccount = account(member, Role.EMPLOYEE, false);
        other = employee("Omar", "other@test.com", null);
        otherAccount = account(other, Role.EMPLOYEE, false);

        casual = new LeaveType(null, "Casual Leave", 12, null, null);
        casual = leaveTypeRepository.save(casual);
        for (Employee e : new Employee[]{lead, member, other}) {
            leaveBalanceRepository.save(new LeaveBalance(null, e.getEmployeeId(), casual.getLeaveTypeId(), new BigDecimal("12"), BigDecimal.ZERO, BigDecimal.ZERO, year));
        }
        memberRequest = request(member);
        otherRequest = request(other);
        leadRequest = request(lead);
    }

    // --- Login ---

    @Test
    void apiRequiresLogin() throws Exception {
        mockMvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void pagesRedirectToLogin() throws Exception {
        mockMvc.perform(get("/leave-requests").accept(MediaType.TEXT_HTML)).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    void formLoginWithEmailAndPasswordSetsTokenCookie() throws Exception {
        mockMvc.perform(formLogin("/login").user("member@test.com").password("Secret123!"))
                .andExpect(redirectedUrl("/"))
                .andExpect(cookie().exists("LMS_TOKEN"))
                .andExpect(cookie().httpOnly("LMS_TOKEN", true));
        mockMvc.perform(formLogin("/login").user("member@test.com").password("wrong"))
                .andExpect(redirectedUrl("/login?error"))
                .andExpect(cookie().doesNotExist("LMS_TOKEN"));
    }

    @Test
    void disabledAccountCannotLogIn() throws Exception {
        memberAccount.setEnabled(false);
        userAccountRepository.save(memberAccount);
        mockMvc.perform(formLogin("/login").user("member@test.com").password("Secret123!"))
                .andExpect(cookie().doesNotExist("LMS_TOKEN"));
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"member@test.com\",\"password\":\"Secret123!\"}"))
                .andExpect(status().isUnauthorized());
    }

    // --- JWT tokens ---

    @Test
    void apiLoginReturnsABearerTokenThatNeedsNoCsrf() throws Exception {
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"Member@Test.com\",\"password\":\"Secret123!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.role").value("EMPLOYEE"))
                .andExpect(jsonPath("$.expiresAt").exists());
        String token = apiLogin("lead@test.com");
        mockMvc.perform(get("/api/me").with(bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("lead@test.com"));
        mockMvc.perform(post(approve(memberRequest)).with(bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    void wrongPasswordGetsNoToken() throws Exception {
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"member@test.com\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.token").doesNotExist());
    }

    @Test
    void cookieTokenOpensPagesButPostsStillNeedCsrf() throws Exception {
        Cookie cookie = new Cookie("LMS_TOKEN", apiLogin("lead@test.com"));
        mockMvc.perform(get("/leave-requests").cookie(cookie)).andExpect(status().isOk());
        mockMvc.perform(post(approve(memberRequest)).cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(approve(memberRequest)).cookie(cookie).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    void alteredOrMissingTokenIsRejected() throws Exception {
        String token = apiLogin("member@test.com");
        String altered = token.substring(0, token.length() - 3) + (token.endsWith("abc") ? "xyz" : "abc");
        mockMvc.perform(get("/api/me").with(bearer(altered))).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/me").with(bearer("not-a-token"))).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/me").cookie(new Cookie("LMS_TOKEN", altered)))
                .andExpect(status().isUnauthorized())
                .andExpect(cookie().maxAge("LMS_TOKEN", 0));
    }

    @Test
    void logoutCancelsTheToken() throws Exception {
        String token = apiLogin("member@test.com");
        mockMvc.perform(post("/api/auth/logout").with(bearer(token))).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/me").with(bearer(token))).andExpect(status().isUnauthorized());

        Cookie cookie = new Cookie("LMS_TOKEN", apiLogin("member@test.com"));
        mockMvc.perform(post("/logout").cookie(cookie).with(csrf()))
                .andExpect(redirectedUrl("/login?logout"))
                .andExpect(cookie().maxAge("LMS_TOKEN", 0));
        mockMvc.perform(get("/api/me").cookie(cookie)).andExpect(status().isUnauthorized());
    }

    @Test
    void passwordResetByHrCancelsTokens() throws Exception {
        String token = apiLogin("member@test.com");
        mockMvc.perform(post("/api/employees/" + member.getEmployeeId() + "/reset-password").with(as(hrAccount)).with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/me").with(bearer(token))).andExpect(status().isUnauthorized());
    }

    @Test
    void postWithoutCsrfTokenIsRejected() throws Exception {
        mockMvc.perform(post("/api/leave-requests/" + memberRequest.getLeaveRequestId() + "/approve").with(as(leadAccount)))
                .andExpect(status().isForbidden());
    }

    // --- Employee records ---

    @Test
    void employeeCannotListEmployees() throws Exception {
        mockMvc.perform(get("/api/employees").with(as(memberAccount))).andExpect(status().isForbidden());
    }

    @Test
    void teamLeadSeesOnlyOwnTeamWithoutSalary() throws Exception {
        mockMvc.perform(get("/api/employees").with(as(leadAccount)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].email").value("member@test.com"))
                .andExpect(jsonPath("$[0].salary").doesNotExist());
    }

    @Test
    void hrSeesAllEmployees() throws Exception {
        mockMvc.perform(get("/api/employees").with(as(hrAccount)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(4)));
    }

    @Test
    void employeeCanViewSelfButNotOthers() throws Exception {
        mockMvc.perform(get("/api/employees/" + member.getEmployeeId()).with(as(memberAccount))).andExpect(status().isOk());
        mockMvc.perform(get("/api/employees/" + other.getEmployeeId()).with(as(memberAccount))).andExpect(status().isForbidden());
    }

    @Test
    void searchFindsFullNamesAndInactiveEmployeesOnlyWhenAsked() throws Exception {
        other.setStatus(EmployeeStatus.RESIGNED);
        employeeRepository.save(other);
        mockMvc.perform(get("/api/employees/search").param("name", "omar").with(as(hrAccount)))
                .andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(get("/api/employees/search").param("name", "omar").param("includeInactive", "true").with(as(hrAccount)))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].status").value("RESIGNED"));
        mockMvc.perform(get("/api/employees/search").param("name", " MIA test ").with(as(hrAccount)))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].fullName").value("Mia Test"));
    }

    @Test
    void onlyHrCreatesEmployees() throws Exception {
        String body = """
                {"firstName":"Nia","lastName":"New","email":"nia@test.com","department":"IT","designation":"Dev",
                 "salary":1000,"dateOfJoining":"2026-01-05","role":"EMPLOYEE","reporting_LeadId":%d}
                """.formatted(lead.getEmployeeId());
        mockMvc.perform(post("/api/employees").with(as(leadAccount)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/employees").with(as(hrAccount)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("nia@test.com"))
                .andExpect(jsonPath("$.temporaryPassword").isNotEmpty())
                .andExpect(jsonPath("$.employee.reporting_LeadId").value(lead.getEmployeeId()));

        UserAccount created = userAccountRepository.findByUsernameIgnoreCase("nia@test.com").orElseThrow();
        assertThat(created.isMustChangePassword()).isTrue();
        assertThat(created.getRole()).isEqualTo(Role.EMPLOYEE);
    }

    @Test
    void duplicateEmailOrPhoneIsBlocked() throws Exception {
        String first = """
                {"firstName":"Nia","lastName":"New","email":"nia@test.com","phone":"+91 98765 43210","department":"IT",
                 "designation":"Dev","salary":1000,"dateOfJoining":"2026-01-05","role":"EMPLOYEE"}
                """;
        mockMvc.perform(post("/api/employees").with(as(hrAccount)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(first))
                .andExpect(status().isCreated());

        String sameEmail = first.replace("nia@test.com", "NIA@Test.com").replace("98765 43210", "11111 22222");
        mockMvc.perform(post("/api/employees").with(as(hrAccount)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(sameEmail))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("email already exists: Nia New")));

        String samePhone = first.replace("nia@test.com", "nia.other@test.com").replace("+91 98765 43210", "9876543210");
        mockMvc.perform(post("/api/employees").with(as(hrAccount)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(samePhone))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("phone number already exists: Nia New")));
        assertThat(userAccountRepository.findByUsernameIgnoreCase("nia.other@test.com")).isEmpty();
    }

    @Test
    void reportingLeadMustBeATeamLead() throws Exception {
        String body = """
                {"firstName":"Nia","lastName":"New","email":"nia@test.com","department":"IT","designation":"Dev",
                 "salary":1000,"dateOfJoining":"2026-01-05","role":"EMPLOYEE","reporting_LeadId":%d}
                """.formatted(other.getEmployeeId());
        mockMvc.perform(post("/api/employees").with(as(hrAccount)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Reporting lead must have the Team Lead or HR role"));
    }

    @Test
    void employeeUpdatesOwnPhoneOnly() throws Exception {
        mockMvc.perform(put("/api/me").with(as(memberAccount)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"phone\":\"+91 98765 43210\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").value("+91 98765 43210"));
        mockMvc.perform(put("/api/employees/" + member.getEmployeeId()).with(as(memberAccount)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"designation\":\"CEO\"}"))
                .andExpect(status().isForbidden());
    }

    // --- Leave balances ---

    @Test
    void balanceVisibility() throws Exception {
        String path = "/api/leave-balance/employee/%d/" + year;
        mockMvc.perform(get(path.formatted(member.getEmployeeId())).with(as(memberAccount))).andExpect(status().isOk());
        mockMvc.perform(get(path.formatted(other.getEmployeeId())).with(as(memberAccount))).andExpect(status().isForbidden());
        mockMvc.perform(get(path.formatted(member.getEmployeeId())).with(as(leadAccount))).andExpect(status().isOk());
        mockMvc.perform(get(path.formatted(other.getEmployeeId())).with(as(leadAccount))).andExpect(status().isForbidden());
        mockMvc.perform(get(path.formatted(other.getEmployeeId())).with(as(hrAccount))).andExpect(status().isOk());
    }

    @Test
    void onlyHrCreatesBalances() throws Exception {
        String url = "/api/leave-balance?employeeId=%d&leaveTypeId=%d&totalDays=5&year=%d"
                .formatted(other.getEmployeeId(), casual.getLeaveTypeId(), year + 1);
        mockMvc.perform(post(url).with(as(leadAccount)).with(csrf())).andExpect(status().isForbidden());
        mockMvc.perform(post(url).with(as(hrAccount)).with(csrf())).andExpect(status().isCreated());
    }

    @Test
    void duplicateBalanceIsRejected() throws Exception {
        String url = "/api/leave-balance?employeeId=%d&leaveTypeId=%d&totalDays=5&year=%d"
                .formatted(member.getEmployeeId(), casual.getLeaveTypeId(), year);
        mockMvc.perform(post(url).with(as(hrAccount)).with(csrf())).andExpect(status().isConflict());
    }

    // --- Leave requests ---

    @Test
    void submittedRequestIsAlwaysForTheLoggedInEmployee() throws Exception {
        String body = """
                {"employeeId":%d,"leaveTypeId":%d,"startDate":"%s","endDate":"%s","reason":"Family event"}
                """.formatted(other.getEmployeeId(), casual.getLeaveTypeId(), start.plusDays(5), start.plusDays(6));
        mockMvc.perform(post("/api/leave-requests").with(as(memberAccount)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.employeeId").value(member.getEmployeeId()))
                .andExpect(jsonPath("$.status").value("PENDING_TL"));
    }

    @Test
    void requestListIsFilteredByRole() throws Exception {
        mockMvc.perform(get("/api/leave-requests").with(as(memberAccount)))
                .andExpect(jsonPath("$", hasSize(1)));
        mockMvc.perform(get("/api/leave-requests").with(as(leadAccount)))
                .andExpect(jsonPath("$", hasSize(2))); // own + member's
        mockMvc.perform(get("/api/leave-requests").with(as(hrAccount)))
                .andExpect(jsonPath("$", hasSize(3)));
    }

    @Test
    void teamLeadThenHrApproveAndBalanceIsDeductedAtTheEnd() throws Exception {
        mockMvc.perform(post(approve(memberRequest)).with(as(leadAccount)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"comments\":\"Fine by me\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_HR"))
                .andExpect(jsonPath("$.approvals[0].level").value("TEAM_LEAD"))
                .andExpect(jsonPath("$.approvals[0].approverName").value("Lee Test"));
        assertThat(balanceOf(member).getUsedDays()).isZero();

        mockMvc.perform(post(approve(memberRequest)).with(as(hrAccount)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"comments\":\"Enjoy\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approvals", hasSize(2)))
                .andExpect(jsonPath("$.approvals[1].level").value("HR"));

        LeaveBalance balance = balanceOf(member);
        assertThat(balance.getUsedDays()).isEqualByComparingTo("2");
        assertThat(balance.getRemainingDays()).isEqualByComparingTo("10");

        mockMvc.perform(get("/api/leave-requests/statistics/%d/%d".formatted(member.getEmployeeId(), year))
                        .with(as(memberAccount)))
                .andExpect(content().string("2"));
    }

    @Test
    void teamLeadCannotApproveOutsideTeam() throws Exception {
        mockMvc.perform(post(approve(otherRequest)).with(as(leadAccount)).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void nobodyApprovesTheirOwnRequest() throws Exception {
        mockMvc.perform(post(approve(leadRequest)).with(as(leadAccount)).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void employeeCannotApprove() throws Exception {
        mockMvc.perform(post(approve(otherRequest)).with(as(memberAccount)).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void hrCanApproveAnyone() throws Exception {
        mockMvc.perform(post(approve(otherRequest)).with(as(hrAccount)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    void rejectionNeedsAReason() throws Exception {
        String url = "/api/leave-requests/" + memberRequest.getLeaveRequestId() + "/reject";
        mockMvc.perform(post(url).with(as(leadAccount)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"comments\":\" \"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(url).with(as(leadAccount)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"comments\":\"Release week\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }

    @Test
    void employeeCannotCancelSomeoneElsesRequest() throws Exception {
        mockMvc.perform(post("/api/leave-requests/" + otherRequest.getLeaveRequestId() + "/cancel")
                        .with(as(memberAccount)).with(csrf()))
                .andExpect(status().isForbidden());
    }

    // --- Temporary passwords ---

    @Test
    void temporaryPasswordMustBeChangedFirst() throws Exception {
        memberAccount.setMustChangePassword(true);
        userAccountRepository.save(memberAccount);

        mockMvc.perform(get("/api/leave-requests").with(as(memberAccount))).andExpect(status().isForbidden());
        mockMvc.perform(get("/leave-requests").with(as(memberAccount)))
                .andExpect(redirectedUrl("/change-password"));

        mockMvc.perform(post("/api/me/password").with(as(memberAccount)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"wrong\",\"newPassword\":\"NewSecret456\"}"))
                .andExpect(status().isBadRequest());
        String oldToken = apiLogin("member@test.com");
        mockMvc.perform(post("/api/me/password").with(as(memberAccount)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"Secret123!\",\"newPassword\":\"NewSecret456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").exists())
                .andExpect(jsonPath("$.mustChangePassword").value(false))
                .andExpect(cookie().exists("LMS_TOKEN"));
        // Tokens issued before the change no longer work
        mockMvc.perform(get("/api/me").with(bearer(oldToken))).andExpect(status().isUnauthorized());

        UserAccount updated = userAccountRepository.findById(memberAccount.getUserId()).orElseThrow();
        assertThat(updated.isMustChangePassword()).isFalse();
        assertThat(passwordEncoder.matches("NewSecret456", updated.getPasswordHash())).isTrue();
    }

    // --- Pages ---

    @Test
    void hrPagesAreHrOnly() throws Exception {
        mockMvc.perform(get("/employees").with(as(leadAccount))).andExpect(status().isForbidden());
        mockMvc.perform(get("/dashboard").with(as(memberAccount))).andExpect(status().isForbidden());
        mockMvc.perform(get("/employees").with(as(hrAccount))).andExpect(status().isOk());
    }

    // --- HR changes apply to users who are already logged in ---

    @Test
    void deactivatedUserIsLoggedOutOnTheNextRequest() throws Exception {
        RequestPostProcessor loggedIn = bearer(apiLogin("member@test.com")); // token issued before HR acts
        mockMvc.perform(get("/api/me").with(loggedIn)).andExpect(status().isOk());
        mockMvc.perform(delete("/api/employees/" + member.getEmployeeId()).with(as(hrAccount)).with(csrf()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/me").with(loggedIn)).andExpect(status().isUnauthorized());
    }

    @Test
    void roleChangeAppliesWithoutLoggingOut() throws Exception {
        RequestPostProcessor loggedInLead = bearer(apiLogin("lead@test.com"));
        mockMvc.perform(get("/api/employees").with(loggedInLead)).andExpect(status().isOk());
        member.setReporting_LeadId(null);
        employeeRepository.save(member);
        mockMvc.perform(put("/api/employees/" + lead.getEmployeeId()).with(as(hrAccount)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"EMPLOYEE\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/employees").with(loggedInLead)).andExpect(status().isForbidden());
    }

    @Test
    void everyPageRendersForEachRole() throws Exception {
        for (String page : new String[]{"/", "/profile", "/leave-requests", "/comp-off", "/leave-balance", "/change-password"}) {
            for (UserAccount account : new UserAccount[]{memberAccount, leadAccount, hrAccount}) {
                mockMvc.perform(get(page).with(as(account))).andExpect(status().isOk());
            }
        }
        mockMvc.perform(get("/dashboard").with(as(hrAccount))).andExpect(status().isOk());
        mockMvc.perform(get("/login")).andExpect(status().isOk());
    }

    // --- Helpers ---

    private String apiLogin(String username) throws Exception {
        String json = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"Secret123!\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
    }

    private RequestPostProcessor bearer(String token) {
        return request -> {
            request.addHeader("Authorization", "Bearer " + token);
            return request;
        };
    }

    private RequestPostProcessor as(UserAccount account) {
        return user(new AppUserDetails(userAccountRepository.findById(account.getUserId()).orElseThrow()));
    }

    private LeaveBalance balanceOf(Employee e) {
        return leaveBalanceRepository
                .findByEmployeeIdAndLeaveTypeIdAndYear(e.getEmployeeId(), casual.getLeaveTypeId(), year).orElseThrow();
    }

    private String approve(LeaveRequest request) {
        return "/api/leave-requests/" + request.getLeaveRequestId() + "/approve";
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

    private UserAccount account(Employee e, Role role, boolean mustChange) {
        UserAccount a = new UserAccount();
        a.setUsername(e.getEmail());
        a.setPasswordHash(passwordEncoder.encode("Secret123!"));
        a.setRole(role);
        a.setEmployeeId(e.getEmployeeId());
        a.setEnabled(true);
        a.setMustChangePassword(mustChange);
        return userAccountRepository.save(a);
    }

    private LeaveRequest request(Employee e) {
        LeaveRequest r = new LeaveRequest();
        r.setEmployeeId(e.getEmployeeId());
        r.setLeaveTypeId(casual.getLeaveTypeId());
        r.setStartDate(start);
        r.setEndDate(start.plusDays(1));
        r.setReason("Trip");
        r.setStatus(e.getReporting_LeadId() == null ? LeaveStatus.PENDING_HR : LeaveStatus.PENDING_TL);
        r.setRequestDate(LocalDate.now());
        return leaveRequestRepository.save(r);
    }
}
