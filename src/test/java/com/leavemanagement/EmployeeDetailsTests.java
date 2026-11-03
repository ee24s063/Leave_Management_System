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

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Employee details collected when an employee is added: contract end date, Aadhaar and PAN, emergency contact.
 * Who sees them, and the HR dashboard list of contracts ending soon.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "spring.datasource.url=jdbc:sqlite:target/test-${random.uuid}.db?date_class=TEXT")
class EmployeeDetailsTests {

    @Autowired MockMvc mockMvc;
    @Autowired EmployeeRepository employeeRepository;
    @Autowired UserAccountRepository userAccountRepository;
    @Autowired PasswordEncoder passwordEncoder;

    UserAccount hrAccount, leadAccount, memberAccount;
    Employee hr, lead, member;
    LocalDate today = LocalDate.now();

    @BeforeEach
    void setUp() {
        hr = employee("Hana", "hr@test.com", null, null);
        hrAccount = account(hr, Role.HR_ADMIN);
        lead = employee("Lee", "lead@test.com", null, null);
        leadAccount = account(lead, Role.TEAM_LEAD);
        member = employee("Mia", "member@test.com", lead.getEmployeeId(), today.plusYears(1));
        member.setAadhaarNumber("234567890123");
        member.setPanNumber("ABCDE1234F");
        member.setEmergencyContactName("Ravi Test");
        member.setEmergencyContactRelation("Father");
        member.setEmergencyContactPhone("9000000001");
        employeeRepository.save(member);
        memberAccount = account(member, Role.EMPLOYEE);
    }

