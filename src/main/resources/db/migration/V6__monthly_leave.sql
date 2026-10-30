-- Monthly leave: every employee has one shared leave balance that grows by 2.5 days on the 1st of each
-- month and is never reset (unused days carry over, also into the next year). Casual, Medical and the
-- other leave types all take their days from it. Compensatory leave keeps its own balance.
-- Leave types no longer have a yearly maximum; max_days_per_year stays in the table, set to 0.

INSERT INTO leave_type (leave_type_name, max_days_per_year, description, system_code)
SELECT 'Monthly Leave', 0,
       'Shared leave balance: days are added on the 1st of every month and unused days carry over. All leave types except Compensatory Leave are taken from it.',
       'MONTHLY'
WHERE NOT EXISTS (SELECT 1 FROM leave_type WHERE system_code = 'MONTHLY');

UPDATE leave_type SET max_days_per_year = 0;

-- Fold the old yearly per-type balances into one monthly balance per employee (year 0, like comp-off)
INSERT INTO leave_balance (employee_id, leave_type_id, total_days_allowed, used_days, adjusted_days, year)
SELECT b.employee_id, (SELECT leave_type_id FROM leave_type WHERE system_code = 'MONTHLY'),
       SUM(b.total_days_allowed), SUM(b.used_days), SUM(b.adjusted_days), 0
FROM leave_balance b
JOIN leave_type t ON t.leave_type_id = b.leave_type_id
WHERE t.system_code IS NULL
GROUP BY b.employee_id;

-- Their ledger entries now belong to the monthly balance, so the ledger still adds up to it
UPDATE leave_transaction
SET leave_type_id = (SELECT leave_type_id FROM leave_type WHERE system_code = 'MONTHLY'), year = 0
WHERE leave_type_id IN (SELECT leave_type_id FROM leave_type WHERE system_code IS NULL);

DELETE FROM leave_balance
WHERE leave_type_id IN (SELECT leave_type_id FROM leave_type WHERE system_code IS NULL);
