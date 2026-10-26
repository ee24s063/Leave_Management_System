# Leave Management System

A Spring Boot web application for managing employees, leave balances and leave requests, with role-based login for Employees, Team Leads, HR and the Lab Head.

> **Status:** under active development, following a phased implementation plan.
>
> | Phase | Scope | Status |
> | --- | --- | --- |
> | 0 | Java 17, dependencies, test setup | Done |
> | 1 | User access: login, roles, access rules | Done |
> | 2 | Team Lead approval, then HR final approval | Done |
> | 3 | Half-day leave, negative balance, compensatory leave, HR balance adjustments, ledger | Done |
> | 4 | HR dashboard: balance summary, negative-balance list; Team Lead "My team" view | Done |
> | 5 | Excel export of reports | Done |
> | - | JWT login (HttpOnly cookie for pages, Bearer token for API clients) | Done |
>
> Next, from the project schedule: contract/tenure and resignation/relieving dates, monthly leave accrual, document upload.

## Features (current)

### User access
- Every page and API call requires login. Users sign in with their **email** and password.
- **Login uses JWT** (no server session). The login form puts a signed token in an HttpOnly cookie (`LMS_TOKEN`, valid 8 hours); API clients such as a future mobile app call `POST /api/auth/login` and send the token as `Authorization: Bearer <token>`.
- **Logout** cancels all of the user's tokens, in every browser and device. Changing the password, or an HR password reset, does the same.
- Four roles:
  - **Employee:** view and update their own profile (phone), view their own leave balance, balance history and leave history, apply for leave, and request comp-off.
  - **Team Lead:** everything an Employee can do, plus view their team's requests and balances, and give the **first approval** (or rejection) on their team's leave requests.
  - **HR / Admin:** manage employees, roles and reporting leads, leave types and balances (including adjustments); view all requests and give the **final approval**.
  - **Lab Head** (role code `LAB_HEAD`): everything an Employee can do, plus approve or reject the leave and comp-off of the **HR staff** they are assigned to, and see those staff members' requests and balances. The name shown to users is a setting (`app.role-names.lab-head`, or the environment variable `LMS_LAB_HEAD_NAME`), so the role can be renamed without changing code.
- Nobody can approve or reject their own leave request, and HR users cannot approve or reject an HR colleague's request.
- New accounts get a **temporary password**, **emailed** to the employee's email address and never shown on screen; it must be changed at first login. HR's "Reset password" emails a new one the same way. If the email cannot be sent, nothing changes (the employee is not added, or the old password stays) and HR sees a warning.
- HR can reset a user's password. Deactivating an employee (or setting them to Resigned or Relieved) disables their login; **Activate** enables it again.
- **Delete permanently (admin account only):** the built-in admin login (`admin@lms.local`, which has no employee record) can delete an employee and everything about them: the record, login, leave and comp-off requests, balances, balance history, and every approval they gave or received. The admin must type the employee's full name to confirm. It is refused while the person is anyone's Team Lead or Lab Head. Balance adjustments they made for other employees stay (they are part of those balances), without the link to their account. HR users can only activate and deactivate.
- HR's changes apply immediately, even to users who are already logged in: every request re-reads the account, so a disabled user is logged out on their next click, and a changed role takes effect on the next request.

### Employee management (HR)
- Create employees with name, email, phone, department/team, designation, salary, joining date, **role** and **reporting lead** (`reporting_LeadId`).
- The reporting lead must be an active employee with the Team Lead or HR role.
- **HR staff** have two approvers instead: a **Lab Head** (required) and, optionally, a **Team Lead**. The Lab Head field (`headApproverId`) appears on the form when the role is HR. The second approver must be a Team Lead, not another HR user.
- **No duplicate employees:** an employee cannot be created (or edited) with an email or phone number that another employee already has, including inactive, resigned and relieved ones. Emails are compared ignoring case; phone numbers on their last 10 digits, so `+91 98765 43210` and `9876543210` match. HR sees a warning naming the existing employee; an employee changing their own phone is only told it is taken. A phone number is only checked when it changes, so older duplicates can still be edited.
- Edit details, change status (Active, Inactive, Resigned, Relieved), change role or reporting lead.
- Search by first, last or full name (capital letters ignored); the "Include inactive" box applies to the list and to search.

