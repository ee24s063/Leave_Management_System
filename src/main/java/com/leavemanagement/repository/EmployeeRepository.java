package com.leavemanagement.repository;

import com.leavemanagement.entity.Employee;
import com.leavemanagement.entity.EmployeeStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface EmployeeRepository extends JpaRepository<Employee, Integer> {
    Optional<Employee> findByEmail(String email);

    boolean existsByEmailIgnoreCase(String email);

    Optional<Employee> findFirstByEmailIgnoreCase(String email);

    List<Employee> findByPhoneIsNotNull();

    List<Employee> findByStatus(EmployeeStatus status);

    List<Employee> findByDepartment(String department);

    List<Employee> findByDepartmentAndStatus(String department, EmployeeStatus status);

    // First name, last name or full name ("Priya S"); inactive, resigned and relieved employees only when asked for
    @Query("SELECT e FROM Employee e WHERE (LOWER(e.firstName) LIKE LOWER(CONCAT('%', :name, '%')) " +
           "OR LOWER(e.lastName) LIKE LOWER(CONCAT('%', :name, '%')) " +
           "OR LOWER(CONCAT(e.firstName, ' ', e.lastName)) LIKE LOWER(CONCAT('%', :name, '%'))) " +
           "AND (:includeInactive = true OR e.status = com.leavemanagement.entity.EmployeeStatus.ACTIVE) " +
           "ORDER BY e.firstName, e.lastName")
    List<Employee> searchByName(@Param("name") String name, @Param("includeInactive") boolean includeInactive);

    // Explicit query: an underscore in a derived query method name is read as a property path
    @Query("SELECT e FROM Employee e WHERE e.reporting_LeadId = :leadId ORDER BY e.firstName, e.lastName")
    List<Employee> findTeamMembers(@Param("leadId") Integer leadId);

    @Query("SELECT e.employeeId FROM Employee e WHERE e.reporting_LeadId = :leadId")
    List<Integer> findTeamMemberIds(@Param("leadId") Integer leadId);

    // HR staff whose leave the given Lab Head approves
    List<Employee> findByHeadApproverIdOrderByFirstNameAscLastNameAsc(Integer headApproverId);

    @Query("SELECT e.employeeId FROM Employee e WHERE e.headApproverId = :headId")
    List<Integer> findIdsByHeadApproverId(@Param("headId") Integer headId);

    long countByStatus(EmployeeStatus status);

    @Query("SELECT DISTINCT e.department FROM Employee e ORDER BY e.department")
    List<String> findDepartments();
}
