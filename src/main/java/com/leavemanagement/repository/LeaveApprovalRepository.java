package com.leavemanagement.repository;

import com.leavemanagement.entity.LeaveApproval;
import com.leavemanagement.entity.RequestType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Collection;
import java.util.List;

@Repository
public interface LeaveApprovalRepository extends JpaRepository<LeaveApproval, Integer> {
    List<LeaveApproval> findByRequestTypeAndRequestIdOrderByActedAtAscApprovalIdAsc(RequestType requestType,
                                                                                 Integer requestId);

    List<LeaveApproval> findByRequestTypeAndRequestIdInOrderByActedAtAscApprovalIdAsc(RequestType requestType,
                                                                                   Collection<Integer> requestIds);

    // Used when an employee is deleted permanently
    long deleteByRequestTypeAndRequestIdIn(RequestType requestType, Collection<Integer> requestIds);

    long deleteByApproverEmployeeId(Integer approverEmployeeId);

    long deleteByApproverUserId(Integer approverUserId);
}
