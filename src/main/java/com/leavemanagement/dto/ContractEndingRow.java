package com.leavemanagement.dto;

import java.time.LocalDate;

/**
 * An active employee whose contract ends soon. daysLeft is negative when the end date has already passed.
 */
public record ContractEndingRow(
        Integer employeeId,
        String employeeName,
        String department,
        String designation,
        String leadName,
        LocalDate endDate,
        long daysLeft) {
}
