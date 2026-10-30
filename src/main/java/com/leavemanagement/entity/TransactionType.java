package com.leavemanagement.entity;

/**
 * Kind of balance change recorded in the ledger
 */
public enum TransactionType {
    ALLOCATION,       // balance set up by HR (+ total days); no longer used
    LEAVE_DEDUCTED,   // leave approved (- days)
    LEAVE_RESTORED,   // approved leave cancelled (+ days)
    COMP_OFF_CREDIT,  // comp-off request approved (+ days)
    ADJUSTMENT,       // HR correction (+ or - days)
    RESET,            // yearly reset of used days (+ days); no longer used
    MONTHLY_CREDIT    // monthly leave added on the 1st of a month (+ days); referenceId = yyyymm
}
