package com.leavemanagement.repository;

import com.leavemanagement.entity.LeaveTransaction;
import com.leavemanagement.entity.TransactionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.Collection;
import java.util.List;

@Repository
public interface LeaveTransactionRepository extends JpaRepository<LeaveTransaction, Integer> {
    List<LeaveTransaction> findByEmployeeIdAndYearInOrderByCreatedAtAscTransactionIdAsc(Integer employeeId,
                                                                                       Collection<Integer> years);

    List<LeaveTransaction> findByEmployeeIdAndLeaveTypeIdAndYearOrderByCreatedAtAscTransactionIdAsc(Integer employeeId,
                                                                                                   Integer leaveTypeId,
                                                                                                   Integer year);

    // Total days per employee and balance for one kind of change, e.g. all comp-off credits;
    // rows are [employeeId, leaveTypeId, sum]
    @Query("SELECT t.employeeId, t.leaveTypeId, SUM(t.days) FROM LeaveTransaction t WHERE t.type = :type " +
           "GROUP BY t.employeeId, t.leaveTypeId")
    List<Object[]> sumDaysByEmployeeAndLeaveType(@Param("type") TransactionType type);

    // Latest month (yyyymm) already credited to an employee, for the monthly leave
    @Query("SELECT MAX(t.referenceId) FROM LeaveTransaction t WHERE t.employeeId = :employeeId AND t.type = :type")
    Integer findLatestReference(@Param("employeeId") Integer employeeId, @Param("type") TransactionType type);

    // Used when an employee is deleted permanently
    long deleteByEmployeeId(Integer employeeId);

    List<LeaveTransaction> findByCreatedByUserId(Integer createdByUserId);
}
