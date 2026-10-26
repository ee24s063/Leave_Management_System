package com.leavemanagement;

import com.leavemanagement.entity.*;
import com.leavemanagement.repository.EmployeeRepository;
import com.leavemanagement.repository.UserAccountRepository;
import com.leavemanagement.security.AppUserDetails;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * When the email with the temporary password cannot be sent, nothing is saved: no employee is added, and a reset
 * keeps the old password. Not @Transactional (and with its own database), so the real commit/rollback is checked.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.datasource.url=jdbc:sqlite:target/test-mail-failure-${random.uuid}.db?date_class=TEXT")
class MailFailureTests {

    @Autowired MockMvc mockMvc;
    @Autowired EmployeeRepository employeeRepository;
    @Autowired UserAccountRepository userAccountRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired RecordingMailSender mailSender;

    @Test
    void whenTheEmailFailsNothingIsSaved() throws Exception {
        Employee hr = employee("Hana", "hr.mailfail@test.com");
        UserAccount hrAccount = account(hr, Role.HR_ADMIN);
        Employee member = employee("Mia", "mia.mailfail@test.com");
        UserAccount memberAccount = account(member, Role.EMPLOYEE);

        mailSender.failNext();
        mockMvc.perform(post("/api/employees").with(as(hrAccount)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"firstName":"Nia","lastName":"New","email":"nia.mailfail@test.com","department":"IT",
                                 "designation":"Dev","salary":1000,"dateOfJoining":"2026-01-05","role":"EMPLOYEE"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Could not send the temporary password to nia.mailfail@test.com. "
                        + "Check the email address and the mail settings, then try again."));
        assertThat(employeeRepository.findFirstByEmailIgnoreCase("nia.mailfail@test.com")).isEmpty();
        assertThat(userAccountRepository.findByUsernameIgnoreCase("nia.mailfail@test.com")).isEmpty();

        mailSender.failNext();
        mockMvc.perform(post("/api/employees/" + member.getEmployeeId() + "/reset-password").with(as(hrAccount)).with(csrf()))
                .andExpect(status().isConflict());
        UserAccount after = userAccountRepository.findById(memberAccount.getUserId()).orElseThrow();
        assertThat(passwordEncoder.matches("Secret123!", after.getPasswordHash())).isTrue();
        assertThat(after.isMustChangePassword()).isFalse();
        assertThat(after.getTokenVersion()).isEqualTo(memberAccount.getTokenVersion());
    }

    private RequestPostProcessor as(UserAccount account) {
        return user(new AppUserDetails(userAccountRepository.findById(account.getUserId()).orElseThrow()));
    }

    private Employee employee(String firstName, String email) {
        Employee e = new Employee();
        e.setFirstName(firstName);
        e.setLastName("Test");
        e.setEmail(email);
        e.setDepartment("IT");
        e.setDesignation("Engineer");
        e.setDateOfJoining(LocalDate.of(2024, 1, 15));
        e.setSalary(50000.0);
        e.setStatus(EmployeeStatus.ACTIVE);
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