    @Test
    void hrAddsAnEmployeeWithAllTheDetails() throws Exception {
        create(json("nia@test.com", "3456 7890 1234", "bcdea2345g", "\"2028-03-31\""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.employee.endDate").value("2028-03-31"))
                .andExpect(jsonPath("$.employee.aadhaarMasked").value("XXXX XXXX 1234"))
                .andExpect(jsonPath("$.employee.panMasked").value("XXXXXX345G"))
                .andExpect(jsonPath("$.employee.emergencyContactName").value("Asha New"));
        Employee saved = employeeRepository.findFirstByEmailIgnoreCase("nia@test.com").orElseThrow();
        assertThat(saved.getAadhaarNumber()).isEqualTo("345678901234");
        assertThat(saved.getPanNumber()).isEqualTo("BCDEA2345G");
    }

    @Test
    void everyDetailIsRequired() throws Exception {
        create(json("nia@test.com", "3456 7890 1234", "BCDEA2345G", "null")).andExpect(status().isBadRequest());
        create(json("nia@test.com", "", "BCDEA2345G", "\"2028-03-31\"")).andExpect(status().isBadRequest());
        create(json("nia@test.com", "3456 7890 1234", "", "\"2028-03-31\"")).andExpect(status().isBadRequest());
        create(json("nia@test.com", "3456 7890 1234", "BCDEA2345G", "\"2028-03-31\"")
                .replace("\"emergencyContactRelation\":\"Mother\"", "\"emergencyContactRelation\":\"\"")).andExpect(status().isBadRequest());
        create(json("nia@test.com", "1234", "BCDEA2345G", "\"2028-03-31\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Aadhaar number must be 12 digits")));
        create(json("nia@test.com", "3456 7890 1234", "BCDEA2345G", "\"2020-01-01\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("End date must be after the date of joining"));
        assertThat(employeeRepository.findFirstByEmailIgnoreCase("nia@test.com")).isEmpty();
    }

    @Test
    void aadhaarAndPanCannotBelongToTwoEmployees() throws Exception {
        create(json("nia@test.com", "2345 6789 0123", "BCDEA2345G", "\"2028-03-31\""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("Aadhaar number already exists: Mia Test (member@test.com)")));
        create(json("nia@test.com", "3456 7890 1234", "abcde1234f", "\"2028-03-31\""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("PAN already exists")));
    }

    @Test
    void idNumbersAreMaskedExceptInHrsEditViewAndLeadsSeeNoPersonalDetails() throws Exception {
        String one = "/api/employees/" + member.getEmployeeId();
        // HR, one employee (the edit form): full numbers
        mockMvc.perform(get(one).with(as(hrAccount)))
                .andExpect(jsonPath("$.aadhaarNumber").value("234567890123"))
                .andExpect(jsonPath("$.panNumber").value("ABCDE1234F"))
                .andExpect(jsonPath("$.emergencyContactPhone").value("9000000001"));
        // HR list: masked only
        mockMvc.perform(get("/api/employees").with(as(hrAccount)))
                .andExpect(jsonPath("$[?(@.email == 'member@test.com')].aadhaarMasked").value(org.hamcrest.Matchers.contains("XXXX XXXX 0123")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("234567890123"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("ABCDE1234F"))));
        // The employee: their own, masked
        mockMvc.perform(get("/api/me").with(as(memberAccount)))
                .andExpect(jsonPath("$.employee.aadhaarNumber").doesNotExist())
                .andExpect(jsonPath("$.employee.aadhaarMasked").value("XXXX XXXX 0123"))
                .andExpect(jsonPath("$.employee.panMasked").value("XXXXXX234F"))
                .andExpect(jsonPath("$.employee.emergencyContactName").value("Ravi Test"));
        mockMvc.perform(get(one).with(as(memberAccount)))
                .andExpect(jsonPath("$.aadhaarNumber").doesNotExist())
                .andExpect(jsonPath("$.aadhaarMasked").value("XXXX XXXX 0123"));
        // Their Team Lead: no ID numbers, emergency contact or salary
        mockMvc.perform(get(one).with(as(leadAccount)))
                .andExpect(jsonPath("$.fullName").value("Mia Test"))
                .andExpect(jsonPath("$.aadhaarMasked").doesNotExist())
                .andExpect(jsonPath("$.panMasked").doesNotExist())
                .andExpect(jsonPath("$.emergencyContactName").doesNotExist())
                .andExpect(jsonPath("$.salary").doesNotExist());
        mockMvc.perform(get("/api/employees").with(as(leadAccount)))
                .andExpect(jsonPath("$[0].aadhaarMasked").doesNotExist());
    }

    @Test
    void hrCompletesTheDetailsOfAnOlderRecord() throws Exception {
        mockMvc.perform(put("/api/employees/" + lead.getEmployeeId()).with(as(hrAccount)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"endDate":"2029-12-31","aadhaarNumber":"4567 8901 2345","panNumber":"cdeab3456h",
                                 "emergencyContactName":"Meena","emergencyContactRelation":"Spouse","emergencyContactPhone":"9000000002"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aadhaarNumber").value("456789012345"))
                .andExpect(jsonPath("$.panNumber").value("CDEAB3456H"))
                .andExpect(jsonPath("$.endDate").value("2029-12-31"));
        // Clearing a required detail is refused
        mockMvc.perform(put("/api/employees/" + lead.getEmployeeId()).with(as(hrAccount)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"emergencyContactName\":\" \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void dashboardListsActiveContractsEndingWithin30DaysAndThoseAlreadyEnded() throws Exception {
        employee("Soon", "soon@test.com", lead.getEmployeeId(), today.plusDays(10));
        employee("Ended", "ended@test.com", null, today.minusDays(3));
        employee("Later", "later@test.com", null, today.plusDays(31));
        Employee gone = employee("Gone", "gone@test.com", null, today.minusDays(5));
        gone.setStatus(EmployeeStatus.RELIEVED);
        employeeRepository.save(gone);

        mockMvc.perform(get("/api/reports/contracts-ending").with(as(hrAccount)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].employeeName").value("Ended Test"))
                .andExpect(jsonPath("$[0].daysLeft").value(-3))
                .andExpect(jsonPath("$[1].employeeName").value("Soon Test"))
                .andExpect(jsonPath("$[1].daysLeft").value(10))
                .andExpect(jsonPath("$[1].leadName").value("Lee Test"));
        mockMvc.perform(get("/api/reports/contracts-ending").with(as(leadAccount))).andExpect(status().isForbidden());
    }

    @Test
    void employeeExportHasTheDetailsWithFullIdNumbers() throws Exception {
        byte[] file = mockMvc.perform(get("/api/reports/employees/export").with(as(hrAccount)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(file))) {
            var sheet = workbook.getSheetAt(0);
            var header = sheet.getRow(0);
            StringBuilder all = new StringBuilder();
            for (var row : sheet) for (var cell : row) all.append(cell.toString()).append('|');
            assertThat(header.getLastCellNum()).isEqualTo((short) 18);
            assertThat(all).contains("Aadhaar number", "234567890123", "ABCDE1234F", "Ravi Test", "Father")
                    .doesNotContain("XXXX");
        }
    }

    // --- Helpers ---

    private String json(String email, String aadhaar, String pan, String endDate) {
        return """
                {"firstName":"Nia","lastName":"New","email":"%s","department":"IT","designation":"Dev",
                 "salary":1000,"dateOfJoining":"2026-01-05","role":"EMPLOYEE","endDate":%s,
                 "aadhaarNumber":"%s","panNumber":"%s",
                 "emergencyContactName":"Asha New","emergencyContactRelation":"Mother","emergencyContactPhone":"+91 90000 00009"}
                """.formatted(email, endDate, aadhaar, pan);
    }

    private ResultActions create(String body) throws Exception {
        return mockMvc.perform(post("/api/employees").with(as(hrAccount)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private RequestPostProcessor as(UserAccount account) {
        return user(new AppUserDetails(userAccountRepository.findById(account.getUserId()).orElseThrow()));
    }

    private Employee employee(String firstName, String email, Integer leadId, LocalDate endDate) {
        Employee e = new Employee();
        e.setFirstName(firstName);
        e.setLastName("Test");
        e.setEmail(email);
        e.setDepartment("IT");
        e.setDesignation("Engineer");
        e.setDateOfJoining(LocalDate.of(2024, 1, 15));
        e.setEndDate(endDate);
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
