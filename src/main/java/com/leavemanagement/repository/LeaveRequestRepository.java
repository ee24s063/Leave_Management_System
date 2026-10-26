package com.leavemanagement.repository;

import com.leavemanagement.entity.LeaveRequest;
import com.leavemanagement.entity.LeaveStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

@Repository
public interface LeaveRequestRepository extends JpaRepository<LeaveRequest, Integer> {
    List<LeaveRequest> findByEmployeeId(Integer employeeId);

    List<LeaveRequest> findByStatusInOrderByRequestDateAsc(Collection<LeaveStatus> statuses);

    List<LeaveRequest> findByStatusInAndEmployeeIdInOrderByRequestDateAsc(Collection<LeaveStatus> statuses,
                                                                          Collection<Integer> employeeIds);

    @Query("SELECT lr FROM LeaveRequest lr JOIN Employee e ON lr.employeeId = e.employeeId " +
           "WHERE e.department = :department AND lr.status IN :statuses ORDER BY lr.requestDate DESC")
    List<LeaveRequest> findByDepartmentAndStatusIn(@Param("department") String department,
                                                   @Param("statuses") Collection<LeaveStatus> statuses);

    @Query("SELECT lr FROM LeaveRequest lr WHERE lr.startDate >= :startDate AND lr.endDate <= :endDate " +
           "AND lr.status = :status ORDER BY lr.startDate")
    List<LeaveRequest> findByDateRangeAndStatus(@Param("startDate") LocalDate startDate,
                                                @Param("endDate") LocalDate endDate,
                                                @Param("status") LeaveStatus status);

    // A date range rather than YEAR(): on SQLite, YEAR() yields text and never equals the integer year
    @Query("SELECT lr FROM LeaveRequest lr WHERE lr.employeeId = :employeeId AND lr.status = :status " +
           "AND lr.startDate >= :yearStart AND lr.startDate <= :yearEnd ORDER BY lr.startDate")
    List<LeaveRequest> findByEmployeeAndStatusBetween(@Param("employeeId") Integer employeeId,
                                                      @Param("status") LeaveStatus status,
                                                      @Param("yearStart") LocalDate yearStart,
                                                      @Param("yearEnd") LocalDate yearEnd);

    List<LeaveRequest> findByEmployeeIdOrderByRequestDateDesc(Integer employeeId);

    List<LeaveRequest> findAllByOrderByRequestDateDesc();

    List<LeaveRequest> findByEmployeeIdInOrderByRequestDateDesc(Collection<Integer> employeeIds);

    // Requests with any day between from and to; a null status means all statuses
    @Query("SELECT lr FROM LeaveRequest lr WHERE lr.startDate <= :to AND lr.endDate >= :from " +
           "AND (:status IS NULL OR lr.status = :status) ORDER BY lr.startDate, lr.leaveRequestId")
    List<LeaveRequest> findOverlapping(@Param("from") LocalDate from,
                                       @Param("to") LocalDate to,
                                       @Param("status") LeaveStatus status);

    // The employee's requests in the given statuses that cover any day between from and to
    @Query("SELECT lr FROM LeaveRequest lr WHERE lr.employeeId = :employeeId AND lr.status IN :statuses " +
           "AND lr.startDate <= :to AND lr.endDate >= :from ORDER BY lr.startDate")
    List<LeaveRequest> findOverlappingForEmployee(@Param("employeeId") Integer employeeId,
                                                  @Param("from") LocalDate from,
                                                  @Param("to") LocalDate to,
                                                  @Param("statuses") Collection<LeaveStatus> statuses);

    long countByStatus(LeaveStatus status);

    long countByStatusAndEmployeeIdIn(LeaveStatus status, Collection<Integer> employeeIds);
}