### Leave management
- Employees apply for leave for themselves (leave type, dates, reason). Past dates are not allowed, and a request stays within one calendar year.
- **No applying twice for the same day:** a new request may not cover a day that is already in one of the employee's pending or approved requests (rejected and cancelled requests don't count). The only exception is a morning half-day and an afternoon half-day on the same date.
- **Half-day leave:** a single-day request can be for the first or second half of the day; it counts as 0.5 days.
- **Two-level approval:** the employee's Team Lead approves first, then HR gives the final approval.

  | Status | Meaning | Who acts next |
  | --- | --- | --- |
  | Waiting for Team Lead (`PENDING_TL`) | Submitted | The employee's reporting lead (or HR, see below) |
  | Waiting for HR (`PENDING_HR`) | Lead approved, or the lead step was skipped | HR |
  | Waiting for Lab Head / Team Lead (`PENDING_HEAD`) | Submitted by an HR employee | Their Lab Head or their chosen Team Lead (either one; final) |
  | Approved (`APPROVED`) | HR approved; days deducted | - |
  | Rejected (`REJECTED`) | Rejected by the lead or HR; no days deducted | - |
  | Cancelled (`CANCELLED`) | Withdrawn; deducted days restored | - |

  - A request goes **straight to HR** when the employee has no reporting lead, or is a Team Lead or Lab Head themselves.
  - **HR staff's requests** (leave and comp-off) go to their Lab Head **and** their chosen Team Lead with the status "Waiting for Lab Head / Team Lead" (`PENDING_HEAD`). Whichever of the two approves first gives the **final** approval (days deducted, or comp-off credited); either one can reject. Other HR users cannot act on it. An HR employee with neither approver set cannot apply until HR sets their Lab Head.
  - **Lead away:** HR can approve a request that is still waiting for the Team Lead. One HR approval then completes both steps, and the history records it as "approved by HR on behalf of the Team Lead". No delegate lead is needed.
  - A rejection at either step ends the request and needs a reason.
  - The Team Lead cannot give the final approval; HR cannot be skipped.
- Days are deducted **only at the final HR approval**. The approval screens show the balance the employee would have left.
- **Negative balance:** there is no lower limit. If an approval would take the balance below zero, HR sees a warning ("will go below zero") but can still approve. Negative balances are shown in red.
- **Approval history:** every approve and reject is recorded with step, approver, comments and time, and is shown in request details and in the employee's leave history.
- **Cancelling:** employees can withdraw a pending request at any time, and cancel approved leave before it starts. HR can cancel at any time. Deducted days are given back.
- Leave balances are tracked per employee, per leave type, per year: **remaining = allotted + adjustments/credits − used**. Values can be whole or half days (e.g. 12.5). HR creates and resets them.
- HR manages leave types (name, maximum days per year, description) on the Leave Balance page.

### Compensatory leave (comp-off)
- An employee who worked on a weekend or holiday requests comp-off on the **Comp-off** page (days worked + work done). One day of comp-off per day worked; whole days only; only for days already worked, and each day only once.
- Comp-off requests are approved like leave: Team Lead first, then HR (HR can approve directly when the lead is away). HR's final approval **credits** the days.
- Each employee has one **comp-off balance that never expires**: it does not belong to a year, so unused days simply stay available. It is listed with every year's balances as "no expiry".
- Employees take the days by applying for leave of type **Compensatory Leave** (a built-in type that cannot be renamed or deleted).
- A pending comp-off request can be withdrawn. An approved credit is corrected by HR with a balance adjustment.

### Balance adjustments and history
- **HR adjustments:** HR can add or remove days (whole or half) on any balance, including comp-off, with a required reason. Use this for corrections, leave taken before the system existed, or working-day adjustments.
- **Balance history (ledger):** every change to a balance is recorded: allotment, leave taken, leave cancelled, comp-off credited, HR adjustment, yearly reset, with days, reason, who did it and when. The entries always add up to the balance. Employees see their own history; Team Leads their team's; HR everyone's.

