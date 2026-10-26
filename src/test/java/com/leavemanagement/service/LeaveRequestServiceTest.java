package com.leavemanagement.service;

import com.leavemanagement.entity.*;
import com.leavemanagement.repository.LeaveApprovalRepository;
import com.leavemanagement.repository.LeaveRequestRepository;
import com.leavemanagement.repository.LeaveTypeRepository;
import com.leavemanagement.security.AppUserDetails;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Leave workflow: Team Lead first, then HR; days deducted only at the final HR approval
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LeaveRequestServiceTest {
    static final int MEMBER = 3, LEAD = 2, HR = 1, CASUAL = 10, REQUEST = 100;
    static final LocalDate START = LocalDate.now().plusDays(10);

    @Mock LeaveRequestRepository requests;
    @Mock LeaveApprovalRepository approvals;
    @Mock LeaveTypeRepository types;
    @Mock LeaveService leaveService;
    @Mock ApprovalSupport approvalSupport;
    @InjectMocks LeaveRequestService service;

    final AppUserDetails lead = user(LEAD, Role.TEAM_LEAD);
    final AppUserDetails hr = user(HR, Role.HR_ADMIN);
    final AppUserDetails member = user(MEMBER, Role.EMPLOYEE);

    static AppUserDetails user(int employeeId, Role role) {
        UserAccount a = new UserAccount();
        a.setUserId(employeeId + 100);
        a.setUsername("u" + employeeId);
        a.setPasswordHash("x");
        a.setRole(role);
        a.setEmployeeId(employeeId);
        return new AppUserDetails(a);
    }

    @BeforeEach
    void setUp() {
        when(types.existsById(CASUAL)).thenReturn(true);
        when(requests.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private LeaveRequest newRequest(LocalDate from, LocalDate to) {
        LeaveRequest r = new LeaveRequest();
        r.setEmployeeId(MEMBER);
        r.setLeaveTypeId(CASUAL);
        r.setStartDate(from);
        r.setEndDate(to);
        r.setReason("Family function");
        return r;
    }

    private LeaveRequest stored(LeaveStatus status, LocalDate from, LocalDate to) {
        LeaveRequest r = newRequest(from, to);
        r.setLeaveRequestId(REQUEST);
        r.setStatus(status);
        when(requests.findById(REQUEST)).thenReturn(Optional.of(r));
        return r;
    }

    // --- Submitting ---

    @Test
    void newRequestIsRoutedAndDated() {
        when(approvalSupport.initialStatus(MEMBER)).thenReturn(LeaveStatus.PENDING_TL);
        LeaveRequest r = service.submitLeaveRequest(newRequest(START, START.plusDays(1)));
        assertThat(r.getStatus()).isEqualTo(LeaveStatus.PENDING_TL);
        assertThat(r.getRequestDate()).isEqualTo(LocalDate.now());
    }

    @Test
    void submissionRules() {
        assertThatThrownBy(() -> service.submitLeaveRequest(newRequest(START.plusDays(2), START)))
                .hasMessage("Start date cannot be after end date");
        assertThatThrownBy(() -> service.submitLeaveRequest(newRequest(LocalDate.now().minusDays(1), LocalDate.now())))
                .hasMessage("Cannot apply leave for past dates");

        LeaveRequest halfDay = newRequest(START, START.plusDays(1));
        halfDay.setHalfDay(HalfDay.FIRST_HALF);
        assertThatThrownBy(() -> service.submitLeaveRequest(halfDay))
                .hasMessage("A half-day leave must start and end on the same day");

        LocalDate dec31 = LocalDate.of(START.getYear() + 1, 12, 31);
        assertThatThrownBy(() -> service.submitLeaveRequest(newRequest(dec31, dec31.plusDays(1))))
                .hasMessageContaining("two calendar years");

        LeaveRequest noReason = newRequest(START, START);
        noReason.setReason(" ");
        assertThatThrownBy(() -> service.submitLeaveRequest(noReason)).hasMessageContaining("Reason");

        LeaveRequest unknownType = newRequest(START, START);
        unknownType.setLeaveTypeId(999);
        assertThatThrownBy(() -> service.submitLeaveRequest(unknownType)).hasMessageContaining("leave type");

        verify(requests, never()).save(any());
    }

    @Test
    void cannotApplyTwiceForTheSameDay() {
        LeaveRequest earlier = newRequest(START, START.plusDays(2));
        earlier.setStatus(LeaveStatus.PENDING_TL);
        when(requests.findOverlappingForEmployee(eq(MEMBER), eq(START.plusDays(1)), eq(START.plusDays(1)), anyCollection()))
                .thenReturn(List.of(earlier));

        assertThatThrownBy(() -> service.submitLeaveRequest(newRequest(START.plusDays(1), START.plusDays(1))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already applied for leave")
                .hasMessageContaining("(pending)");
        verify(requests, never()).save(any());
    }

    @Test
    void onlyPendingAndApprovedRequestsBlockTheDay() {
        service.submitLeaveRequest(newRequest(START, START));
        verify(requests).findOverlappingForEmployee(eq(MEMBER), eq(START), eq(START),
                eq(EnumSet.of(LeaveStatus.PENDING_TL, LeaveStatus.PENDING_HR, LeaveStatus.PENDING_HEAD, LeaveStatus.APPROVED)));
    }

    @Test
    void morningAndAfternoonOfTheSameDayMayBothBeTaken() {
        LeaveRequest morning = newRequest(START, START);
        morning.setHalfDay(HalfDay.FIRST_HALF);
        morning.setStatus(LeaveStatus.APPROVED);
        when(requests.findOverlappingForEmployee(eq(MEMBER), eq(START), eq(START), anyCollection()))
                .thenReturn(List.of(morning));

        LeaveRequest afternoon = newRequest(START, START);
        afternoon.setHalfDay(HalfDay.SECOND_HALF);
        service.submitLeaveRequest(afternoon);
        verify(requests).save(afternoon);

        LeaveRequest morningAgain = newRequest(START, START);
        morningAgain.setHalfDay(HalfDay.FIRST_HALF);
        assertThatThrownBy(() -> service.submitLeaveRequest(morningAgain)).hasMessageContaining("(approved)");
        LeaveRequest fullDay = newRequest(START, START);
        assertThatThrownBy(() -> service.submitLeaveRequest(fullDay)).hasMessageContaining("already applied");
    }

    // --- Approving ---

    @Test
    void leadApprovalMovesTheRequestToHrWithoutDeducting() {
        stored(LeaveStatus.PENDING_TL, START, START.plusDays(1));
        LeaveRequest r = service.approveLeaveRequest(REQUEST, lead, "OK");
        assertThat(r.getStatus()).isEqualTo(LeaveStatus.PENDING_HR);
        verify(approvalSupport).record(RequestType.LEAVE, REQUEST, ApprovalLevel.TEAM_LEAD, ApprovalAction.APPROVED, lead, "OK", false);
        verify(leaveService, never()).deductLeave(any(), any(), any(), any(), any());
    }

    @Test
    void hrFinalApprovalDeductsTheDays() {
        stored(LeaveStatus.PENDING_HR, START, START.plusDays(1));
        LeaveRequest r = service.approveLeaveRequest(REQUEST, hr, null);
        assertThat(r.getStatus()).isEqualTo(LeaveStatus.APPROVED);
        verify(leaveService).deductLeave(MEMBER, CASUAL, new BigDecimal("2"), START.getYear(), REQUEST);
    }

    @Test
    void labHeadGivesTheFinalApprovalForHrStaff() {
        AppUserDetails head = user(5, Role.LAB_HEAD);
        stored(LeaveStatus.PENDING_HEAD, START, START.plusDays(1));
        when(approvalSupport.headStepLevel(head, MEMBER)).thenReturn(ApprovalLevel.LAB_HEAD);
        LeaveRequest r = service.approveLeaveRequest(REQUEST, head, "Enjoy");
        assertThat(r.getStatus()).isEqualTo(LeaveStatus.APPROVED);
        verify(approvalSupport).record(RequestType.LEAVE, REQUEST, ApprovalLevel.LAB_HEAD, ApprovalAction.APPROVED, head, "Enjoy", false);
        verify(leaveService).deductLeave(MEMBER, CASUAL, new BigDecimal("2"), START.getYear(), REQUEST);
    }

    @Test
    void chosenTeamLeadMayGiveTheFinalApprovalForHrStaffInstead() {
        stored(LeaveStatus.PENDING_HEAD, START, START);
        when(approvalSupport.headStepLevel(lead, MEMBER)).thenReturn(ApprovalLevel.TEAM_LEAD);
        assertThat(service.approveLeaveRequest(REQUEST, lead, null).getStatus()).isEqualTo(LeaveStatus.APPROVED);
        verify(approvalSupport).record(RequestType.LEAVE, REQUEST, ApprovalLevel.TEAM_LEAD, ApprovalAction.APPROVED, lead, null, false);
        verify(leaveService).deductLeave(eq(MEMBER), eq(CASUAL), any(), anyInt(), eq(REQUEST));
    }

    @Test
    void labHeadRejectionIsRecordedAtTheLabHeadStep() {
        AppUserDetails head = user(5, Role.LAB_HEAD);
        stored(LeaveStatus.PENDING_HEAD, START, START);
        when(approvalSupport.headStepLevel(head, MEMBER)).thenReturn(ApprovalLevel.LAB_HEAD);
        assertThat(service.rejectLeaveRequest(REQUEST, head, "Audit week").getStatus()).isEqualTo(LeaveStatus.REJECTED);
        verify(approvalSupport).record(RequestType.LEAVE, REQUEST, ApprovalLevel.LAB_HEAD, ApprovalAction.REJECTED, head, "Audit week", false);
        verifyNoInteractions(leaveService);
    }

    @Test
    void hrCoveringForAnAbsentLeadCompletesBothSteps() {
        stored(LeaveStatus.PENDING_TL, START, START);
        when(approvalSupport.isReportingLeadOf(hr, MEMBER)).thenReturn(false);
        LeaveRequest r = service.approveLeaveRequest(REQUEST, hr, "Fine");
        assertThat(r.getStatus()).isEqualTo(LeaveStatus.APPROVED);
        verify(approvalSupport).record(eq(RequestType.LEAVE), eq(REQUEST), eq(ApprovalLevel.TEAM_LEAD), eq(ApprovalAction.APPROVED),
                eq(hr), contains("on behalf"), eq(true));
        verify(approvalSupport).record(RequestType.LEAVE, REQUEST, ApprovalLevel.HR, ApprovalAction.APPROVED, hr, "Fine", false);
        verify(leaveService).deductLeave(any(), any(), any(), any(), any());
    }

    @Test
    void leadCannotGiveTheFinalApproval() {
        stored(LeaveStatus.PENDING_HR, START, START);
        assertThatThrownBy(() -> service.approveLeaveRequest(REQUEST, lead, null)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void finishedRequestsCannotBeApprovedAgain() {
        stored(LeaveStatus.APPROVED, START, START);
        assertThatThrownBy(() -> service.approveLeaveRequest(REQUEST, hr, null)).isInstanceOf(IllegalStateException.class);
    }

    // --- Rejecting ---

    @Test
    void rejectionNeedsAReasonAndEndsTheRequest() {
        stored(LeaveStatus.PENDING_TL, START, START);
        assertThatThrownBy(() -> service.rejectLeaveRequest(REQUEST, lead, "")).hasMessageContaining("reason");
        LeaveRequest r = service.rejectLeaveRequest(REQUEST, lead, "Release week");
        assertThat(r.getStatus()).isEqualTo(LeaveStatus.REJECTED);
        verify(approvalSupport).record(RequestType.LEAVE, REQUEST, ApprovalLevel.TEAM_LEAD, ApprovalAction.REJECTED, lead, "Release week", false);
    }

    @Test
    void onlyHrRejectsAtTheHrStep() {
        stored(LeaveStatus.PENDING_HR, START, START);
        assertThatThrownBy(() -> service.rejectLeaveRequest(REQUEST, lead, "No")).isInstanceOf(AccessDeniedException.class);
        assertThat(service.rejectLeaveRequest(REQUEST, hr, "No").getStatus()).isEqualTo(LeaveStatus.REJECTED);
    }

    // --- Cancelling ---

    @Test
    void cancellingApprovedLeaveBeforeItStartsRestoresTheDays() {
        stored(LeaveStatus.APPROVED, START, START.plusDays(2));
        LeaveRequest r = service.cancelLeaveRequest(REQUEST, member);
        assertThat(r.getStatus()).isEqualTo(LeaveStatus.CANCELLED);
        verify(leaveService).restoreLeave(MEMBER, CASUAL, new BigDecimal("3"), START.getYear(), REQUEST);
    }

    @Test
    void employeeCannotCancelLeaveThatHasStartedButHrCan() {
        LocalDate today = LocalDate.now();
        stored(LeaveStatus.APPROVED, today, today.plusDays(1));
        assertThatThrownBy(() -> service.cancelLeaveRequest(REQUEST, member)).hasMessageContaining("before it starts");
        assertThat(service.cancelLeaveRequest(REQUEST, hr).getStatus()).isEqualTo(LeaveStatus.CANCELLED);
    }

    @Test
    void pendingRequestIsWithdrawnWithoutTouchingTheBalance() {
        stored(LeaveStatus.PENDING_TL, START, START);
        assertThat(service.cancelLeaveRequest(REQUEST, member).getStatus()).isEqualTo(LeaveStatus.CANCELLED);
        verifyNoInteractions(leaveService);
    }

    @Test
    void rejectedRequestCannotBeCancelled() {
        stored(LeaveStatus.REJECTED, START, START);
        assertThatThrownBy(() -> service.cancelLeaveRequest(REQUEST, member)).isInstanceOf(IllegalStateException.class);
    }
}
