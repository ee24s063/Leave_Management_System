package com.leavemanagement.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * LeaveType entity class representing different types of leaves
 */
@Entity
@Table(name = "leave_type")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LeaveType {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer leaveTypeId;

    @Column(nullable = false, unique = true)
    private String leaveTypeName;

    // No longer used (leave comes from the shared monthly balance); kept at 0
    @Column(nullable = false)
    private Integer maxDaysPerYear = 0;

    @Column(length = 500)
    private String description;

    // Set for built-in types the application relies on (COMP_OFF, MONTHLY); these cannot be renamed or deleted
    @Column(length = 20, unique = true)
    private String systemCode;

    @Override
    public String toString() {
        return "LeaveType{" +
                "leaveTypeId=" + leaveTypeId +
                ", leaveTypeName='" + leaveTypeName + '\'' +
                ", maxDaysPerYear=" + maxDaysPerYear +
                ", description='" + description + '\'' +
                ", systemCode='" + systemCode + '\'' +
                '}';
    }
}

