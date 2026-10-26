package com.leavemanagement.dto;

import com.leavemanagement.entity.EmployeeStatus;
import com.leavemanagement.entity.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/**
 * HR update of an employee; null fields are left unchanged.
 * Set clearReportingLead to true to remove the reporting lead.
 * headApproverId (the Lab Head) applies to HR staff only, who must always have one.
 */
public record UpdateEmployeeRequest(
        String firstName,
        String lastName,
        @Email String email,
        @Pattern(regexp = "^$|^\\+?[0-9 -]{7,20}$", message = "must be a valid phone number") String phone,
        String department,
        String designation,
        @Positive Double salary,
        EmployeeStatus status,
        Integer reporting_LeadId,
        Boolean clearReportingLead,
        Role role,
        Integer headApproverId) {
}
