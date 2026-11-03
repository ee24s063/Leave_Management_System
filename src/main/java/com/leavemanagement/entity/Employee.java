package com.leavemanagement.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDate;

/**
 * Employee entity class representing an employee in the leave management system
 */
@Entity
@Table(name = "employee")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Employee {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer employeeId;

    @Column(nullable = false)
    private String firstName;

    @Column(nullable = false)
    private String lastName;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(length = 20)
    private String phone;

    @Column(nullable = false)
    private String department;

    @Column(nullable = false)
    private String designation;

    @Column(nullable = false)
    private LocalDate dateOfJoining;

    @Column(nullable = false)
    private Double salary;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EmployeeStatus status;

    // employeeId of the Team Lead who approves this employee's leave
    @Column(name = "reporting_lead_id")
    private Integer reporting_LeadId;

    // HR staff only: employeeId of the Lab Head who approves this employee's leave (the Team Lead above may too)
    @Column(name = "head_approver_id")
    private Integer headApproverId;

    // Contract end date (required for new employees; older records may not have one yet)
    private LocalDate endDate;

    // ID proof: 12 digits, no spaces
    @Column(length = 12)
    private String aadhaarNumber;

    // ID proof: e.g. ABCDE1234F, upper case
    @Column(length = 10)
    private String panNumber;

    @Column(length = 100)
    private String emergencyContactName;

    @Column(length = 50)
    private String emergencyContactRelation;

    @Column(length = 20)
    private String emergencyContactPhone;

    public String getFullName() {
        return firstName + " " + lastName;
    }

    @Override
    public String toString() {
        return "Employee{" +
                "employeeId=" + employeeId +
                ", firstName='" + firstName + '\'' +
                ", lastName='" + lastName + '\'' +
                ", email='" + email + '\'' +
                ", department='" + department + '\'' +
                ", designation='" + designation + '\'' +
                ", status=" + status +
                ", reporting_LeadId=" + reporting_LeadId +
                ", headApproverId=" + headApproverId +
                '}';
    }
}
