package com.leavemanagement.service;

import com.leavemanagement.config.RoleNames;
import com.leavemanagement.entity.*;
import com.leavemanagement.repository.EmployeeRepository;
import com.leavemanagement.repository.LeaveApprovalRepository;
import com.leavemanagement.repository.UserAccountRepository;
import com.leavemanagement.security.AppUserDetails;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Routing of new requests and the approval history entries
 */
class ApprovalSupportTest {
    private final EmployeeRepository employees = mock(EmployeeRepository.class);
    private final UserAccountRepository accounts = mock(UserAccountRepository.class);
    private final LeaveApprovalRepository approvals = mock(LeaveApprovalRepository.class);
    private final ApprovalSupport support = new ApprovalSupport(employees, accounts, approvals, new RoleNames("Lab Head"));

    private Employee employee(int id, Integer leadId, Role role) {
        Employee e = new Employee();
        e.setEmployeeId(id);
        e.setFirstName("Emp");
        e.setLastName(String.valueOf(id));
        e.setReporting_LeadId(leadId);
        when(employees.findById(id)).thenReturn(Optional.of(e));
        UserAccount a = new UserAccount();
        a.setRole(role);
        when(accounts.findByEmployeeId(id)).thenReturn(Optional.ofNullable(role == null ? null : a));
        return e;
    }

    @Test
    void employeeWithALeadWaitsForTheLead() {
        employee(3, 2, Role.EMPLOYEE);
        assertThat(support.initialStatus(3)).isEqualTo(LeaveStatus.PENDING_TL);
    }

    @Test
    void employeeWithoutALeadGoesStraightToHr() {
        employee(4, null, Role.EMPLOYEE);
        assertThat(support.initialStatus(4)).isEqualTo(LeaveStatus.PENDING_HR);
    }

    @Test
    void leadsAndLabHeadsGoStraightToHrEvenWithALead() {
        employee(5, 9, Role.TEAM_LEAD);
        employee(8, 9, Role.LAB_HEAD);
        assertThat(support.initialStatus(5)).isEqualTo(LeaveStatus.PENDING_HR);
        assertThat(support.initialStatus(8)).isEqualTo(LeaveStatus.PENDING_HR);
    }

    @Test
    void hrStaffWaitForTheirLabHeadOrTeamLead() {
        employee(6, null, Role.HR_ADMIN).setHeadApproverId(5);
        employee(10, 9, Role.HR_ADMIN);
        assertThat(support.initialStatus(6)).isEqualTo(LeaveStatus.PENDING_HEAD);
        assertThat(support.initialStatus(10)).isEqualTo(LeaveStatus.PENDING_HEAD);
    }

    @Test
    void hrStaffWithoutAnyApproverCannotApply() {
        employee(6, null, Role.HR_ADMIN);
        assertThatThrownBy(() -> support.initialStatus(6))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("choose your Lab Head");
    }

    @Test
    void labHeadOrTeamLeadStepIsRecordedByWhoActed() {
        employee(6, 2, Role.HR_ADMIN).setHeadApproverId(5);
        assertThat(support.headStepLevel(LeaveRequestServiceTest.user(5, Role.LAB_HEAD), 6)).isEqualTo(ApprovalLevel.LAB_HEAD);
        assertThat(support.headStepLevel(LeaveRequestServiceTest.user(2, Role.TEAM_LEAD), 6)).isEqualTo(ApprovalLevel.TEAM_LEAD);
    }

    @Test
    void employeeWithoutLoginIsTreatedAsAnEmployee() {
        employee(7, 2, null);
        assertThat(support.initialStatus(7)).isEqualTo(LeaveStatus.PENDING_TL);
    }

    @Test
    void unknownEmployeeIsRejected() {
        when(employees.findById(99)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> support.initialStatus(99)).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void reportingLeadCheck() {
        employee(3, 2, Role.EMPLOYEE);
        assertThat(support.isReportingLeadOf(LeaveRequestServiceTest.user(2, Role.TEAM_LEAD), 3)).isTrue();
        assertThat(support.isReportingLeadOf(LeaveRequestServiceTest.user(1, Role.HR_ADMIN), 3)).isFalse();
    }

    @Test
    void historyEntryRecordsWhoDidWhatWithTrimmedComments() {
        employee(2, null, Role.TEAM_LEAD);
        AppUserDetails lead = LeaveRequestServiceTest.user(2, Role.TEAM_LEAD);
        support.record(RequestType.LEAVE, 100, ApprovalLevel.TEAM_LEAD, ApprovalAction.APPROVED, lead, "  Enjoy  ", false);

        ArgumentCaptor<LeaveApproval> captor = ArgumentCaptor.forClass(LeaveApproval.class);
        verify(approvals).save(captor.capture());
        LeaveApproval a = captor.getValue();
        assertThat(a.getRequestId()).isEqualTo(100);
        assertThat(a.getApproverName()).isEqualTo("Emp 2");
        assertThat(a.getApproverEmployeeId()).isEqualTo(2);
        assertThat(a.getComments()).isEqualTo("Enjoy");
        assertThat(a.getActedAt()).isNotNull();
    }

    @Test
    void blankCommentsAreStoredAsNoneAndAdminIsNamedByUsername() {
        UserAccount admin = new UserAccount();
        admin.setUserId(1);
        admin.setUsername("admin@lms.local");
        admin.setPasswordHash("x");
        admin.setRole(Role.HR_ADMIN);
        support.record(RequestType.COMP_OFF, 5, ApprovalLevel.HR, ApprovalAction.APPROVED, new AppUserDetails(admin), " ", true);

        ArgumentCaptor<LeaveApproval> captor = ArgumentCaptor.forClass(LeaveApproval.class);
        verify(approvals).save(captor.capture());
        assertThat(captor.getValue().getComments()).isNull();
        assertThat(captor.getValue().getApproverName()).isEqualTo("admin@lms.local");
        assertThat(captor.getValue().isOnBehalf()).isTrue();
    }
}
