package com.leavemanagement.service;

import com.leavemanagement.dto.CompOffSubmitRequest;
import com.leavemanagement.entity.*;
import com.leavemanagement.repository.CompOffRequestRepository;
import com.leavemanagement.repository.EmployeeRepository;
import com.leavemanagement.repository.LeaveApprovalRepository;
import com.leavemanagement.security.AppUserDetails;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Comp-off: whole days already worked, approved like leave, credited at the final HR approval
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CompOffServiceTest {
    static final int MEMBER = 3, ID = 50;
    static final LocalDate WORKED = LocalDate.now().minusDays(3);

    @Mock CompOffRequestRepository repository;
    @Mock LeaveApprovalRepository approvals;
    @Mock EmployeeRepository employees;
    @Mock ApprovalSupport approvalSupport;
    @Mock LeaveService leaveService;
    @InjectMocks CompOffService service;

    final AppUserDetails hr = LeaveRequestServiceTest.user(1, Role.HR_ADMIN);
    final AppUserDetails lead = LeaveRequestServiceTest.user(2, Role.TEAM_LEAD);

    @BeforeEach
    void setUp() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repository.findOverlapping(any(), any(), any(), any())).thenReturn(List.of());
        when(approvalSupport.initialStatus(MEMBER)).thenReturn(LeaveStatus.PENDING_TL);
    }

    private CompOffRequest stored(LeaveStatus status, LocalDate from, LocalDate to) {
        CompOffRequest r = new CompOffRequest();
        r.setCompOffRequestId(ID);
        r.setEmployeeId(MEMBER);
        r.setWorkedFrom(from);
        r.setWorkedTo(to);
        r.setDays(BigDecimal.valueOf(to.toEpochDay() - from.toEpochDay() + 1));
        r.setStatus(status);
        when(repository.findById(ID)).thenReturn(Optional.of(r));
        return r;
    }

    @Test
    void oneDayOfCompOffPerDayWorked() {
        CompOffRequest r = service.submit(MEMBER, new CompOffSubmitRequest(WORKED.minusDays(1), WORKED, " Release "));
        assertThat(r.getDays()).isEqualByComparingTo("2");
        assertThat(r.getStatus()).isEqualTo(LeaveStatus.PENDING_TL);
        assertThat(r.getReason()).isEqualTo("Release");
    }

    @Test
    void submissionRules() {
        assertThatThrownBy(() -> service.submit(MEMBER, new CompOffSubmitRequest(WORKED, WORKED.minusDays(1), "x")))
                .hasMessageContaining("cannot be after");
        assertThatThrownBy(() -> service.submit(MEMBER, new CompOffSubmitRequest(WORKED, LocalDate.now().plusDays(1), "x")))
                .hasMessageContaining("already worked");
        assertThatThrownBy(() -> service.submit(MEMBER, new CompOffSubmitRequest(WORKED.minusDays(31), WORKED, "x")))
                .hasMessageContaining("at most 31");
        assertThatThrownBy(() -> service.submit(MEMBER, new CompOffSubmitRequest(WORKED, WORKED, " ")))
                .hasMessageContaining("work done");
    }

    @Test
    void sameDaysCannotBeClaimedTwice() {
        when(repository.findOverlapping(eq(MEMBER), any(), any(), any())).thenReturn(List.of(new CompOffRequest()));
        assertThatThrownBy(() -> service.submit(MEMBER, new CompOffSubmitRequest(WORKED, WORKED, "x")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("already been requested");
    }

    @Test
    void leadApprovalDoesNotCreditYet() {
        stored(LeaveStatus.PENDING_TL, WORKED, WORKED);
        assertThat(service.approve(ID, lead, null).getStatus()).isEqualTo(LeaveStatus.PENDING_HR);
        verifyNoInteractions(leaveService);
    }

    @Test
    void hrFinalApprovalCreditsTheDays() {
        stored(LeaveStatus.PENDING_HR, WORKED.minusDays(1), WORKED);
        assertThat(service.approve(ID, hr, null).getStatus()).isEqualTo(LeaveStatus.APPROVED);
        verify(leaveService).creditCompOff(MEMBER, new BigDecimal("2"), ID, "Worked " + WORKED.minusDays(1) + " to " + WORKED);
    }

    @Test
    void labHeadApprovalCreditsHrStaffCompOff() {
        AppUserDetails head = LeaveRequestServiceTest.user(5, Role.LAB_HEAD);
        stored(LeaveStatus.PENDING_HEAD, WORKED, WORKED);
        when(approvalSupport.headStepLevel(head, MEMBER)).thenReturn(ApprovalLevel.LAB_HEAD);
        assertThat(service.approve(ID, head, null).getStatus()).isEqualTo(LeaveStatus.APPROVED);
        verify(approvalSupport).record(RequestType.COMP_OFF, ID, ApprovalLevel.LAB_HEAD, ApprovalAction.APPROVED, head, null, false);
        verify(leaveService).creditCompOff(MEMBER, BigDecimal.ONE, ID, "Worked on " + WORKED);
    }

    @Test
    void singleDayCreditSaysWhichDay() {
        stored(LeaveStatus.PENDING_HR, WORKED, WORKED);
        service.approve(ID, hr, null);
        verify(leaveService).creditCompOff(MEMBER, BigDecimal.ONE, ID, "Worked on " + WORKED);
    }

    @Test
    void onlyPendingRequestsCanBeWithdrawn() {
        stored(LeaveStatus.PENDING_HR, WORKED, WORKED);
        assertThat(service.cancel(ID).getStatus()).isEqualTo(LeaveStatus.CANCELLED);
        stored(LeaveStatus.APPROVED, WORKED, WORKED);
        assertThatThrownBy(() -> service.cancel(ID)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectionNeedsAReason() {
        stored(LeaveStatus.PENDING_TL, WORKED, WORKED);
        assertThatThrownBy(() -> service.reject(ID, lead, null)).hasMessageContaining("reason");
        assertThat(service.reject(ID, lead, "Not approved overtime").getStatus()).isEqualTo(LeaveStatus.REJECTED);
    }
}