### Dashboard
- **HR** (`/dashboard`): cards for active employees, requests waiting for a Team Lead and waiting for HR (leave + comp-off, split underneath), and employees with a balance below zero (red when above 0); the year's total days allotted, used and remaining.
  - **Negative balances:** every balance below zero, with team, lead, remaining days and the day it went below zero (from the ledger).
  - **Balance summary:** one row per employee per leave type (plus their comp-off balance): allotted, used, comp-off credited, other adjustments, remaining. Filter by year, department and leave type; click "Remaining" to sort.
  - Each name links to that employee's balance and history on the Leave Balance page.
  - **Excel exports** (see below) for both tables, leave requests and the employee list.
- **Team Lead** ("My team"): team size, requests waiting for them, requests waiting for HR, and their reports' balances. No organisation-wide numbers.

### Excel exports (HR)
Every export is an `.xlsx` file with a bold, frozen header row, filters on each column, and proper date and day formats. Each download is written to the application log with the file name, row count and who downloaded it.

| Export | Where | Contents |
| --- | --- | --- |
| Leave balance | Dashboard, Balance summary | One row per employee per leave type (plus comp-off) for the chosen year, department and leave type; totals row at the bottom |
| Negative balances | Dashboard, Negative balances | Balances below zero, most negative first, with the date each went below zero |
| Leave requests | Dashboard, Excel exports | Requests with any day in a date range, optionally one status; both approval steps (action, by whom, when, comments) |
| Balance history | Leave Balance page | Every change to one employee's balances in a year, and to their comp-off balance |
| Employees | Employees page, Dashboard | All employees with status, role, reporting lead and salary |

### Pages
| Page | Who |
| --- | --- |
| Home, My Profile, Leave Balance (with balance history) | Everyone |
| Comp-off: request, own balance, My Requests / All tabs | Everyone |
| Comp-off: Team Approvals / HR Approvals tabs | Team Lead and Lab Head / HR |
| Leave Balance: create balance, adjust balance, leave types | HR |
| Leave Requests: My Requests and All tabs | Everyone |
| Leave Requests: Team Approvals tab (with pending count) | Team Lead, Lab Head |
| Leave Requests: HR Approvals tab (with pending count) | HR |
| Dashboard: organisation view | HR |
| Dashboard: My team view | Team Lead |
| Employees | HR only |
| Login, Change Password | Everyone |

## Technology

- **Spring Boot 3.1.5**, Java **17** or newer (tested on Java 21)
- **Spring Security**: stateless JWT login (jjwt, HMAC-SHA256) in an HttpOnly cookie or Bearer header, BCrypt passwords, CSRF protection for cookie logins
- **SQLite** database (single file), **Flyway** migrations, Spring Data JPA / Hibernate 6
- Thymeleaf templates, Bootstrap 5, plain JavaScript
- springdoc OpenAPI (Swagger UI, HR only)
- Apache POI (streaming `SXSSFWorkbook`) for the Excel exports

## Getting started

### Prerequisites
- Java 17 or newer
- Maven 3.6+ (if `mvn` is not on your PATH, the VS Code Java extension ships one, e.g.
  `%USERPROFILE%\.vscode\extensions\oracle.oracle-java-<version>\nbcode\java\maven\bin\mvn.cmd`)

### Build and run
```bash
mvn clean package
java -jar target/leave-management-system-1.0.0.jar
```
or `mvn spring-boot:run`.

The app runs at **http://localhost:8080/lms/**.

### First login
1. On first start, the app creates an HR admin account, **`admin@lms.local`**, and prints a one-time temporary password in the console log:
   ```
   WARN  c.l.config.AdminAccountInitializer - Created initial HR admin account 'admin@lms.local' with temporary password: ...
   ```
   To choose the username and password yourself, set environment variables before the first start:
   `LMS_ADMIN_USERNAME` and `LMS_ADMIN_PASSWORD`.
2. Log in and change the password.

