package com.leavemanagement.controller;

import com.leavemanagement.entity.LeaveType;
import com.leavemanagement.repository.LeaveTypeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Optional;

/**
 * REST Controller for Leave Type management endpoints
 */
@RestController
@RequestMapping("/api/leave-types")
@RequiredArgsConstructor
public class LeaveTypeController {
    private final LeaveTypeRepository leaveTypeRepository;

    /**
     * Get all leave types
     */
    @GetMapping
    public ResponseEntity<List<LeaveType>> getAllLeaveTypes() {
        List<LeaveType> types = leaveTypeRepository.findAll();
        return ResponseEntity.ok(types);
    }

    /**
     * Get leave type by ID
     */
    @GetMapping("/{id}")
    public ResponseEntity<LeaveType> getLeaveTypeById(@PathVariable Integer id) {
        Optional<LeaveType> type = leaveTypeRepository.findById(id);
        return type.map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    /**
     * Get leave type by name
     */
    @GetMapping("/name/{name}")
    public ResponseEntity<LeaveType> getLeaveTypeByName(@PathVariable String name) {
        Optional<LeaveType> type = leaveTypeRepository.findByLeaveTypeName(name);
        return type.map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    /**
     * Create a new leave type
     */
    @PostMapping
    public ResponseEntity<LeaveType> createLeaveType(@RequestBody LeaveType leaveType) {
        if (leaveType.getLeaveTypeName() == null || leaveType.getLeaveTypeName().isBlank()) {
            throw new IllegalArgumentException("Leave type name cannot be empty");
        }
        leaveType.setLeaveTypeId(null);
        leaveType.setSystemCode(null);
        // Days come from the shared monthly balance, not from a yearly maximum per type
        leaveType.setMaxDaysPerYear(0);
        leaveType.setLeaveTypeName(leaveType.getLeaveTypeName().trim());
        if (leaveTypeRepository.findByLeaveTypeName(leaveType.getLeaveTypeName()).isPresent()) {
            throw new IllegalStateException("A leave type with this name already exists");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(leaveTypeRepository.save(leaveType));
    }

    /**
     * Update a leave type
     */
    @PutMapping("/{id}")
    public ResponseEntity<LeaveType> updateLeaveType(@PathVariable Integer id, @RequestBody LeaveType leaveTypeDetails) {
        Optional<LeaveType> builtIn = leaveTypeRepository.findById(id).filter(t -> t.getSystemCode() != null);
        if (builtIn.isPresent() && leaveTypeDetails.getLeaveTypeName() != null
                && !leaveTypeDetails.getLeaveTypeName().equals(builtIn.get().getLeaveTypeName())) {
            throw new IllegalArgumentException("The built-in " + builtIn.get().getLeaveTypeName() + " type cannot be renamed");
        }
        try {
            Optional<LeaveType> existingType = leaveTypeRepository.findById(id);
            if (existingType.isPresent()) {
                LeaveType leaveType = existingType.get();
                if (leaveTypeDetails.getLeaveTypeName() != null) {
                    leaveType.setLeaveTypeName(leaveTypeDetails.getLeaveTypeName());
                }
                if (leaveTypeDetails.getDescription() != null) {
                    leaveType.setDescription(leaveTypeDetails.getDescription());
                }
                LeaveType updatedType = leaveTypeRepository.save(leaveType);
                return ResponseEntity.ok(updatedType);
            }
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
    }

    /**
     * Delete a leave type
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteLeaveType(@PathVariable Integer id) {
        leaveTypeRepository.findById(id).filter(t -> t.getSystemCode() != null).ifPresent(t -> {
            throw new IllegalArgumentException("The built-in " + t.getLeaveTypeName() + " type cannot be deleted");
        });
        try {
            if (leaveTypeRepository.existsById(id)) {
                leaveTypeRepository.deleteById(id);
                return ResponseEntity.noContent().build();
            }
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
    }
}
