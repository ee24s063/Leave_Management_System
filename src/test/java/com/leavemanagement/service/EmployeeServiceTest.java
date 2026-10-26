package com.leavemanagement.service;

import com.leavemanagement.config.RoleNames;
import com.leavemanagement.dto.CreateEmployeeRequest;
import com.leavemanagement.dto.CreatedEmployeeResponse;
import com.leavemanagement.dto.UpdateEmployeeRequest;
import com.leavemanagement.entity.Employee;
import com.leavemanagement.entity.EmployeeStatus;
import com.leavemanagement.entity.Role;
import com.leavemanagement.entity.UserAccount;
import com.leavemanagement.repository.EmployeeRepository;
import com.leavemanagement.repository.UserAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Employee records and their login accounts
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmployeeServiceTest {
    static final int LEAD = 2, MEMBER = 3;

    @Mock EmployeeRepository employees;
    @Mock UserAccountRepository accounts;
    @Mock PasswordEncoder encoder;
    @Spy RoleNames roleNames = new RoleNames("Lab Head");
    @InjectMocks EmployeeService service;

    UserAccount leadAccount, memberAccount;
    Employee member;

    @BeforeEach
    void setUp() {
        when(encoder.encode(anyString())).thenAnswer(inv -> "hash:" + inv.getArgument(0));
        when(employees.save(any())).thenAnswer(inv -> {
            Employee e = inv.getArgument(0);
            if (e.getEmployeeId() == null) e.setEmployeeId(10);
            return e;
        });
        when(accounts.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(employees.findAllById(any())).thenReturn(List.of());
        when(accounts.findByEmployeeIdIn(any())).thenReturn(List.of());

        Employee lead = employee(LEAD, null);
        leadAccount = account(LEAD, Role.TEAM_LEAD);
        member = employee(MEMBER, LEAD);
        memberAccount = account(MEMBER, Role.EMPLOYEE);
    }

    private Employee employee(int id, Integer leadId) {
        Employee e = new Employee();
        e.setEmployeeId(id);
        e.setFirstName("E");
        e.setLastName(String.valueOf(id));
        e.setEmail("e" + id + "@test.com");
        e.setStatus(EmployeeStatus.ACTIVE);
        e.setReporting_LeadId(leadId);
        when(employees.findById(id)).thenReturn(Optional.of(e));
        return e;
    }

    private UserAccount account(int employeeId, Role role) {
        UserAccount a = new UserAccount();
        a.setUsername("e" + employeeId + "@test.com");
        a.setRole(role);
        a.setEmployeeId(employeeId);
        a.setMustChangePassword(false);
        when(accounts.findByEmployeeId(employeeId)).thenReturn(Optional.of(a));
        return a;
    }

    private CreateEmployeeRequest create(String email, Integer leadId) {
        return new CreateEmployeeRequest(" Asha ", "Rao", email, "", "IT", "Engineer",
                LocalDate.of(2025, 6, 1), 60000.0, leadId, Role.EMPLOYEE, null);
    }

    private CreateEmployeeRequest createHr(Integer leadId, Integer headId) {
        return new CreateEmployeeRequest("Hema", "Rao", "hema@test.com", "", "HR", "HR Officer",
                LocalDate.of(2025, 6, 1), 60000.0, leadId, Role.HR_ADMIN, headId);
    }

    private UpdateEmployeeRequest update(EmployeeStatus status, Integer leadId, Role role, String email) {
        return new UpdateEmployeeRequest(null, null, email, null, null, null, null, status, leadId, null, role, null);
    }

    @Test
    void newEmployeeGetsAnAccountWithATemporaryPassword() {
        CreatedEmployeeResponse created = service.createEmployee(create(" asha@test.com ", LEAD));
        assertThat(created.username()).isEqualTo("asha@test.com");
        assertThat(created.temporaryPassword()).hasSize(12);

        ArgumentCaptor<UserAccount> captor = ArgumentCaptor.forClass(UserAccount.class);
        verify(accounts).save(captor.capture());
        UserAccount account = captor.getValue();
        assertThat(account.isMustChangePassword()).isTrue();
        assertThat(account.isEnabled()).isTrue();
        assertThat(account.getPasswordHash()).isEqualTo("hash:" + created.temporaryPassword());
        assertThat(account.getRole()).isEqualTo(Role.EMPLOYEE);
        assertThat(created.employee().firstName()).isEqualTo("Asha");
        assertThat(created.employee().phone()).isNull();
    }

    @Test
    void duplicateEmailIsRefused() {
        when(employees.existsByEmailIgnoreCase("asha@test.com")).thenReturn(true);
        assertThatThrownBy(() -> service.createEmployee(create("asha@test.com", null)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("already exists");
        verify(accounts, never()).save(any());
    }

    @Test
    void reportingLeadMustBeAnActiveLeadOrHr() {
        assertThatThrownBy(() -> service.createEmployee(create("a@test.com", MEMBER)))
                .hasMessageContaining("Team Lead or HR role");
        employees.findById(LEAD).orElseThrow().setStatus(EmployeeStatus.RESIGNED);
        assertThatThrownBy(() -> service.createEmployee(create("a@test.com", LEAD)))
                .hasMessageContaining("not an active employee");
        when(employees.findById(77)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.createEmployee(create("a@test.com", 77)))
                .hasMessageContaining("not found");
    }

    @Test
    void employeeCannotBeTheirOwnLead() {
        assertThatThrownBy(() -> service.updateEmployee(LEAD, update(null, LEAD, null, null)))
                .hasMessageContaining("own reporting lead");
    }

    @Test
    void nonActiveStatusDisablesTheLogin() {
        service.updateEmployee(MEMBER, update(EmployeeStatus.RESIGNED, null, null, null));
        assertThat(member.getStatus()).isEqualTo(EmployeeStatus.RESIGNED);
        assertThat(memberAccount.isEnabled()).isFalse();

        service.updateEmployee(MEMBER, update(EmployeeStatus.ACTIVE, null, null, null));
        assertThat(memberAccount.isEnabled()).isTrue();
    }

    @Test
    void changingTheEmailChangesTheUsername() {
        service.updateEmployee(MEMBER, update(null, null, null, "new@test.com"));
        assertThat(member.getEmail()).isEqualTo("new@test.com");
        assertThat(memberAccount.getUsername()).isEqualTo("new@test.com");
    }

    @Test
    void leadWithATeamCannotBeDemoted() {
        when(employees.findTeamMemberIds(LEAD)).thenReturn(List.of(MEMBER));
        assertThatThrownBy(() -> service.updateEmployee(LEAD, update(null, null, Role.EMPLOYEE, null)))
                .hasMessageContaining("Reassign");
        when(employees.findTeamMemberIds(LEAD)).thenReturn(List.of());
        service.updateEmployee(LEAD, update(null, null, Role.EMPLOYEE, null));
        assertThat(leadAccount.getRole()).isEqualTo(Role.EMPLOYEE);
    }

    @Test
    void passwordResetForcesAChangeAndLogsOutEverywhere() {
        int before = memberAccount.getTokenVersion();
        String temporary = service.resetPassword(MEMBER);
        assertThat(temporary).hasSize(12);
        assertThat(memberAccount.isMustChangePassword()).isTrue();
        assertThat(memberAccount.getPasswordHash()).isEqualTo("hash:" + temporary);
        assertThat(memberAccount.getTokenVersion()).isEqualTo(before + 1);
    }

    @Test
    void deactivationDisablesTheLogin() {
        service.deactivateEmployee(MEMBER);
        assertThat(member.getStatus()).isEqualTo(EmployeeStatus.INACTIVE);
        assertThat(memberAccount.isEnabled()).isFalse();
    }

    @Test
    void salaryIsHiddenUnlessAskedFor() {
        member.setSalary(50000.0);
        assertThat(service.toView(member, false).salary()).isNull();
        assertThat(service.toView(member, true).salary()).isEqualTo(50000.0);
    }
}