### Login settings (JWT)
| Environment variable | Default | Meaning |
| --- | --- | --- |
| `LMS_JWT_SECRET` | random at each start | Key that signs tokens; 32+ characters. Without it, everyone must log in again after a restart (fine for a local demo) |
| `LMS_JWT_EXPIRATION_MINUTES` | 480 (8 hours) | How long a login lasts |
| `LMS_COOKIE_SECURE` | false | Set to `true` when the app is served over HTTPS |
| `LMS_LAB_HEAD_NAME` | Lab Head | The name shown for the `LAB_HEAD` role on pages, messages and Excel exports |
| `LMS_MAIL_USERNAME` | (none) | The mail account that sends temporary passwords, e.g. `hrsgbc@htic.iitm.ac.in`. **Required** to add employees or reset passwords |
| `LMS_MAIL_PASSWORD` | (none) | That account's **App password** (Google Account → Security → App passwords). Never put it in the code or in Git |
| `LMS_MAIL_FROM` | `LMS_MAIL_USERNAME` | The "From" address, if different from the login |
| `LMS_MAIL_HOST` / `LMS_MAIL_PORT` | `smtp.gmail.com` / `587` | Mail server; the defaults suit the institute's Google mail |
| `LMS_LOGIN_URL` | `http://localhost:8081/lms/login` | Login link written in the email |

   Set the mail variables once in PowerShell (then reopen the terminal / VS Code): `setx LMS_MAIL_USERNAME "hrsgbc@htic.iitm.ac.in"` and `setx LMS_MAIL_PASSWORD "<app password>"`. Logged in as `admin@lms.local`, the **Employees** page shows which address mail is sent from and has a **Send test email** button to check delivery (look in the Junk folder too).
3. On **Leave Balance**, add the leave types you use (for example Sick Leave, Casual Leave).
4. On **Employees**, create your **Team Leads first**, then the employees who report to them. Each new user receives their temporary password by email.
5. On **Leave Balance**, create each employee's balance for the year.

The admin account is not an employee, so it cannot apply for leave. Create a separate employee record with the HR role for anyone in HR who takes leave.

### Database
- The database file is `leavemanagement.db` in the folder the app is started from.
- The schema is created and upgraded by **Flyway** from `src/main/resources/db/migration`. Hibernate does not change the schema (`ddl-auto=none`).
- Upgrading is automatic. For example, a Phase 1 database gets migration V2 at the next start: pending requests become "Waiting for Team Lead", and earlier approve/reject decisions are moved into the approval history. V3 adds the built-in Compensatory Leave type and writes opening ledger entries for existing balances (allotted days and days already used). V4 adds the token version used to cancel login tokens. V5 adds the Lab Head approver (`head_approver_id`) to employees and moves HR staff's pending requests to "Waiting for Lab Head / Team Lead"; HR then sets a Lab Head on each HR employee so those requests reach an approver.
- A `leavemanagement.db` left over from versions before Flyway will not start. Move or delete it, and a new one is created.

## REST API

All endpoints are under `/lms/api` and need a login token, either the browser's `LMS_TOKEN` cookie or an `Authorization: Bearer <token>` header. With the cookie, POST, PUT and DELETE also need the CSRF token: pages read it from the `_csrf` meta tag and send it in the header named by the `_csrf_header` meta tag (`X-XSRF-TOKEN`). Bearer requests need no CSRF token. Errors are returned as JSON: `{"status": 400, "error": "...", "message": "..."}`.

### Login
| Method | Path | Description |
| --- | --- | --- |
| POST | `/auth/login` | `{"username": "email", "password": "..."}` returns `{"token", "tokenType": "Bearer", "expiresAt", "username", "role", "mustChangePassword"}`; 401 for a wrong password or disabled account |
| POST | `/auth/logout` | Cancels all of the user's tokens |

Example:
```bash
TOKEN=$(curl -s -X POST -H "Content-Type: application/json" \
  -d '{"username":"admin@lms.local","password":"..."}' http://localhost:8080/lms/api/auth/login | jq -r .token)
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/lms/api/reports/summary
```

