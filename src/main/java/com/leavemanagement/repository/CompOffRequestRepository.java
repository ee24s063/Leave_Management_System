package com.leavemanagement.repository;

import com.leavemanagement.entity.CompOffRequest;
import com.leavemanagement.entity.LeaveStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

@Repository
public interface CompOffRequestRepository extends JpaRepository<CompOffRequest, Integer> {
    List<CompOffRequest> findAllByOrderByRequestDateDescCompOffRequestIdDesc();

    List<CompOffRequest> findByEmployeeIdInOrderByRequestDateDescCompOffRequestIdDesc(Collection<Integer> employeeIds);

    List<CompOffRequest> findByStatusInOrderByRequestDateAscCompOffRequestIdAsc(Collection<LeaveStatus> statuses);

    List<CompOffRequest> findByStatusInAndEmployeeIdInOrderByRequestDateAscCompOffRequestIdAsc(
            Collection<LeaveStatus> statuses, Collection<Integer> employeeIds);

    // Pending or approved requests of the employee that cover any of the given worked days
    @Query("SELECT c FROM CompOffRequest c WHERE c.employeeId = :employeeId AND c.status IN :statuses " +
           "AND c.workedFrom <= :workedTo AND c.workedTo >= :workedFrom")
    List<CompOffRequest> findOverlapping(@Param("employeeId") Integer employeeId,
                                         @Param("workedFrom") LocalDate workedFrom,
                                         @Param("workedTo") LocalDate workedTo,
                                         @Param("statuses") Collection<LeaveStatus> statuses);

    List<CompOffRequest> findByEmployeeId(Integer employeeId);

    long countByStatus(LeaveStatus status);

    long countByStatusAndEmployeeIdIn(LeaveStatus status, Collection<Integer> employeeIds);
}
