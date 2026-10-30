package com.leavemanagement.service;

import com.leavemanagement.dto.*;
import com.leavemanagement.entity.*;
import com.leavemanagement.repository.*;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * Dashboard numbers: comp-off credits vs other adjustments, the "below zero since" date, Team Lead scope
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReportServiceTest {
    static final int YEAR = 2026, MIA = 3, CASUAL = 10, COMP_OFF = 20;

    @Mock EmployeeRepository employees;
    @Mock LeaveBalanceRepository balances;
    @Mock LeaveTransactionRepository ledger;
    @Mock LeaveRequestRepository requests;
    @Mock CompOffRequestRepository compOffs;
    @InjectMocks ReportService service;

    @BeforeEach
    void setUp() {
        when(ledger.sumDaysByEmployeeAndLeaveType(TransactionType.COMP_OFF_CREDIT)).thenReturn(new ArrayList<>());
    }

    static BalanceSnapshot snapshot(int leaveTypeId, int year, String total, String used, String adjusted) {
        return new BalanceSnapshot(MIA, "Mia", "Rao", "IT", EmployeeStatus.ACTIVE, "Lee", "Shah",
                leaveTypeId, leaveTypeId == COMP_OFF ? "Compensatory Leave" : "Casual Leave", year,
                new BigDecimal(total), new BigDecimal(used), new BigDecimal(adjusted));
    }

    static LeaveTransaction entry(String days, int dayOfMonth) {
        LeaveTransaction t = new LeaveTransaction();
        t.setDays(new BigDecimal(days));
        t.setCreatedAt(LocalDateTime.of(YEAR, 3, dayOfMonth, 10, 0));
        return t;
    }

    @Test
    void compOffBalanceSplitsCreditsFromHrAdjustments() {
        when(balances.findSnapshots(YEAR, null, null, null)).thenReturn(List.of(
                snapshot(CASUAL, 0, "12", "2", "-1"),     // the monthly balance also has no year
                snapshot(COMP_OFF, 0, "0", "1", "3")));
        List<Object[]> credits = new ArrayList<>();
        credits.add(new Object[]{MIA, COMP_OFF, new BigDecimal("2")});
        when(ledger.sumDaysByEmployeeAndLeaveType(TransactionType.COMP_OFF_CREDIT)).thenReturn(credits);

        List<BalanceReportRow> rows = service.getLeaveBalances(YEAR, null, null);

        BalanceReportRow casual = rows.get(0);
        assertThat(casual.employeeName()).isEqualTo("Mia Rao");
        assertThat(casual.leadName()).isEqualTo("Lee Shah");
        assertThat(casual.compOffCredited()).isEqualByComparingTo("0");
        assertThat(casual.adjustments()).isEqualByComparingTo("-1");
        assertThat(casual.remainingDays()).isEqualByComparingTo("9");

        BalanceReportRow compOff = rows.get(1);
        assertThat(compOff.compOffCredited()).isEqualByComparingTo("2");
        assertThat(compOff.adjustments()).isEqualByComparingTo("1");   // 3 adjusted = 2 credited + 1 by HR
        assertThat(compOff.remainingDays()).isEqualByComparingTo("2"); // 0 + 3 - 1
    }

    @Test
    void creditSumsReturnedAsDoublesAreAccepted() {
        when(balances.findSnapshots(YEAR, null, null, null)).thenReturn(List.of(snapshot(COMP_OFF, 0, "0", "0", "1.5")));
        List<Object[]> credits = new ArrayList<>();
        credits.add(new Object[]{MIA, COMP_OFF, 1.5d}); // SQLite may hand back a double
        when(ledger.sumDaysByEmployeeAndLeaveType(TransactionType.COMP_OFF_CREDIT)).thenReturn(credits);
        assertThat(service.getLeaveBalances(YEAR, null, null).get(0).compOffCredited()).isEqualByComparingTo("1.5");
    }

    @Test
    void employeeWithoutLeadHasNoLeadName() {
        BalanceSnapshot s = new BalanceSnapshot(MIA, "Sam", "Roy", "Sales", EmployeeStatus.ACTIVE, null, null,
                CASUAL, "Casual Leave", YEAR, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO);
        assertThat(s.leadName()).isNull();
    }

    @Test
    void belowZeroSinceIsTheLastDayTheBalanceCrossedZero() {
        when(balances.findNegativeSnapshots(YEAR)).thenReturn(List.of(snapshot(CASUAL, YEAR, "2", "4", "0")));
        when(ledger.findByEmployeeIdAndLeaveTypeIdAndYearOrderByCreatedAtAscTransactionIdAsc(MIA, CASUAL, YEAR))
                .thenReturn(List.of(entry("2", 1), entry("-3", 5), entry("+5", 8), entry("-6", 20)));
        // running total: 2, -1 (crossed on the 5th), 4, -2 (crossed again on the 20th)
        NegativeBalanceRow row = service.getNegativeBalances(YEAR).get(0);
        assertThat(row.negativeSince()).isEqualTo(LocalDate.of(YEAR, 3, 20));
        assertThat(row.remainingDays()).isEqualByComparingTo("-2");
    }

    @Test
    void belowZeroSinceIsEmptyWhenTheLedgerNeverShowsIt() {
        when(balances.findNegativeSnapshots(YEAR)).thenReturn(List.of(snapshot(CASUAL, YEAR, "0", "1", "0")));
        when(ledger.findByEmployeeIdAndLeaveTypeIdAndYearOrderByCreatedAtAscTransactionIdAsc(anyInt(), anyInt(), anyInt()))
                .thenReturn(List.of(entry("5", 1)));
        assertThat(service.getNegativeBalances(YEAR).get(0).negativeSince()).isNull();
    }

    @Test
    void summaryAddsLeaveAndCompOffCounts() {
        when(balances.sumForYear(YEAR)).thenReturn(new BalanceTotals(new BigDecimal("24"), null, new BigDecimal("20")));
        when(employees.countByStatus(EmployeeStatus.ACTIVE)).thenReturn(7L);
        when(requests.countByStatus(LeaveStatus.PENDING_TL)).thenReturn(2L);
        when(compOffs.countByStatus(LeaveStatus.PENDING_HR)).thenReturn(1L);
        when(balances.countEmployeesWithNegativeBalance(YEAR)).thenReturn(3L);

        ReportSummary s = service.getSummary(YEAR);
        assertThat(s.activeEmployees()).isEqualTo(7);
        assertThat(s.leavePendingTeamLead()).isEqualTo(2);
        assertThat(s.compOffPendingHr()).isEqualTo(1);
        assertThat(s.employeesWithNegativeBalance()).isEqualTo(3);
        assertThat(s.totalUsed()).isEqualByComparingTo("0"); // null SUM becomes 0
        assertThat(s.totalRemaining()).isEqualByComparingTo("20");
    }

    @Test
    void teamReportCountsLeaveAndCompOffForTheTeamOnly() {
        when(employees.findTeamMemberIds(2)).thenReturn(List.of(MIA));
        when(requests.countByStatusAndEmployeeIdIn(LeaveStatus.PENDING_TL, List.of(MIA))).thenReturn(2L);
        when(compOffs.countByStatusAndEmployeeIdIn(LeaveStatus.PENDING_TL, List.of(MIA))).thenReturn(1L);
        when(balances.findSnapshots(YEAR, null, null, 2)).thenReturn(List.of(snapshot(CASUAL, YEAR, "12", "0", "0")));

        TeamReport team = service.getTeamReport(2, YEAR);
        assertThat(team.teamSize()).isEqualTo(1);
        assertThat(team.waitingForMe()).isEqualTo(3);
        assertThat(team.balances()).hasSize(1);
    }

    @Test
    void leadWithoutTeamGetsAnEmptyReportWithoutFurtherQueries() {
        when(employees.findTeamMemberIds(2)).thenReturn(List.of());
        TeamReport team = service.getTeamReport(2, YEAR);
        assertThat(team.teamSize()).isZero();
        assertThat(team.balances()).isEmpty();
        verify(balances, never()).findSnapshots(anyInt(), any(), any(), any());
    }
}
