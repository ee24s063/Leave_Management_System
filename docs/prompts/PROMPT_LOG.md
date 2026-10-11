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
