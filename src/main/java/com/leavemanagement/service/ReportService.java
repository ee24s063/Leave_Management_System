package com.leavemanagement.service;

import com.leavemanagement.dto.*;
import com.leavemanagement.entity.EmployeeStatus;
import com.leavemanagement.entity.LeaveBalance;
import com.leavemanagement.entity.LeaveStatus;
import com.leavemanagement.entity.LeaveTransaction;
import com.leavemanagement.entity.TransactionType;
import com.leavemanagement.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * HR dashboard and Team Lead "My team" reports. Totals and filters run in the database (see LeaveBalanceRepository).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReportService {
    private final EmployeeRepository employeeRepository;
    private final LeaveBalanceRepository leaveBalanceRepository;
    private final LeaveTransactionRepository leaveTransactionRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final CompOffRequestRepository compOffRequestRepository;

    public ReportSummary getSummary(int year) {
        BalanceTotals totals = leaveBalanceRepository.sumForYear(year);
        return new ReportSummary(year,
                employeeRepository.countByStatus(EmployeeStatus.ACTIVE),
                leaveRequestRepository.countByStatus(LeaveStatus.PENDING_TL)
                        + leaveRequestRepository.countByStatus(LeaveStatus.PENDING_HEAD),
                leaveRequestRepository.countByStatus(LeaveStatus.PENDING_HR),
                compOffRequestRepository.countByStatus(LeaveStatus.PENDING_TL)
                        + compOffRequestRepository.countByStatus(LeaveStatus.PENDING_HEAD),
                compOffRequestRepository.countByStatus(LeaveStatus.PENDING_HR),
                leaveBalanceRepository.countEmployeesWithNegativeBalance(year),
                totals.allotted(), totals.used(), totals.remaining());
    }

    /**
     * One row per employee per leave type for the year, plus each employee's comp-off balance (year 0).
     * Null filters mean "all".
     */
    public List<BalanceReportRow> getLeaveBalances(int year, String department, Integer leaveTypeId) {
        return toRows(leaveBalanceRepository.findSnapshots(year, department, leaveTypeId, null));
    }

    public List<NegativeBalanceRow> getNegativeBalances(int year) {
        return leaveBalanceRepository.findNegativeSnapshots(year).stream()
                .map(s -> new NegativeBalanceRow(s.employeeId(), s.employeeName(), s.department(), s.leadName(),
                        s.leaveTypeId(), s.leaveTypeName(), s.year(), s.remainingDays(),
                        negativeSince(s.employeeId(), s.leaveTypeId(), s.year())))
                .toList();
    }

    public List<String> getDepartments() {
        return employeeRepository.findDepartments();
    }

    /**
     * The lead's direct reports only; no organisation-wide numbers
     */
    public TeamReport getTeamReport(Integer leadId, int year) {
        List<Integer> teamIds = employeeRepository.findTeamMemberIds(leadId);
        if (teamIds.isEmpty()) {
            return new TeamReport(year, 0, 0, 0, List.of());
        }
        // Includes HR staff in the team, whose requests this lead may approve directly (PENDING_HEAD)
        long waitingForMe = leaveRequestRepository.countByStatusAndEmployeeIdIn(LeaveStatus.PENDING_TL, teamIds)
                + leaveRequestRepository.countByStatusAndEmployeeIdIn(LeaveStatus.PENDING_HEAD, teamIds)
                + compOffRequestRepository.countByStatusAndEmployeeIdIn(LeaveStatus.PENDING_TL, teamIds)
                + compOffRequestRepository.countByStatusAndEmployeeIdIn(LeaveStatus.PENDING_HEAD, teamIds);
        long waitingForHr = leaveRequestRepository.countByStatusAndEmployeeIdIn(LeaveStatus.PENDING_HR, teamIds)
                + compOffRequestRepository.countByStatusAndEmployeeIdIn(LeaveStatus.PENDING_HR, teamIds);
        List<BalanceReportRow> rows = toRows(leaveBalanceRepository.findSnapshots(year, null, null, leadId));
        return new TeamReport(year, teamIds.size(), waitingForMe, waitingForHr, rows);
    }

    // Splits adjustedDays into comp-off credits (from the ledger) and other adjustments (the rest)
    private List<BalanceReportRow> toRows(List<BalanceSnapshot> snapshots) {
        Map<String, BigDecimal> credits = new HashMap<>();
        for (Object[] r : leaveTransactionRepository.sumDaysByEmployeeAndLeaveType(TransactionType.COMP_OFF_CREDIT)) {
            credits.put(r[0] + ":" + r[1], toBigDecimal(r[2]));
        }
        return snapshots.stream().map(s -> {
            BigDecimal credited = s.year() == LeaveBalance.NO_YEAR
                    ? credits.getOrDefault(s.employeeId() + ":" + s.leaveTypeId(), BigDecimal.ZERO) : BigDecimal.ZERO;
            return new BalanceReportRow(s.employeeId(), s.employeeName(), s.department(), s.employeeStatus(),
                    s.leadName(), s.leaveTypeId(), s.leaveTypeName(), s.year(), s.totalDaysAllowed(), s.usedDays(),
                    credited, s.adjustedDays().subtract(credited), s.remainingDays());
        }).toList();
    }

    /**
     * Day of the last ledger entry that took the running balance from zero or above to below zero
     */
    private LocalDate negativeSince(Integer employeeId, Integer leaveTypeId, Integer year) {
        BigDecimal running = BigDecimal.ZERO;
        LocalDate since = null;
        for (LeaveTransaction t : leaveTransactionRepository
                .findByEmployeeIdAndLeaveTypeIdAndYearOrderByCreatedAtAscTransactionIdAsc(employeeId, leaveTypeId, year)) {
            BigDecimal next = running.add(t.getDays());
            if (running.signum() >= 0 && next.signum() < 0) {
                since = t.getCreatedAt().toLocalDate();
            }
            running = next;
        }
        return since;
    }

    private static BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        return value instanceof BigDecimal b ? b : new BigDecimal(value.toString());
    }
}
