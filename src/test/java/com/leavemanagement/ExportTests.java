package com.leavemanagement;

import com.leavemanagement.entity.*;
import com.leavemanagement.repository.*;
import com.leavemanagement.security.AppUserDetails;
import com.leavemanagement.service.LeaveService;
import jakarta.servlet.http.Cookie;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 5: Excel downloads. Each test opens the returned file and checks what is in it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "spring.datasource.url=jdbc:sqlite:target/test-${random.uuid}.db?date_class=TEXT")
class ExportTests {
    static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Autowired MockMvc mockMvc;
    @Autowired EmployeeRepository employeeRepository;
    @Autowired UserAccountRepository userAccountRepository;
    @Autowired LeaveTypeRepository leaveTypeRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired LeaveService leaveService;

    UserAccount hrAccount, leadAccount, memberAccount, soloAccount;
    Employee hr, lead, member, solo;
    LeaveType casual, sick, monthly;
    LocalDate start = LocalDate.now().plusDays(10);
    int year = start.getYear();

    @BeforeEach
    void setUp() throws Exception {
        hr = employee("Hana", "hr@test.com", "HR", null);
        hrAccount = account(hr, Role.HR_ADMIN);
        lead = employee("Lee", "lead@test.com", "IT", null);
        leadAccount = account(lead, Role.TEAM_LEAD);
        member = employee("Mia", "member@test.com", "IT", lead.getEmployeeId());
        memberAccount = account(member, Role.EMPLOYEE);
        solo = employee("Sam", "solo@test.com", "Sales", null);
        soloAccount = account(solo, Role.EMPLOYEE);

        casual = leaveTypeRepository.save(new LeaveType(null, "Casual Leave", 0, null, null));
        sick = leaveTypeRepository.save(new LeaveType(null, "Sick Leave", 0, null, null));
        monthly = leaveTypeRepository.findBySystemCode("MONTHLY").orElseThrow();
        giveMonthlyDays(member, "12");
        giveMonthlyDays(solo, "13");
    }

