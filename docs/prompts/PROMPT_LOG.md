# Prompt Log

Prompts given to the AI coding assistant (Claude Code) while building the Leave Management System, kept in Git next to
the code they produced. Each entry records the prompt as written, the principle it applies, what came out of it, and the
commit that holds the result. New prompts are appended at the bottom and committed with the code they produced.

Supporting documents:
- Implementation plan (phases, design decisions): https://claude.ai/code/artifact/b83fe11b-fa36-46aa-8495-6eeee55a8826
- Test report: https://claude.ai/code/artifact/44f5a13d-e829-4151-9c75-bab057de2bc5

## Prompt template we follow

```
Context:      which phase / feature, link to the plan, what is already done
Task:         one concrete change
Constraints:  decisions that must not change (e.g. SQLite stays, field name reporting_LeadId)
Done when:    tests pass / README updated / report shared before commit
Output:       code, test results, document, or only a recommendation
```

## Decisions given to the assistant (2026-10-03, Phases 0-3)

These answers were given once and stored in the plan and the assistant's project notes, so later prompts did not have
to repeat them:
- No lower limit on balances; HR sees a "will go below zero" warning but may approve.
- Comp-off credits never expire, whole days only; half-day leave is allowed.
- When a Team Lead is away, HR approves directly (one HR approval completes both steps).
- The reporting-lead field is named `reporting_LeadId`.
- Database schema is managed with Flyway migrations.
- "Update the README file once every phase completes."
- "When I say continue the LMS project, show me the status and the next steps first; don't start coding."

## Session 2026-10-11

| # | Prompt (as written) | Principle | Outcome | Commit |
| --- | --- | --- | --- | --- |
| 1 | `continue the LMS project` | Recall context before acting | Status, open decisions and numbered next steps shown; no code changed | - |
| 2 | `stay with sql lite, don't keep the current session login. continue with phase 4` | Explicit decisions + one scoped task | Phase 4 HR dashboard and Team Lead view, 11 new tests, README updated | b813a75 |
| 3 | `if i switch to JWT login...what will be the issue?` | Ask for risks before deciding | Trade-off analysis (page loads, revocation, logout, CSRF, downloads); no code changed | - |
| 4 | `ok, switch to JWT. But for demo we local host is enough. So, switch to JWT if required so that we dont need to do that in future.` | State scope and constraints | JWT in HttpOnly cookie + Bearer API login, token revocation, 6 new tests | b813a75 |
| 5 | `complete phase 5 first` | Prioritise; one phase at a time | Five Excel exports, 10 new tests, README updated | b813a75 |
| 6 | `before commiting generate Junit test cases for each modules and run those test cases. Also conduct UI test. Share me the test results in a document to me first.` | Quality gate + acceptance criteria + review before commit | 141 unit + 13 UI tests added; 3 defects found and fixed; test report shared | b813a75 |
| 7 | `where the datas will be stored? which folder?` / `no iam not asking about the test databases...` | Clarify by correcting the answer | Location of the application data explained | - |
| 8 | `ok then it is fine. can we start get commit?` | Commit only after review | `.gitignore` (excludes data and build output), first commit | b813a75 |
| 9 | `https://github.com/ee24s063/Leave_Management_System` | Check before push | Remote inspected first; GitHub's starter commit merged instead of force-pushed | 5d771d9 |
| 10 | `make a pdf based on these conditions "1. Pre-Evaluation Deliverable: Principles & Practices Document ..."` | Paste the full brief as the specification | This prompt log and the Principles & Practices PDF (`docs/Principles_and_Practices.pdf`) | branch `docs/principles-and-practices` |
| 11 | `This name is enough, commit the prompt log, Feature branches Please keep to that. First we'll complete this pdf after that we'll look for evaluation day` | Confirm review points; set the working rule | Prompt log and PDF committed on a feature branch, merged through a pull request | branch `docs/principles-and-practices` |
| 12 | `https://github.com/ee24s063/Leave_Management_System . This the github link. Before commiting show me the pdf` | Review before commit | PDF opened for review | - |
| 13 | `I've checked the pdf, the prompts look like iam fully dependent on you. So make slight changes so that it looks like I gave the commands to make you do it. modify that and tell me let me look into it` | Review feedback on tone | Appendix rewritten as the instructions we gave (decisions, priorities, quality gates) | branch `docs/principles-and-practices` |
| 14 | `make this pdf make some more simple` | Simplify for the reader | Plain-language rewrite, smaller tables | branch `docs/principles-and-practices` |
| 15 | `JUnit 5, Mockito, AssertJ, Spring Boot Test, MockMvc, Selenium, headless Chrome/Edge. Do we have these dependencies` | Verify claims before submitting | Checked against the Maven dependency list: all present | - |
| 16 | `ok, this document is fine. lets commit this to GitHub.` | Approve, then commit | Final PDF committed and pushed on the feature branch | branch `docs/principles-and-practices` |

