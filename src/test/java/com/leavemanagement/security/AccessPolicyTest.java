package com.leavemanagement.security;

import com.leavemanagement.config.RoleNames;
import com.leavemanagement.entity.Employee;
import com.leavemanagement.entity.Role;
import com.leavemanagement.entity.UserAccount;
import com.leavemanagement.repository.EmployeeRepository;
import com.leavemanagement.repository.UserAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Who may see or act on which employee: HR everyone, a Team Lead their reports, an employee themselves.
 * HR staff's requests: only their Lab Head or their chosen Team Lead, never another HR user.
 */
class AccessPolicyTest {
    // Employee IDs: 1 = HR, 2 = lead, 3 = lead's report, 4 = someone else,
    // 5 = Lab Head, 6 = HR staff approved by Lab Head 5 with Team Lead 2
    private final CurrentUser currentUser = mock(CurrentUser.class);
    private final EmployeeRepository employees = mock(EmployeeRepository.class);
    private final UserAccountRepository accounts = mock(UserAccountRepository.class);
    private final AccessPolicy policy = new AccessPolicy(currentUser, employees, accounts, new RoleNames("Lab Head"));

    @BeforeEach
    void setUp() {
        when(employees.findById(3)).thenReturn(Optional.of(employee(3, 2)));
        when(employees.findById(4)).thenReturn(Optional.of(employee(4, null)));
        when(employees.findById(2)).thenReturn(Optional.of(employee(2, null)));
        Employee hrStaff = employee(6, 2);
        hrStaff.setHeadApproverId(5);
        when(employees.findById(6)).thenReturn(Optional.of(hrStaff));
        UserAccount hrAccount = new UserAccount();
        hrAccount.setRole(Role.HR_ADMIN);
        hrAccount.setEmployeeId(6);
        when(accounts.findByEmployeeId(6)).thenReturn(Optional.of(hrAccount));
    }

    static Employee employee(int id, Integer leadId) {
        Employee e = new Employee();
        e.setEmployeeId(id);
        e.setReporting_LeadId(leadId);
        return e;
    }

    static AppUserDetails user(Integer employeeId, Role role) {
        UserAccount a = new UserAccount();
        a.setUserId(employeeId == null ? 99 : employeeId + 100);
        a.setUsername("u" + employeeId);
        a.setPasswordHash("x");
        a.setRole(role);
        a.setEmployeeId(employeeId);
        return new AppUserDetails(a);
    }

    private void loggedInAs(Integer employeeId, Role role) {
        when(currentUser.get()).thenReturn(user(employeeId, role));
    }

    @Test
    void hrSeesEveryone() {
        loggedInAs(1, Role.HR_ADMIN);
        assertThat(policy.canView(3)).isTrue();
        assertThat(policy.canView(4)).isTrue();
        assertThat(policy.canView(null)).isTrue();
    }

    @Test
    void initialAdminWithoutEmployeeRecordSeesEveryone() {
        loggedInAs(null, Role.HR_ADMIN);
        assertThat(policy.canView(4)).isTrue();
    }

    @Test
    void employeeSeesOnlyThemselves() {
        loggedInAs(3, Role.EMPLOYEE);
        assertThat(policy.canView(3)).isTrue();
        assertThat(policy.canView(4)).isFalse();
        assertThat(policy.canView(2)).isFalse();
        assertThat(policy.canView(null)).isFalse();
        assertThatThrownBy(() -> policy.checkCanView(4)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void teamLeadSeesThemselvesAndTheirReports() {
        loggedInAs(2, Role.TEAM_LEAD);
        assertThat(policy.canView(2)).isTrue();
        assertThat(policy.canView(3)).isTrue();
        assertThat(policy.canView(4)).isFalse();
    }

    @Test
    void nobodyApprovesTheirOwnRequestNotEvenHr() {
        loggedInAs(1, Role.HR_ADMIN);
        assertThatThrownBy(() -> policy.checkCanApprove(1))
                .isInstanceOf(AccessDeniedException.class).hasMessageContaining("your own");
    }

    @Test
    void hrApprovesAnyoneElse() {
        loggedInAs(1, Role.HR_ADMIN);
        assertThatCode(() -> policy.checkCanApprove(4)).doesNotThrowAnyException();
    }

    @Test
    void teamLeadApprovesOnlyTheirReports() {
        loggedInAs(2, Role.TEAM_LEAD);
        assertThatCode(() -> policy.checkCanApprove(3)).doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.checkCanApprove(4)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void employeeApprovesNobody() {
        loggedInAs(4, Role.EMPLOYEE);
        assertThatThrownBy(() -> policy.checkCanApprove(3)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void otherHrCannotApproveAnHrColleague() {
        loggedInAs(1, Role.HR_ADMIN);
        assertThatThrownBy(() -> policy.checkCanApprove(6))
                .isInstanceOf(AccessDeniedException.class).hasMessageContaining("Lab Head");
    }

    @Test
    void labHeadOrChosenTeamLeadApprovesHrStaff() {
        loggedInAs(5, Role.LAB_HEAD);
        assertThatCode(() -> policy.checkCanApprove(6)).doesNotThrowAnyException();
        loggedInAs(2, Role.TEAM_LEAD);
        assertThatCode(() -> policy.checkCanApprove(6)).doesNotThrowAnyException();
    }

    @Test
    void labHeadApprovesOnlyTheHrStaffTheyHead() {
        loggedInAs(5, Role.LAB_HEAD);
        assertThatThrownBy(() -> policy.checkCanApprove(3)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> policy.checkCanApprove(4)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void labHeadSeesThemselvesAndTheHrStaffTheyHead() {
        loggedInAs(5, Role.LAB_HEAD);
        assertThat(policy.canView(5)).isTrue();
        assertThat(policy.canView(6)).isTrue();
        assertThat(policy.canView(3)).isFalse();
    }
}
