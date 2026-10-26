package com.leavemanagement.dto;

import java.math.BigDecimal;

/**
 * HR dashboard cards. Pending counts are split into leave and comp-off requests; the "Team Lead" counts
 * include HR staff's requests waiting for their Lab Head or Team Lead.
 * Day totals cover the balances of the given year (not the comp-off balance, which has no year).
 */
public record ReportSummary(
        Integer year,
        long activeEmployees,
        long leavePendingTeamLead,
        long leavePendingHr,
        long compOffPendingTeamLead,
        long compOffPendingHr,
        long employeesWithNegativeBalance,
        BigDecimal totalAllotted,
        BigDecimal totalUsed,
        BigDecimal totalRemaining) {
}
