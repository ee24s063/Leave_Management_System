-- Lab Head approval for HR staff: an HR employee's leave and comp-off go to their Lab Head (head_approver_id)
-- or their chosen Team Lead (reporting_lead_id); whichever approves first gives the final approval.
-- The new role LAB_HEAD and the status PENDING_HEAD are stored as text, so they need no schema change.
ALTER TABLE employee ADD COLUMN head_approver_id INTEGER REFERENCES employee (employee_id);

-- HR staff's requests that are still pending now wait for their Lab Head / Team Lead instead of another HR user
UPDATE leave_request SET status = 'PENDING_HEAD'
WHERE status IN ('PENDING_TL', 'PENDING_HR')
  AND employee_id IN (SELECT employee_id FROM user_account WHERE role = 'HR_ADMIN' AND employee_id IS NOT NULL);

UPDATE comp_off_request SET status = 'PENDING_HEAD'
WHERE status IN ('PENDING_TL', 'PENDING_HR')
  AND employee_id IN (SELECT employee_id FROM user_account WHERE role = 'HR_ADMIN' AND employee_id IS NOT NULL);
