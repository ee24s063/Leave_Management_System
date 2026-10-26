package com.leavemanagement.dto;

import com.leavemanagement.entity.Employee;
import com.leavemanagement.entity.EmployeeStatus;
import com.leavemanagement.entity.Role;
import java.time.LocalDate;

/**
 * Employee as returned by the API, with role, reporting lead and (HR staff) Lab Head names. Salary is only filled in for HR.
 */
public record EmployeeView(
        Integer employeeId,
        String firstName,
        String lastName,
        String fullName,
        String email,
        String phone,
        String department,
        String designation,
        LocalDate dateOfJoining,
        Double salary,
        EmployeeStatus status,
        Integer reporting_LeadId,
        String reportingLeadName,
        Role role,
        Integer headApproverId,
        String headApproverName) {

    public static EmployeeView of(Employee e, Role role, String reportingLeadName, String headApproverName,
                                  boolean includeSalary) {
        return new EmployeeView(
                e.getEmployeeId(),
                e.getFirstName(),
                e.getLastName(),
                e.getFullName(),
                e.getEmail(),
                e.getPhone(),
                e.getDepartment(),
                e.getDesignation(),
                e.getDateOfJoining(),
                includeSalary ? e.getSalary() : null,
                e.getStatus(),
                e.getReporting_LeadId(),
                reportingLeadName,
                role,
                e.getHeadApproverId(),
                headApproverName);
    }
}
