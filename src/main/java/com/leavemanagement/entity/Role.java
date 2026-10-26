package com.leavemanagement.entity;

/**
 * Access role of a user account
 */
public enum Role {
    EMPLOYEE,
    TEAM_LEAD,
    HR_ADMIN,
    LAB_HEAD   // approves HR staff's leave; the name shown to users is set by app.role-names.lab-head
}