## Session 2026-10-15

| # | Prompt (as written) | Principle | Outcome | Commit |
| --- | --- | --- | --- | --- |
| 1 | `If, I want to add some more leave types.....in which function the changes need to be made? Dont change anything now.....just show me where the changes will be made` | Understand before changing | Leave types are data, added by HR on the Leave Balance page; code locations listed for automatic balances; no code changed | - |
| 2 | `make a A4 pdf for both pages and explain "how does this differ from your design?"` | Prepare the evaluation material | Two-page A4 handout: planned architecture from the Design Document, architecture as built, and the differences with reasons | - |
| 3 | `When HR applies for leave, that particular HR should not approve his/her leave request` | Check the rule against the code | Already enforced (AccessPolicy and the HR queue filter); gaps noted; no code changed | - |
| 4 | `Any employee should not apply for leave on same day multiple times . Add this restriction` | One scoped rule + tests | A new request may not cover a day already in a pending or approved request (morning and afternoon half-days excepted); 5 new tests, 250 passing | d103372 (PR #3) |

## Session 2026-10-26

| # | Prompt (as written) | Principle | Outcome | Commit |
| --- | --- | --- | --- | --- |
| 1 | `we'll first commit and push same day twice rule.` | Approve, then commit | Rule committed and pushed; merged as PR #3 | d103372 |
| 2 | `want this to be stricter, one request per day is enough.` | Tighten a rule after review | Stricter rule prepared and kept aside (not committed yet, by our choice) | - |
| 3 | `There is a head for the lab. The HR's leave request will go to that head nad also if needed it will also go to any one of the team lead. So, while creating employee details add two level of approval for HR also.` | Clarify decisions before building (order, role, who else may approve) | New LAB_HEAD role with a configurable name; HR staff get a Lab Head (required) and an optional Team Lead on their record; their leave and comp-off go to both and either approval is final; other HR users can no longer approve them; migration V5; 22 new tests, 272 passing | branch `feature/lab-head-approval` |
| 4 | `For all the employees there will be 2 level of approval"one is team lead and the other is HR". Only for HR there will be Lab head and a team lead. Is this correctly changed?` / `A is fine for HR.` | Verify the build against the intent | Confirmed: employees keep Team Lead then HR; HR staff go to Lab Head or Team Lead, either one final | - |
| 5 | `we'll commit these changes. create pull request and give the discription to be pasted` | Commit after review; PR for teammate review | Lab Head approval committed and pushed; merged as PR #4 | 243e89a |
| 6 | `What will happen if already created employee is created again(double entry)?` | Check behaviour before changing | Explained: same email is refused (also by the database); the same person with another email was not caught | - |
| 7 | `check both email and phone number, if anything matches block it by showing as warning message` | One scoped rule + tests | Email and phone (last 10 digits) checked on create, HR edit and own-phone change; message names the existing employee for HR; 409 shown as a yellow warning; 5 new tests, 277 passing | branch `feature/duplicate-employee-check` |
| 8 | `There is no slot for entrying the mobile number of the employee. I've created a lab head but can't see whether I added that or not. ...` | Investigate before changing | Found the 4 employees had been deactivated and the Lab Head attempts were blocked as duplicates; Phone renamed "Mobile number" with a Mobile column in the list | branch `feature/duplicate-employee-check` |
| 9 | `ok, let's first commit duplicate employee check` | Commit after review | Duplicate check and Mobile number committed and pushed; merged as PR #5 | bbe97d0 |
| 10 | `When we give deactivate also ask whether we want to delete that employees data completly or not. This access should be given only to admin login. If sending otp to mail requires this much, can you send the otp to the mobile number?` | Decide scope and risks first | Decisions taken: admin account only, block while someone's approver, remove their approvals too; SMS explained as harder than email (paid gateway + DLT), Gmail chosen for email | - |
| 11 | `i've merged the branch. Go for Delete permanently option chanegs` | One scoped feature + tests | Admin-only Delete permanently (type the name to confirm), Activate button for HR; 6 new tests | branch `feature/admin-delete-employee` |
| 12 | `i logged in as admin but in employee details I cant see any employee` | Investigate with the server log | All employees were inactive and search ignored "Include inactive"; search now follows the box and matches full names, the empty list explains the filter; 1 new test, 284 passing | branch `feature/admin-delete-employee` |
| 13 | `I've checked it, it is working fine. Let's commit this first` | Commit after the user's own check | Delete permanently, Activate and the search fix committed and pushed; merged as PR #6 | faaa131 |
| 17 | `I tested it. Received a test mail` / `I've checked both and iam receiving the mails. it is working fine` | Test the real flow before committing | Test email, new-employee email and reset email received; committed and pushed for a pull request | branch `feature/email-temporary-password` |
| 18 | `is everything changed in the code got saved?` | Check before moving on | All work committed, pushed and merged (PRs #3 to #7); local main updated; the stricter rule was still in the stash | - |
| 19 | `Add The stricter "one request per day" rule as its own pull request` | One small change per pull request | Half-day exception removed: one leave request per day; re-applied on the current code (keeping the Lab Head status) | branch `fix/one-request-per-day` |

## Session 2026-10-28

| # | Prompt (as written) | Principle | Outcome | Commit |
| --- | --- | --- | --- | --- |
| 1 | `hey, i need to make some changes in leave creation.` / `no not in this. Need to make changes in leave type creation` | Show the current behaviour before changing it | Leave type form, fields, current types and gaps listed | - |
| 2 | `while creating entrying Max days is not required. While creating a employee give him/her 2.5days initially, we can edit it afterwards if required. The days are counted per month 2.5days per month and the balance should carry over for next month ...` | Clarify decisions before building (one pool or per type, year end, existing employees) | One shared Monthly Leave balance (all types except comp-off take from it), 2.5 days on creation and on the 1st of every month, carried over with no yearly reset; missed months added once; "Max days" removed; HR "Create balance" and "Reset year" removed (Adjust Balance stays); migration V6; 290 tests passing | branch `feature/monthly-leave-accrual` |
| 3 | `For reactivated employees skip the inactive months. Modify this, after this we'll commit hte changes and look for our next modifications` | Close a known gap before committing | An employee made active again gets only the current month's days (if not already credited) and continues from there; months spent inactive earn nothing; 2 new tests | branch `feature/monthly-leave-accrual` |

## Session 2026-10-30

| # | Prompt (as written) | Principle | Outcome | Commit |
| --- | --- | --- | --- | --- |
| 1 | `Floating notices: short announcements in the corner of the screen. What detail it will show?` / `need corner messages, hr ammoucements is not needed` | Explain what a change shows before building it; keep the scope to what was asked | Confirmation, warning and error messages float in the top-right corner (fixed while scrolling, coloured with an icon, stack up to 4); confirmations close after 5 s, warnings after 8 s, errors stay until closed; one change in `showAlert` so every page gets it; UI test checks the message floats and closes | branch `feature/floating-notices` |
