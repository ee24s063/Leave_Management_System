package com.leavemanagement.repository;

import com.leavemanagement.dto.BalanceSnapshot;
import com.leavemanagement.dto.BalanceTotals;
import com.leavemanagement.entity.LeaveBalance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA Repository for LeaveBalance entity
 */
@Repository
public interface LeaveBalanceRepository extends JpaRepository<LeaveBalance, Integer> {
    Optional<LeaveBalance> findByEmployeeIdAndLeaveTypeIdAndYear(Integer employeeId, Integer leaveTypeId, Integer year);

    List<LeaveBalance> findByEmployeeIdAndYear(Integer employeeId, Integer year);

    List<LeaveBalance> findByEmployeeIdAndYearInOrderByLeaveTypeId(Integer employeeId, Collection<Integer> years);

    List<LeaveBalance> findByEmployeeId(Integer employeeId);

    // --- Reports (Phase 4). Year 0 (the monthly and comp-off balances) is always included alongside the requested year. ---

    String SNAPSHOT_SELECT = "SELECT new com.leavemanagement.dto.BalanceSnapshot(e.employeeId, e.firstName, e.lastName, " +
            "e.department, e.status, lead.firstName, lead.lastName, lt.leaveTypeId, lt.leaveTypeName, b.year, " +
            "b.totalDaysAllowed, b.usedDays, b.adjustedDays) " +
            "FROM LeaveBalance b JOIN Employee e ON e.employeeId = b.employeeId " +
            "JOIN LeaveType lt ON lt.leaveTypeId = b.leaveTypeId " +
            "LEFT JOIN Employee lead ON lead.employeeId = e.reporting_LeadId " +
            "WHERE b.year IN (:year, 0) ";
    String SNAPSHOT_ORDER = "ORDER BY e.firstName, e.lastName, e.employeeId, b.year DESC, lt.leaveTypeName";
    String NEGATIVE = "b.totalDaysAllowed + b.adjustedDays - b.usedDays < 0 ";

    // Optional filters: null department / leave type / lead means "all"
    @Query(SNAPSHOT_SELECT +
           "AND (:department IS NULL OR e.department = :department) " +
           "AND (:leaveTypeId IS NULL OR b.leaveTypeId = :leaveTypeId) " +
           "AND (:leadId IS NULL OR e.reporting_LeadId = :leadId) " + SNAPSHOT_ORDER)
    List<BalanceSnapshot> findSnapshots(@Param("year") Integer year,
                                        @Param("department") String department,
                                        @Param("leaveTypeId") Integer leaveTypeId,
                                        @Param("leadId") Integer leadId);

    @Query(SNAPSHOT_SELECT + "AND " + NEGATIVE + SNAPSHOT_ORDER)
    List<BalanceSnapshot> findNegativeSnapshots(@Param("year") Integer year);

    @Query("SELECT COUNT(DISTINCT b.employeeId) FROM LeaveBalance b WHERE b.year IN (:year, 0) AND " + NEGATIVE)
    long countEmployeesWithNegativeBalance(@Param("year") Integer year);

    @Query("SELECT new com.leavemanagement.dto.BalanceTotals(SUM(b.totalDaysAllowed), SUM(b.usedDays), " +
           "SUM(b.totalDaysAllowed + b.adjustedDays - b.usedDays)) FROM LeaveBalance b WHERE b.year IN (:year, 0)")
    BalanceTotals sumForYear(@Param("year") Integer year);
}
