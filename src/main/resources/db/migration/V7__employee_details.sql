-- Employee details collected when an employee is added: contract end date, ID proof (Aadhaar and PAN) and an
-- emergency contact. The new employee form requires them all; existing employees get them when HR edits them.
ALTER TABLE employee ADD COLUMN end_date DATE;
ALTER TABLE employee ADD COLUMN aadhaar_number VARCHAR(12);
ALTER TABLE employee ADD COLUMN pan_number VARCHAR(10);
ALTER TABLE employee ADD COLUMN emergency_contact_name VARCHAR(100);
ALTER TABLE employee ADD COLUMN emergency_contact_relation VARCHAR(50);
ALTER TABLE employee ADD COLUMN emergency_contact_phone VARCHAR(20);

-- No two employees share an Aadhaar or PAN (the service gives a clearer message first)
CREATE UNIQUE INDEX ux_employee_aadhaar ON employee (aadhaar_number) WHERE aadhaar_number IS NOT NULL;
CREATE UNIQUE INDEX ux_employee_pan ON employee (pan_number) WHERE pan_number IS NOT NULL;
