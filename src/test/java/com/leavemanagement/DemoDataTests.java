package com.leavemanagement;

import com.leavemanagement.config.DemoDataInitializer;
import com.leavemanagement.entity.Role;
import com.leavemanagement.repository.EmployeeRepository;
import com.leavemanagement.repository.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

/**
 * Demo mode (LMS_DEMO_DATA=true, used by the Docker image): a made-up team is added to an empty database
 * and every role can log in with the demo password.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:sqlite:target/test-${random.uuid}.db?date_class=TEXT",
        "app.demo-data=true"})
class DemoDataTests {

    @Autowired MockMvc mockMvc;
    @Autowired EmployeeRepository employeeRepository;
    @Autowired UserAccountRepository userAccountRepository;
    @Autowired DemoDataInitializer demoDataInitializer;

    @Test
    void demoTeamIsAddedAndEveryRoleCanLogIn() throws Exception {
        assertThat(employeeRepository.count()).isEqualTo(5);
        assertThat(userAccountRepository.findAll()).extracting(a -> a.getRole())
                .contains(Role.HR_ADMIN, Role.TEAM_LEAD, Role.LAB_HEAD, Role.EMPLOYEE);
        // The demo HR user is the HR login: no extra admin account with a random password
        assertThat(userAccountRepository.findAll()).allMatch(a -> a.getEmployeeId() != null);

        for (String user : new String[] {"hr@demo.lms", "lead@demo.lms", "labhead@demo.lms", "employee@demo.lms"}) {
            mockMvc.perform(formLogin("/login").user(user).password("Demo@1234"))
                    .andExpect(redirectedUrl("/"))
                    .andExpect(cookie().exists("LMS_TOKEN"));
        }
    }

    @Test
    void demoDataIsNotAddedTwice() throws Exception {
        demoDataInitializer.run();
        assertThat(employeeRepository.count()).isEqualTo(5);
    }
}
