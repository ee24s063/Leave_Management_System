package com.leavemanagement.dto;

import com.leavemanagement.entity.Employee;
import com.leavemanagement.entity.EmployeeStatus;
import com.leavemanagement.entity.Role;
import java.time.LocalDate;

/**
 * Employee as returned by the API, with role, reporting lead and (HR staff) Lab Head names.
 * What else is filled in depends on who is looking, see {@link Access}.
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
        LocalDate endDate,
        Double salary,
        EmployeeStatus status,
        Integer reporting_LeadId,
        String reportingLeadName,
        Role role,
        Integer headApproverId,
        String headApproverName,
        String aadhaarNumber,
        String panNumber,
        String aadhaarMasked,
        String panMasked,
        String emergencyContactName,
        String emergencyContactRelation,
        String emergencyContactPhone) {

    /**
     * FULL: HR looking at one employee (the edit form), ID numbers in full.
     * PERSONAL: HR lists and the employee's own profile: salary, emergency contact, ID numbers masked.
     * LIMITED: Team Leads and Lab Heads looking at their people: no salary or personal details.
     */
    public enum Access { FULL, PERSONAL, LIMITED }

    public static EmployeeView of(Employee e, Role role, String reportingLeadName, String headApproverName,
                                  Access access) {
        boolean personal = access != Access.LIMITED;
        boolean full = access == Access.FULL;
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
                e.getEndDate(),
                personal ? e.getSalary() : null,
                e.getStatus(),
                e.getReporting_LeadId(),
                reportingLeadName,
                role,
                e.getHeadApproverId(),
                headApproverName,
                full ? e.getAadhaarNumber() : null,
                full ? e.getPanNumber() : null,
                personal ? maskAadhaar(e.getAadhaarNumber()) : null,
                personal ? maskPan(e.getPanNumber()) : null,
                personal ? e.getEmergencyContactName() : null,
                personal ? e.getEmergencyContactRelation() : null,
                personal ? e.getEmergencyContactPhone() : null);
    }

    // 234567890123 -> XXXX XXXX 0123
    public static String maskAadhaar(String aadhaar) {
        return aadhaar == null || aadhaar.length() < 4 ? null : "XXXX XXXX " + aadhaar.substring(aadhaar.length() - 4);
    }

    // ABCDE1234F -> XXXXXX234F
    public static String maskPan(String pan) {
        return pan == null || pan.length() < 4 ? null : "X".repeat(pan.length() - 4) + pan.substring(pan.length() - 4);
    }
}
