package com.leavemanagement.dto;

import com.leavemanagement.entity.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;

/**
 * HR request to create an employee together with their login account.
 * HR staff need a headApproverId (their Lab Head); their reporting lead, if any, must be a Team Lead.
 */
public record CreateEmployeeRequest(
        @NotBlank String firstName,
        @NotBlank String lastName,
        @NotBlank @Email String email,
        @Pattern(regexp = "^$|^\\+?[0-9 -]{7,20}$", message = "must be a valid phone number") String phone,
        @NotBlank String department,
        @NotBlank String designation,
        @NotNull LocalDate dateOfJoining,
        @NotNull @Positive Double salary,
        Integer reporting_LeadId,
        @NotNull Role role,
        Integer headApproverId) {
}