    @Test
    void leaveBalanceExportHasOneRowPerBalanceAndTotals() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(get("/api/reports/leave-balance/export?year=" + year)
                        .with(as(hrAccount)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", XLSX))
                .andExpect(header().string("Content-Disposition",
                        containsString("attachment; filename=\"leave-balance-" + year + ".xlsx\"")))
                .andReturn().getResponse();

        List<List<String>> rows = read(response);
        assertThat(rows.get(0)).startsWith("Employee ID", "Employee", "Team", "Lead");
        assertThat(rows.get(0)).endsWith("Remaining");
        assertThat(rows).hasSize(1 + 2 + 1); // header, 2 monthly balances, totals
        assertThat(rows.get(1)).contains("Mia Test", "IT", "Lee Test", "Monthly Leave", "12");
        assertThat(rows.get(3).get(0)).isEqualTo("Total");
        assertThat(rows.get(3).get(7)).isEqualTo("25"); // monthly days added 12 + 13
    }

    @Test
    void leaveBalanceExportUsesTheDashboardFilters() throws Exception {
        List<List<String>> rows = read(mockMvc.perform(get("/api/reports/leave-balance/export?year=%d&department=Sales&leaveTypeId=%d"
                        .formatted(year, monthly.getLeaveTypeId())).with(as(hrAccount)))
                .andExpect(header().string("Content-Disposition", containsString("leave-balance-" + year + "-sales-type")))
                .andReturn().getResponse());
        assertThat(rows).hasSize(3);
        assertThat(rows.get(1)).contains("Sam Test", "Monthly Leave");
    }

    @Test
    void negativeBalancesAreSortedMostNegativeFirst() throws Exception {
        adjust(solo, monthly, "-11");                                                         // 13 - 11 = 2
        approve(submitLeave(soloAccount, sick, start, start.plusDays(2)), hrAccount);           // 2 - 3 = -1
        approve(submitLeave(soloAccount, casual, start.plusDays(5), start.plusDays(5)), hrAccount); // -1 - 1 = -2
        adjust(member, monthly, "-17");                                                       // 12 - 17 = -5

        List<List<String>> rows = read(mockMvc.perform(get("/api/reports/negative-balances/export?year=" + year)
                .with(as(hrAccount))).andReturn().getResponse());
        assertThat(rows).hasSize(3);
        assertThat(rows.get(1)).contains("Mia Test", "-5");
        assertThat(rows.get(2)).contains("Sam Test", "-2");
        assertThat(rows.get(1).get(7)).isNotEmpty(); // below zero since
    }

    @Test
    void leaveRequestExportShowsBothApprovalSteps() throws Exception {
        int id = submitLeave(memberAccount, casual, start, start.plusDays(1));
        approve(id, leadAccount);
        approve(id, hrAccount);
        submitLeave(soloAccount, casual, start.plusDays(40), start.plusDays(40)); // outside the range below

        String url = "/api/reports/leave-requests/export?from=%s&to=%s".formatted(start, start.plusDays(5));
        List<List<String>> rows = read(mockMvc.perform(get(url).with(as(hrAccount)))
                .andExpect(status().isOk()).andReturn().getResponse());
        assertThat(rows).hasSize(2);
        List<String> header = rows.get(0);
        List<String> row = rows.get(1);
        assertThat(row.get(header.indexOf("Employee"))).isEqualTo("Mia Test");
        assertThat(row.get(header.indexOf("Status"))).isEqualTo("Approved");
        assertThat(row.get(header.indexOf("Days"))).isEqualTo("2");
        assertThat(row.get(header.indexOf("Team Lead step"))).isEqualTo("Approved");
        assertThat(row.get(header.indexOf("Team Lead step by"))).isEqualTo("Lee Test");
        assertThat(row.get(header.indexOf("HR step"))).isEqualTo("Approved");
        assertThat(row.get(header.indexOf("HR step by"))).isEqualTo("Hana Test");
    }

    @Test
    void leaveRequestExportFiltersByStatus() throws Exception {
        submitLeave(memberAccount, casual, start, start);
        approve(submitLeave(soloAccount, casual, start, start), hrAccount);
        String url = "/api/reports/leave-requests/export?from=%s&to=%s&status=PENDING_TL".formatted(start, start);
        List<List<String>> rows = read(mockMvc.perform(get(url).with(as(hrAccount))).andReturn().getResponse());
        assertThat(rows).hasSize(2);
        assertThat(rows.get(1)).contains("Mia Test", "Waiting for Team Lead");
    }

    @Test
    void ledgerExportListsEveryBalanceChange() throws Exception {
        approve(submitLeave(soloAccount, casual, start, start), hrAccount);
        List<List<String>> rows = read(mockMvc.perform(get("/api/reports/transactions/export?employeeId=%d&year=%d"
                        .formatted(solo.getEmployeeId(), year)).with(as(hrAccount)))
                .andExpect(header().string("Content-Disposition", containsString("balance-history-" + solo.getEmployeeId() + "-sam-test")))
                .andReturn().getResponse());
        assertThat(rows.get(0)).containsExactly("Date", "Leave type", "Year", "Change", "Days", "Details", "Request ID", "By");
        assertThat(rows).hasSize(3); // monthly leave added + 1 leave taken
        assertThat(rows.get(1)).contains("Monthly Leave", "Monthly leave added", "13");
        assertThat(rows.get(2)).contains("Monthly Leave", "Leave taken", "-1", "Casual Leave approved");
    }

    @Test
    void employeeExportHasRoleAndLead() throws Exception {
        List<List<String>> rows = read(mockMvc.perform(get("/api/reports/employees/export").with(as(hrAccount)))
                .andReturn().getResponse());
        assertThat(rows).hasSize(5);
        List<String> header = rows.get(0);
        List<String> mia = rows.stream().filter(r -> r.contains("Mia")).findFirst().orElseThrow();
        assertThat(mia.get(header.indexOf("Role"))).isEqualTo("Employee");
        assertThat(mia.get(header.indexOf("Reporting lead"))).isEqualTo("Lee Test");
        assertThat(mia.get(header.indexOf("Status"))).isEqualTo("Active");
        assertThat(mia.get(header.indexOf("Salary"))).isEqualTo("50000");
    }

    @Test
    void exportsAreHrOnly() throws Exception {
        for (String url : new String[]{"/api/reports/leave-balance/export", "/api/reports/negative-balances/export",
                "/api/reports/leave-requests/export", "/api/reports/employees/export",
                "/api/reports/transactions/export?employeeId=" + member.getEmployeeId()}) {
            mockMvc.perform(get(url).with(as(leadAccount))).andExpect(status().isForbidden());
            mockMvc.perform(get(url).with(as(memberAccount))).andExpect(status().isForbidden());
            mockMvc.perform(get(url)).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void badExportRequestsAreRejected() throws Exception {
        mockMvc.perform(get("/api/reports/transactions/export?employeeId=99999").with(as(hrAccount)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/reports/leave-requests/export?from=2026-05-01&to=2026-04-01").with(as(hrAccount)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void plainLinkDownloadWorksWithTheLoginCookie() throws Exception {
        String json = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"hr@test.com\",\"password\":\"Secret123!\"}"))
                .andReturn().getResponse().getContentAsString();
        String token = json.replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
        mockMvc.perform(get("/api/reports/employees/export").cookie(new Cookie("LMS_TOKEN", token)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", XLSX));
    }

    // --- Helpers ---

    // Every row of the first sheet as text (numbers without trailing ".0")
    private static List<List<String>> read(MockHttpServletResponse response) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertThat(sheet.getPaneInformation().isFreezePane()).isTrue();
            List<List<String>> rows = new ArrayList<>();
            for (Row row : sheet) {
                List<String> cells = new ArrayList<>();
                for (int c = 0; c < row.getLastCellNum(); c++) {
                    var cell = row.getCell(c);
                    if (cell == null) {
                        cells.add("");
                    } else if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC) {
                        double d = cell.getNumericCellValue();
                        cells.add(d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d));
                    } else {
                        cells.add(cell.getStringCellValue());
                    }
                }
                rows.add(cells);
            }
            return rows;
        }
    }

    private void giveMonthlyDays(Employee e, String days) {
        leaveService.creditMonth(e.getEmployeeId(), YearMonth.now(), new BigDecimal(days));
    }

    private int submitLeave(UserAccount account, LeaveType type, LocalDate from, LocalDate to) throws Exception {
        String body = """
                {"leaveTypeId":%d,"startDate":"%s","endDate":"%s","reason":"Holiday"}
                """.formatted(type.getLeaveTypeId(), from, to);
        String json = mockMvc.perform(post("/api/leave-requests").with(as(account)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return Integer.parseInt(json.replaceAll(".*\"leaveRequestId\":(\\d+).*", "$1"));
    }

    private void approve(int id, UserAccount account) throws Exception {
        mockMvc.perform(post("/api/leave-requests/" + id + "/approve").with(as(account)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
    }

    private void adjust(Employee e, LeaveType type, String days) throws Exception {
        String body = """
                {"employeeId":%d,"leaveTypeId":%d,"year":%d,"days":%s,"reason":"Correction"}
                """.formatted(e.getEmployeeId(), type.getLeaveTypeId(), year, days);
        mockMvc.perform(post("/api/leave-balance/adjust").with(as(hrAccount)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    private RequestPostProcessor as(UserAccount account) {
        return user(new AppUserDetails(userAccountRepository.findById(account.getUserId()).orElseThrow()));
    }

    private Employee employee(String firstName, String email, String department, Integer leadId) {
        Employee e = new Employee();
        e.setFirstName(firstName);
        e.setLastName("Test");
        e.setEmail(email);
        e.setDepartment(department);
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