### My account
| Method | Path | Description |
| --- | --- | --- |
| GET | `/me` | Current user: role, and employee profile if linked |
| PUT | `/me` | Update own phone: `{"phone": "..."}` |
| POST | `/me/password` | `{"currentPassword": "...", "newPassword": "..."}` (min 8 characters). Cancels older tokens and returns a new one (same shape as `/auth/login`) |

### Employees
| Method | Path | Who | Description |
| --- | --- | --- | --- |
| GET | `/employees?includeInactive=false` | Team Lead (own team), HR (all) | List employees |
| GET | `/employees/{id}` | Self, own lead, HR | Employee details (salary shown to HR only) |
| POST | `/employees` | HR | Create employee + login; the temporary password is emailed (`passwordSentTo` in the response); 409 if the email cannot be sent |
| PUT | `/employees/{id}` | HR | Update fields, `status`, `role`, `reporting_LeadId` (or `clearReportingLead: true`), `headApproverId` (HR staff only) |
| POST | `/employees/{id}/reset-password` | HR | New temporary password, emailed to the employee |
| GET | `/admin/mail-status` | Admin account only | Whether email is set up, and the sender address |
| POST | `/admin/test-email` | Admin account only | `{"to": "..."}`: sends a test email |
| DELETE | `/employees/{id}` | HR | Deactivate and disable login |
| POST | `/employees/{id}/delete-permanently` | Admin account only | `{"confirmName": "<full name>"}`; deletes the employee and all of their data (409 while they are someone's Team Lead or Lab Head) |
| GET | `/employees/search?name=` | HR | Search by name |

Example: create an employee (as HR)
```json
POST /lms/api/employees
{
  "firstName": "Esha", "lastName": "Rao", "email": "esha@example.com", "phone": "+91 98765 43210",
  "department": "IT", "designation": "Developer", "salary": 50000, "dateOfJoining": "2024-06-01",
  "role": "EMPLOYEE", "reporting_LeadId": 1
}
```
For an HR employee, send `"role": "HR_ADMIN"` and `"headApproverId"` (the employee ID of a Lab Head); `reporting_LeadId` is then the optional Team Lead.

### Leave requests
| Method | Path | Who | Description |
| --- | --- | --- | --- |
| GET | `/leave-requests` | Everyone (filtered) | Own requests; Team Lead also their team's; HR all |
| POST | `/leave-requests` | Everyone with an employee record | Apply: `{"leaveTypeId", "startDate", "endDate", "reason", "halfDay"}` (`halfDay`: `FIRST_HALF` / `SECOND_HALF`, optional) |
| GET | `/leave-requests/{id}` | Requester, their lead, HR | Request details |
| GET | `/leave-requests/employee/{employeeId}` | Self, own lead, HR | An employee's history |
| GET | `/leave-requests/status/pending` | Everyone (filtered) | Requests pending at either step |
| GET | `/leave-requests/pending/team` | Team Lead, Lab Head, HR | Requests waiting for Team Lead approval (a lead sees only their reports), then HR staff's requests this user may approve as their Lab Head or Team Lead |
| GET | `/leave-requests/pending/hr` | HR | Requests waiting for HR, then requests still waiting for a lead |
| GET | `/leave-requests/{id}/history` | Requester, their lead, HR | Approval history (step, action, approver, comments, time) |
| POST | `/leave-requests/{id}/approve` | Team Lead (own team), HR | Approve at the current step. `{"comments": "..."}` (optional) |
| POST | `/leave-requests/{id}/reject` | Team Lead (own team), HR | Reject at the current step. `{"comments": "..."}` (required) |
| POST | `/leave-requests/{id}/cancel` | Requester, HR | Withdraw a pending request, or cancel approved leave (requester: before it starts) |
| GET | `/leave-requests/statistics/{employeeId}/{year}` | Self, own lead, HR | Total approved days |

The server works out the approval step from the request's status and the caller's role, so the same `approve` call is used by Team Leads and HR. Each leave request in a response includes `status`, `numberOfDays` (0.5 for a half day), `reportingLeadName`, `balanceAfterApproval` (while pending; negative if it would go below zero) and `approvals` (the history).

### Comp-off
| Method | Path | Who | Description |
| --- | --- | --- | --- |
| POST | `/comp-off` | Everyone with an employee record | Request: `{"workedFrom", "workedTo", "reason"}` |
| GET | `/comp-off` | Everyone (filtered) | Own requests; Team Lead also their team's; HR all |
| GET | `/comp-off/{id}`, `/comp-off/employee/{employeeId}` | Requester, their lead, HR | Details / an employee's requests |
| GET | `/comp-off/pending/team` | Team Lead, Lab Head, HR | Waiting for Team Lead approval, then HR staff's requests this user may approve |
| GET | `/comp-off/pending/hr` | HR | Waiting for HR, then waiting for a lead |
| POST | `/comp-off/{id}/approve`, `/comp-off/{id}/reject` | Team Lead (own team), HR | Same rules as leave; HR's final approval credits the days |
| POST | `/comp-off/{id}/cancel` | Requester, HR | Withdraw a pending request |

### Leave balances and types
| Method | Path | Who | Description |
| --- | --- | --- | --- |
| GET | `/leave-balance/employee/{employeeId}/{year}` | Self, own lead, HR | Balances for a year, plus the comp-off balance (`year: 0`). Each has `totalDaysAllowed`, `adjustedDays`, `usedDays`, `remainingDays` |
| GET | `/leave-balance/transactions/{employeeId}/{year}` | Self, own lead, HR | Balance history (ledger) for the year and the comp-off balance |
| POST | `/leave-balance?employeeId=&leaveTypeId=&totalDays=&year=` | HR | Create a balance (`totalDays` may be e.g. 12.5; not for comp-off) |
| POST | `/leave-balance/adjust` | HR | `{"employeeId", "leaveTypeId", "year", "days", "reason"}`: `days` positive adds, negative removes |
| POST | `/leave-balance/reset-yearly?employeeId=&year=` | HR | Reset used days to 0 |
| GET | `/leave-types` | Everyone | List leave types |
| POST / PUT / DELETE | `/leave-types[/{id}]` | HR | Manage leave types |

### Reports (dashboard)
All take an optional `year` (defaults to the current year). The comp-off balance (`year: 0`) is included alongside the chosen year.

| Method | Path | Who | Description |
| --- | --- | --- | --- |
| GET | `/reports/summary?year=` | HR | Active employees, pending counts (`leavePendingTeamLead`, `leavePendingHr`, `compOffPendingTeamLead`, `compOffPendingHr`), `employeesWithNegativeBalance`, `totalAllotted` / `totalUsed` / `totalRemaining` for the year |
| GET | `/reports/leave-balance?year=&department=&leaveTypeId=` | HR | One row per employee per leave type: employee, team, lead, `totalDays`, `usedDays`, `compOffCredited`, `adjustments`, `remainingDays` |
| GET | `/reports/negative-balances?year=` | HR | Balances below zero with `negativeSince` (date) |
| GET | `/reports/departments` | HR | Department names for the filter |
| GET | `/reports/team?year=` | Team Lead | `teamSize`, `waitingForMe`, `waitingForHr` and the team's balance rows |
| GET | `/reports/leave-balance/export?year=&department=&leaveTypeId=` | HR | Excel: leave balance report with totals |
| GET | `/reports/negative-balances/export?year=` | HR | Excel: negative balances, most negative first |
| GET | `/reports/leave-requests/export?from=&to=&status=` | HR | Excel: leave requests with both approval steps. `from`/`to` are `YYYY-MM-DD` (default: the current year); `status` is optional (`PENDING_TL`, `PENDING_HR`, `PENDING_HEAD`, `APPROVED`, `REJECTED`, `CANCELLED`) |
| GET | `/reports/transactions/export?employeeId=&year=` | HR | Excel: one employee's balance history |
| GET | `/reports/employees/export` | HR | Excel: employee list |

Excel downloads are plain links: in a browser the login cookie goes with them, and API clients send the Bearer token as usual.

Swagger UI (HR only): http://localhost:8080/lms/swagger-ui.html

## Project structure
```
src/main/java/com/leavemanagement/
├── config/        SecurityConfig (URL rules), WebConfig, AdminAccountInitializer
├── security/      Logged-in user, per-record AccessPolicy, forced password change
├── controller/    REST controllers, page controller, JSON error handler
├── dto/           API request/response records
├── entity/        Employee, UserAccount, LeaveType, LeaveBalance, LeaveRequest, LeaveApproval,
│                  CompOffRequest, LeaveTransaction (ledger) and their enums
├── repository/    Spring Data JPA repositories
├── service/       Business logic
└── util/          Date, validation and password helpers
src/main/resources/
├── db/migration/  Flyway SQL migrations (V1 initial schema, V2 two-level approval, V3 comp-off/adjustments/ledger, V4 token version, V5 Lab Head approval)
├── templates/     Thymeleaf pages (+ fragments/layout.html: shared head, navbar, footer)
└── static/        CSS and JavaScript (main.js is shared by all pages)
src/test/java/     Spring Boot + MockMvc tests: access rules, approval workflow, balances and comp-off, reports, Excel exports
```

## Testing
```bash
mvn test                          # all 290 tests, about a minute
mvn test -DexcludedGroups=ui      # without the browser tests
mvn test -Dtest=UiTests           # only the browser tests
```
Three levels:

| Level | Where | Tests |
| --- | --- | --- |
| Unit: one module at a time, no database (JUnit 5, Mockito) | `src/test/java/com/leavemanagement/{util,entity,security,service}` | 163 |
| Integration: whole app through the REST API with a real database | `src/test/java/com/leavemanagement/*Tests.java` | 114 |
| UI: a real headless browser clicking through the pages as each role (Selenium) | `src/test/java/com/leavemanagement/ui/UiTests.java` | 13 |

The UI tests use Microsoft Edge, or Google Chrome when Edge's driver cannot be downloaded; with neither available they are skipped. They save a screenshot per scenario to `target/ui-screenshots`.

Integration tests use a temporary SQLite file under `target/` and a fake mail sender (`RecordingMailSender`, so no real email is sent) and cover login (form and API, token cookie, Bearer tokens, altered tokens, logout and password changes cancelling tokens), CSRF, the forced password change, each access rule (own data, own team, HR-only actions), the two-level approval workflow (routing, HR approving for an absent lead, rejections, cancelling, queues, history), Lab Head approval of HR staff (either approver is final, other HR users refused, creating HR staff, the renamed role), half-day leave, negative balances, comp-off, HR adjustments, the ledger adding up to the balance, the dashboard reports (totals, filters, comp-off vs adjustments, negative-balance dates, Team Lead scope), the Excel exports (each file is opened and its rows checked; HR only; download with the login cookie), and that every page renders for each role.

## Known limitations
- An approved comp-off credit cannot be cancelled directly; HR corrects it with a negative balance adjustment.
- Export downloads are recorded in the application log only, not in a database table.
- The "below zero since" date is blank if the ledger never shows the balance crossing zero (e.g. data changed outside the app).
- A request waits for the employee's **current** reporting lead. If HR changes the lead while a request is pending, the new lead sees it.
- Leave days are counted as calendar days, including weekends.
- `config.properties` and `QUICKSTART.md` are older files: the app does not read `config.properties`, and `QUICKSTART.md` predates login and Flyway.

## Troubleshooting
| Problem | Fix |
| --- | --- |
| Port 8080 in use | Start with `--server.port=8081` |
| `Found non-empty schema(s) ... without schema history table` | An old `leavemanagement.db` exists; move or delete it |
| Lost the admin password | If another HR employee exists, log in as them and keep using that account. Otherwise stop the app, delete `leavemanagement.db` (this deletes all data) and start again |
| Java version error | Install Java 17 or newer (`java -version`) |
| Everyone is logged out after a restart | Expected without `LMS_JWT_SECRET`; set it to a fixed 32+ character value to keep logins |
