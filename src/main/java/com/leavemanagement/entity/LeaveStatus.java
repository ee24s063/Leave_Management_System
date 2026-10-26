package com.leavemanagement.entity;

/**
 * Leave request workflow: PENDING_TL -> PENDING_HR -> APPROVED, or REJECTED / CANCELLED.
 * HR staff's requests take one step instead: PENDING_HEAD -> APPROVED.
 */
public enum LeaveStatus {
    PENDING_TL,  // waiting for the employee's Team Lead
    PENDING_HR,  // Team Lead approved (or step skipped), waiting for HR final approval
    PENDING_HEAD, // an HR employee's request, waiting for their Lab Head or chosen Team Lead (either gives final approval)
    APPROVED,    // HR approved; days deducted
    REJECTED,    // rejected by the Team Lead or HR; no days deducted
    CANCELLED;   // withdrawn; deducted days restored if it had been approved

    public boolean isPending() {
        return this == PENDING_TL || this == PENDING_HR || this == PENDING_HEAD;
    }
}
