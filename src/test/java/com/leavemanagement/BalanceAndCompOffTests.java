package com.leavemanagement;

import com.leavemanagement.entity.*;
import com.leavemanagement.repository.*;
import com.leavemanagement.security.AppUserDetails;
import com.leavemanagement.service.LeaveAccrualService;
import com.leavemanagement.service.LeaveService;
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
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 3: half-day leave, negative balances, comp-off, HR adjustments and the balance ledger;
 * monthly leave: one shared balance that grows every month and carries over
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "spring.datasource.url=jdbc:sqlite:target/test-${random.uuid}.db?date_class=TEXT")
class BalanceAndCompOffTests {

    @Autowired MockMvc mockMvc;
    @Autowired EmployeeRepository employeeRepository;
    @Autowired UserAccountRepository userAccountRepository;
    @Autowired LeaveTypeRepository leaveTypeRepository;
    @Autowired LeaveBalanceRepository leaveBalanceRepository;
    @Autowired LeaveTransactionRepository leaveTransactionRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired LeaveService leaveService;
    @Autowired LeaveAccrualService leaveAccrualService;

    UserAccount hrAccount, leadAccount, memberAccount, soloAccount;
    Employee hr, lead, member, solo;
    LeaveType casual, compOff, monthly;
    LocalDate start = LocalDate.now().plusDays(10);
    int year = start.getYear();
    LocalDate lastSaturday = LocalDate.now().minusDays(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.SATURDAY));

    @BeforeEach
    void setUp() throws Exception {
        hr = employee("Hana", "hr@test.com", null);
        hrAccount = account(hr, Role.HR_ADMIN);
        lead = employee("Lee", "lead@test.com", null);
        leadAccount = account(lead, Role.TEAM_LEAD);
        member = employee("Mia", "member@test.com", lead.getEmployeeId());
        memberAccount = account(member, Role.EMPLOYEE);
        solo = employee("Sam", "solo@test.com", null);
        soloAccount = account(solo, Role.EMPLOYEE);

        casual = leaveTypeRepository.save(new LeaveType(null, "Casual Leave", 0, null, null));
        compOff = leaveTypeRepository.findBySystemCode("COMP_OFF").orElseThrow();
        monthly = leaveTypeRepository.findBySystemCode("MONTHLY").orElseThrow();
        for (Employee e : new Employee[]{member, solo}) {
            leaveService.creditMonth(e.getEmployeeId(), YearMonth.now(), new BigDecimal("12"));
        }
    }

    // --- Half-day leave ---

    @Test
    void halfDayLeaveCountsAsHalfADay() throws Exception {
        int id = submitLeave(soloAccount, casual, start, start, "FIRST_HALF");
        mockMvc.perform(get("/api/leave-requests/" + id).with(as(soloAccount)))
                .andExpect(jsonPath("$.numberOfDays").value(0.5))
                .andExpect(jsonPath("$.halfDay").value("FIRST_HALF"))
                .andExpect(jsonPath("$.balanceAfterApproval").value(11.5));
        approve("leave-requests", id, hrAccount).andExpect(jsonPath("$.status").value("APPROVED"));
        assertThat(balance(solo, monthly, 0).getRemainingDays()).isEqualByComparingTo("11.5");
    }

    @Test
    void halfDayMustBeASingleDay() throws Exception {
        applyLeave(soloAccount, casual, start, start.plusDays(1), "SECOND_HALF")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("A half-day leave must start and end on the same day"));
    }

    @Test
    void leaveCannotSpanTwoYears() throws Exception {
        LocalDate dec31 = LocalDate.of(LocalDate.now().getYear(), 12, 31);
        applyLeave(soloAccount, casual, dec31, dec31.plusDays(1), null).andExpect(status().isBadRequest());
    }

    // --- Monthly leave and negative balance ---

    @Test
    void everyLeaveTypeTakesFromTheMonthlyBalance() throws Exception {
        LeaveType sick = leaveTypeRepository.save(new LeaveType(null, "Sick Leave", 0, null, null));
        approve("leave-requests", submitLeave(soloAccount, casual, start, start, null), hrAccount);
        approve("leave-requests", submitLeave(soloAccount, sick, start.plusDays(3), start.plusDays(4), null), hrAccount);
        assertThat(balance(solo, monthly, 0).getRemainingDays()).isEqualByComparingTo("9");
        assertThat(leaveBalanceRepository.findByEmployeeId(solo.getEmployeeId())).hasSize(1);
    }

    @Test
    void approvalBeyondTheBalanceGoesNegative() throws Exception {
        LeaveType sick = leaveTypeRepository.save(new LeaveType(null, "Sick Leave", 0, null, null));
        adjust(hrAccount, solo, monthly, "-12", "Correction").andExpect(status().isOk());
        int id = submitLeave(soloAccount, sick, start, start.plusDays(1), null);
        mockMvc.perform(get("/api/leave-requests/" + id).with(as(hrAccount)))
                .andExpect(jsonPath("$.balanceAfterApproval").value(-2));
        approve("leave-requests", id, hrAccount).andExpect(jsonPath("$.status").value("APPROVED"));
        assertThat(balance(solo, monthly, 0).getRemainingDays()).isEqualByComparingTo("-2");
    }

    @Test
    void unusedMonthlyDaysCarryOverAndEachMonthIsAddedOnce() throws Exception {
        YearMonth thisMonth = YearMonth.now();
        // One day taken this month, then the next two months are added (also when the app was off for a while)
        approve("leave-requests", submitLeave(soloAccount, casual, start, start, null), hrAccount);
        leaveAccrualService.creditDueMonths(thisMonth.plusMonths(2));
        assertThat(balance(solo, monthly, 0).getRemainingDays()).isEqualByComparingTo("16"); // 12 - 1 + 2.5 + 2.5
        // Running again adds nothing
        assertThat(leaveAccrualService.creditDueMonths(thisMonth.plusMonths(2))).isZero();
        assertThat(balance(solo, monthly, 0).getRemainingDays()).isEqualByComparingTo("16");
        // Into the next year too: no reset
        leaveAccrualService.creditDueMonths(thisMonth.plusMonths(14));
        assertThat(balance(solo, monthly, 0).getRemainingDays()).isEqualByComparingTo("46");
    }

    @Test
    void employeesNeverCreditedStartWithTheCurrentMonthAndInactiveOnesGetNothing() throws Exception {
        lead.setStatus(EmployeeStatus.INACTIVE);
        employeeRepository.save(lead);
        leaveAccrualService.creditDueMonths(YearMonth.now());
        assertThat(balance(hr, monthly, 0).getRemainingDays()).isEqualByComparingTo("2.5");
        assertThat(leaveBalanceRepository.findByEmployeeId(lead.getEmployeeId())).isEmpty();
        assertThat(balance(solo, monthly, 0).getRemainingDays()).isEqualByComparingTo("12"); // already had this month
    }

    @Test
    void reactivatedEmployeesSkipTheMonthsTheyWereInactive() throws Exception {
        YearMonth thisMonth = YearMonth.now();
        solo.setStatus(EmployeeStatus.INACTIVE);
        employeeRepository.save(solo);
        leaveAccrualService.creditDueMonths(thisMonth.plusMonths(3));     // three months away: nothing added
        assertThat(balance(solo, monthly, 0).getRemainingDays()).isEqualByComparingTo("12");
        leaveAccrualService.restartAt(solo.getEmployeeId(), thisMonth.plusMonths(3)); // back: only that month
        assertThat(balance(solo, monthly, 0).getRemainingDays()).isEqualByComparingTo("14.5");
        solo.setStatus(EmployeeStatus.ACTIVE);
        employeeRepository.save(solo);
        leaveAccrualService.creditDueMonths(thisMonth.plusMonths(4));     // and on from there
        assertThat(balance(solo, monthly, 0).getRemainingDays()).isEqualByComparingTo("17");
    }

    @Test
    void reactivationInTheSameMonthAddsNothing() throws Exception {
        String body = "{\"status\":\"%s\"}";
        mockMvc.perform(put("/api/employees/" + solo.getEmployeeId()).with(as(hrAccount)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body.formatted("INACTIVE"))).andExpect(status().isOk());
        mockMvc.perform(put("/api/employees/" + solo.getEmployeeId()).with(as(hrAccount)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body.formatted("ACTIVE"))).andExpect(status().isOk());
        assertThat(balance(solo, monthly, 0).getRemainingDays()).isEqualByComparingTo("12"); // already had this month
    }

    // --- Comp-off ---

    @Test
    void compOffIsApprovedByLeadThenHrAndCredited() throws Exception {
        int id = submitCompOff(memberAccount, lastSaturday, lastSaturday.plusDays(1));
        mockMvc.perform(get("/api/comp-off/" + id).with(as(memberAccount)))
                .andExpect(jsonPath("$.status").value("PENDING_TL"))
                .andExpect(jsonPath("$.days").value(2));
        approve("comp-off", id, leadAccount).andExpect(jsonPath("$.status").value("PENDING_HR"));
        approve("comp-off", id, leadAccount).andExpect(status().isForbidden());
        approve("comp-off", id, hrAccount)
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approvals[1].level").value("HR"));

        LeaveBalance balance = balance(member, compOff, 0);
        assertThat(balance.getRemainingDays()).isEqualByComparingTo("2");
        mockMvc.perform(get("/api/leave-balance/transactions/%d/%d".formatted(member.getEmployeeId(), year)).with(as(memberAccount)))
                .andExpect(jsonPath("$[*].type", hasItem("COMP_OFF_CREDIT")));
    }

    @Test
    void compOffBalanceHasNoYearAndCoversLeaveInAnyYear() throws Exception {
        int id = submitCompOff(soloAccount, lastSaturday, lastSaturday);
        approve("comp-off", id, hrAccount);

        // Shown with every year's balances
        mockMvc.perform(get("/api/leave-balance/employee/%d/%d".formatted(solo.getEmployeeId(), year + 1)).with(as(soloAccount)))
                .andExpect(jsonPath("$[0].leaveTypeId").value(compOff.getLeaveTypeId()))
                .andExpect(jsonPath("$[0].year").value(0));

        // Used for leave next year
        LocalDate nextYear = LocalDate.of(year + 1, 1, 6);
        int leaveId = submitLeave(soloAccount, compOff, nextYear, nextYear, null);
        approve("leave-requests", leaveId, hrAccount).andExpect(jsonPath("$.status").value("APPROVED"));
        assertThat(balance(solo, compOff, 0).getRemainingDays()).isEqualByComparingTo("0");
    }

    @Test
    void compOffOnlyForDaysAlreadyWorkedAndOnlyOnce() throws Exception {
        applyCompOff(soloAccount, LocalDate.now().plusDays(1), LocalDate.now().plusDays(1))
                .andExpect(status().isBadRequest());
        submitCompOff(soloAccount, lastSaturday, lastSaturday);
        applyCompOff(soloAccount, lastSaturday, lastSaturday).andExpect(status().isConflict());
    }

    @Test
    void compOffCanBeWithdrawnOnlyWhilePending() throws Exception {
        int pending = submitCompOff(memberAccount, lastSaturday, lastSaturday);
        cancel("comp-off", pending, memberAccount).andExpect(jsonPath("$.status").value("CANCELLED"));

        int approved = submitCompOff(soloAccount, lastSaturday, lastSaturday);
        approve("comp-off", approved, hrAccount);
        cancel("comp-off", approved, soloAccount).andExpect(status().isConflict());
    }

    @Test
    void builtInTypesCannotBeDeletedOrRenamed() throws Exception {
        for (LeaveType builtIn : new LeaveType[]{compOff, monthly}) {
            mockMvc.perform(delete("/api/leave-types/" + builtIn.getLeaveTypeId()).with(as(hrAccount)).with(csrf()))
                    .andExpect(status().isBadRequest());
            mockMvc.perform(put("/api/leave-types/" + builtIn.getLeaveTypeId()).with(as(hrAccount)).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"leaveTypeName\":\"Bonus\"}"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void aLeaveTypeNeedsOnlyAName() throws Exception {
        mockMvc.perform(post("/api/leave-types").with(as(hrAccount)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"leaveTypeName\":\" Sick Leave \"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.leaveTypeName").value("Sick Leave"))
                .andExpect(jsonPath("$.maxDaysPerYear").value(0));
    }

    @Test
    void balancesAreNoLongerAllottedPerTypeAndYear() throws Exception {
        mockMvc.perform(post("/api/leave-balance?employeeId=%d&leaveTypeId=%d&totalDays=5&year=%d"
                        .formatted(solo.getEmployeeId(), casual.getLeaveTypeId(), year)).with(as(hrAccount)).with(csrf()))
                .andExpect(status().is4xxClientError());
    }

    // --- HR adjustments ---

    @Test
    void hrAdjustsBalanceWithAReason() throws Exception {
        adjust(hrAccount, solo, monthly, "1.5", "Carried over from last year").andExpect(status().isOk());
        assertThat(balance(solo, monthly, 0).getRemainingDays()).isEqualByComparingTo("13.5");
        adjust(hrAccount, solo, monthly, "-3", "Leave taken before the system went live").andExpect(status().isOk());
        assertThat(balance(solo, monthly, 0).getRemainingDays()).isEqualByComparingTo("10.5");
    }

    @Test
    void adjustmentRules() throws Exception {
        adjust(leadAccount, solo, casual, "1", "Because").andExpect(status().isForbidden());
        adjust(hrAccount, solo, casual, "1", " ").andExpect(status().isBadRequest());
        adjust(hrAccount, solo, casual, "0.3", "Odd").andExpect(status().isBadRequest());
        adjust(hrAccount, solo, casual, "0", "Nothing").andExpect(status().isBadRequest());
    }

    @Test
    void hrCanCorrectTheCompOffBalance() throws Exception {
        adjust(hrAccount, solo, compOff, "1", "Worked on a holiday before the system went live").andExpect(status().isOk());
        assertThat(balance(solo, compOff, 0).getRemainingDays()).isEqualByComparingTo("1");
    }

    // --- Ledger ---

    @Test
    void ledgerAlwaysAddsUpToTheBalance() throws Exception {
        int a = submitLeave(soloAccount, casual, start, start.plusDays(2), null);
        approve("leave-requests", a, hrAccount);
        int b = submitLeave(soloAccount, casual, start.plusDays(5), start.plusDays(5), "SECOND_HALF");
        approve("leave-requests", b, hrAccount);
        cancel("leave-requests", a, soloAccount);
        adjust(hrAccount, solo, monthly, "2", "Bonus days");

        List<LeaveTransaction> ledger = leaveTransactionRepository.findByEmployeeIdAndYearInOrderByCreatedAtAscTransactionIdAsc(
                solo.getEmployeeId(), List.of(LeaveBalance.NO_YEAR));
        BigDecimal sum = ledger.stream().map(LeaveTransaction::getDays).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(ledger).extracting(LeaveTransaction::getType).containsExactly(
                TransactionType.MONTHLY_CREDIT, TransactionType.LEAVE_DEDUCTED, TransactionType.LEAVE_DEDUCTED,
                TransactionType.LEAVE_RESTORED, TransactionType.ADJUSTMENT);
        assertThat(sum).isEqualByComparingTo(balance(solo, monthly, 0).getRemainingDays());
        assertThat(sum).isEqualByComparingTo("13.5");
        assertThat(ledger.get(4).getCreatedByName()).isEqualTo("Hana Test");
    }

    @Test
    void ledgerIsVisibleOnlyToThoseWhoCanSeeTheBalance() throws Exception {
        String path = "/api/leave-balance/transactions/%d/%d".formatted(solo.getEmployeeId(), year);
        mockMvc.perform(get(path).with(as(soloAccount))).andExpect(status().isOk());
        mockMvc.perform(get(path).with(as(memberAccount))).andExpect(status().isForbidden());
        mockMvc.perform(get(path).with(as(hrAccount))).andExpect(status().isOk());
    }

    // --- Helpers ---

    private ResultActions applyLeave(UserAccount account, LeaveType type, LocalDate from, LocalDate to, String halfDay) throws Exception {
        String body = """
                {"leaveTypeId":%d,"startDate":"%s","endDate":"%s","reason":"Holiday"%s}
                """.formatted(type.getLeaveTypeId(), from, to, halfDay == null ? "" : ",\"halfDay\":\"" + halfDay + "\"");
        return mockMvc.perform(post("/api/leave-requests").with(as(account)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private int submitLeave(UserAccount account, LeaveType type, LocalDate from, LocalDate to, String halfDay) throws Exception {
        String json = applyLeave(account, type, from, to, halfDay).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return Integer.parseInt(json.replaceAll(".*\"leaveRequestId\":(\\d+).*", "$1"));
    }

    private ResultActions applyCompOff(UserAccount account, LocalDate from, LocalDate to) throws Exception {
        String body = """
                {"workedFrom":"%s","workedTo":"%s","reason":"Release weekend"}
                """.formatted(from, to);
        return mockMvc.perform(post("/api/comp-off").with(as(account)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private int submitCompOff(UserAccount account, LocalDate from, LocalDate to) throws Exception {
        String json = applyCompOff(account, from, to).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return Integer.parseInt(json.replaceAll(".*\"compOffRequestId\":(\\d+).*", "$1"));
    }

    private ResultActions approve(String api, int id, UserAccount account) throws Exception {
        return mockMvc.perform(post("/api/" + api + "/" + id + "/approve").with(as(account)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{}"));
    }

    private ResultActions cancel(String api, int id, UserAccount account) throws Exception {
        return mockMvc.perform(post("/api/" + api + "/" + id + "/cancel").with(as(account)).with(csrf()));
    }

    private ResultActions adjust(UserAccount account, Employee e, LeaveType type, String days, String reason) throws Exception {
        String body = """
                {"employeeId":%d,"leaveTypeId":%d,"year":%d,"days":%s,"reason":"%s"}
                """.formatted(e.getEmployeeId(), type.getLeaveTypeId(), year, days, reason);
        return mockMvc.perform(post("/api/leave-balance/adjust").with(as(account)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private LeaveBalance balance(Employee e, LeaveType type, int y) {
        return leaveBalanceRepository.findByEmployeeIdAndLeaveTypeIdAndYear(e.getEmployeeId(), type.getLeaveTypeId(), y)
                .orElseThrow();
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
