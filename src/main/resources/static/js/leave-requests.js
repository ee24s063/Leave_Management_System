// Leave Requests JavaScript
// Two-step approval: the Team Lead approves first, then HR gives final approval.
// HR can also approve a request still waiting for the lead (when the lead is away); that completes both steps.
// HR staff's own requests go to their Lab Head or chosen Team Lead instead; either one's approval is final.

const VIEWS = {
    mine: { hint: 'Your own leave requests.' },
    team: { url: '/leave-requests/pending/team', hint: 'Requests from your team waiting for your approval. After you approve, they go to HR for final approval.' },
    hr: { url: '/leave-requests/pending/hr', hint: 'Requests waiting for HR final approval, followed by requests still waiting for a Team Lead. Approving one of those completes both steps (use this when the lead is away).' },
    all: { url: '/leave-requests', hint: 'All requests you can see.' }
};

let me = null;
let currentView = 'mine';

// Load on page load
document.addEventListener('DOMContentLoaded', async function() {
    me = await getCurrentUser();
    if (!me) return;

    if (!me.employee) {
        // The initial admin account has no employee record, so it cannot apply for leave
        document.getElementById('applySection').hidden = true;
        document.getElementById('tab-mine').hidden = true;
    } else {
        loadLeaveTypeOptions('leaveTypeId');
        document.getElementById('leaveRequestForm').addEventListener('submit', submitLeaveRequest);
        ['startDate', 'endDate'].forEach(id => document.getElementById(id).addEventListener('change', updateHalfDayOption));
    }
    document.getElementById('tab-team').hidden = !hasTeamQueue(me.role);
    document.getElementById('tab-hr').hidden = me.role !== 'HR_ADMIN';
    if (me.role === 'LAB_HEAD') {
        VIEWS.team.hint = `Requests from the HR staff you approve as ${ROLE_NAMES.LAB_HEAD}. Your approval is final.`;
    } else if (me.role === 'TEAM_LEAD') {
        VIEWS.team.hint += ` Requests from HR staff ("Waiting for ${ROLE_NAMES.LAB_HEAD} / Team Lead") are final when you approve them.`;
    }

    // Start on the queue that needs this user's attention
    const start = me.role === 'HR_ADMIN' ? 'hr' : hasTeamQueue(me.role) ? 'team' : 'mine';
    showView(me.employee || start !== 'mine' ? start : 'all');
    refreshCounts();
});

// Half days are only possible for a single-day request
function updateHalfDayOption() {
    const start = document.getElementById('startDate').value;
    const end = document.getElementById('endDate').value;
    const halfDay = document.getElementById('halfDay');
    halfDay.disabled = !(start && start === end);
    if (halfDay.disabled) halfDay.value = '';
}

function showView(view) {
    currentView = view;
    document.querySelectorAll('#viewTabs .nav-link').forEach(a => a.classList.remove('active'));
    document.querySelector(`#tab-${view} .nav-link`).classList.add('active');
    document.getElementById('viewHint').textContent = VIEWS[view].hint;
    const url = view === 'mine' ? `/leave-requests/employee/${me.employee.employeeId}` : VIEWS[view].url;
    // Ignore a late answer for a tab the user has already left, so it cannot replace the current list
    apiCall(url).then(data => data && currentView === view && displayLeaveRequests(data));
}

function reload() {
    showView(currentView);
    refreshCounts();
}

// Number of requests waiting in the user's approval queue, shown on the tab
async function refreshCounts() {
    if (hasTeamQueue(me.role)) {
        const team = await apiCall('/leave-requests/pending/team');
        document.getElementById('count-team').textContent = team && team.length ? team.length : '';
    }
    if (me.role === 'HR_ADMIN') {
        const hr = await apiCall('/leave-requests/pending/hr');
        document.getElementById('count-hr').textContent = hr && hr.length ? hr.length : '';
    }
}

function isMine(req) {
    return me && me.employee && req.employeeId === me.employee.employeeId;
}

// Whether the logged-in user can approve/reject this request at its current step
// (the server checks again: Team Leads only for their own reports)
function canAct(req) {
    if (isMine(req)) return false;
    return canApproveStatus(me.role, req.status);
}

function canCancel(req) {
    if (req.status === 'REJECTED' || req.status === 'CANCELLED') return false;
    if (me.role === 'HR_ADMIN') return true;
    if (!isMine(req)) return false;
    if (req.status === 'APPROVED') return req.startDate > new Date().toISOString().split('T')[0];
    return true;
}

// Who acts next, shown under the status
function nextStepNote(req) {
    if (req.status === 'PENDING_TL') return req.reportingLeadName ? `Lead: ${escapeHtml(req.reportingLeadName)}` : '';
    if (req.status === 'PENDING_HEAD') {
        return escapeHtml(ROLE_NAMES.LAB_HEAD) + (req.reportingLeadName ? ` or ${escapeHtml(req.reportingLeadName)}` : '');
    }
    if (req.status === 'PENDING_HR') {
        const lead = (req.approvals || []).find(a => a.level === 'TEAM_LEAD' && a.action === 'APPROVED');
        return lead ? `Lead approved: ${escapeHtml(lead.approverName)}` : 'Sent straight to HR';
    }
    return '';
}

// Display leave requests in table
function displayLeaveRequests(requests) {
    const tbody = document.getElementById('leaveRequestsList');
    tbody.innerHTML = '';

    if (requests.length === 0) {
        tbody.innerHTML = '<tr><td colspan="10" class="text-center">No leave requests here</td></tr>';
        return;
    }

    requests.forEach(req => {
        const row = document.createElement('tr');
        const balance = req.balanceAfterApproval;
        const balanceCell = balance === null || balance === undefined ? '-'
            : balance < 0 ? `<span class="text-danger fw-bold" title="Balance would go negative">${formatDays(balance)}</span>` : formatDays(balance);
        const note = nextStepNote(req);

        row.innerHTML = `
            <td>${req.leaveRequestId}</td>
            <td>${escapeHtml(req.employeeName)}${isMine(req) ? ' <span class="badge bg-light text-dark">you</span>' : ''}</td>
            <td>${escapeHtml(req.leaveTypeName)}</td>
            <td>${formatDate(req.startDate)}</td>
            <td>${formatDate(req.endDate)}</td>
            <td>${formatDays(req.numberOfDays)}${req.halfDay ? `<div class="small text-muted">${req.halfDay === 'FIRST_HALF' ? 'first half' : 'second half'}</div>` : ''}</td>
            <td>${balanceCell}</td>
            <td>${escapeHtml(req.reason)}</td>
            <td>${statusBadge(req.status)}${note ? `<div class="small text-muted">${note}</div>` : ''}</td>
            <td class="text-nowrap">
                ${canAct(req) ? `
                    <button class="btn btn-sm btn-success" onclick="approveRequest(${req.leaveRequestId}, '${req.status}', ${req.balanceAfterApproval === null || req.balanceAfterApproval === undefined ? 'null' : req.balanceAfterApproval})">Approve</button>
                    <button class="btn btn-sm btn-danger" onclick="rejectRequest(${req.leaveRequestId})">Reject</button>
                ` : ''}
                ${canCancel(req) ? `
                    <button class="btn btn-sm btn-warning" onclick="cancelRequest(${req.leaveRequestId}, '${req.status}')">Cancel</button>
                ` : ''}
                <button class="btn btn-sm btn-info" onclick="viewRequestDetails(${req.leaveRequestId})">Details</button>
            </td>
        `;
        tbody.appendChild(row);
    });
}

// Submit leave request for the logged-in employee
async function submitLeaveRequest(e) {
    e.preventDefault();

    const startDate = document.getElementById('startDate').value;
    const endDate = document.getElementById('endDate').value;

    if (endDate < startDate) {
        showAlert('End date must be on or after the start date', 'danger');
        return;
    }

    const leaveRequestData = {
        leaveTypeId: parseInt(document.getElementById('leaveTypeId').value),
        startDate: startDate,
        endDate: endDate,
        halfDay: document.getElementById('halfDay').value || null,
        reason: document.getElementById('reason').value
    };

    const result = await apiCall('/leave-requests', 'POST', leaveRequestData);
    if (result) {
        showAlert(`Leave request submitted and ${submittedTo(result)}.`, 'success');
        document.getElementById('leaveRequestForm').reset();
        updateHalfDayOption();
        if (currentView !== 'mine') showView('mine'); else reload();
    }
}

// Approve at the current step (the approver is the logged-in user)
function approveRequest(requestId, status, balanceAfter) {
    let message = 'Approval comments (optional):';
    if (status === 'PENDING_HEAD') {
        message = 'Your approval is final: the days will be deducted from the balance.\n\nApproval comments (optional):';
    } else if (me.role === 'HR_ADMIN' && status === 'PENDING_TL') {
        message = 'This request has not been approved by the Team Lead yet.\n'
            + 'Approving now completes both steps (Team Lead and HR).\n\nApproval comments (optional):';
    } else if (me.role === 'TEAM_LEAD') {
        message = 'After your approval the request goes to HR for final approval.\n\nApproval comments (optional):';
    }
    const isFinal = me.role === 'HR_ADMIN' || status === 'PENDING_HEAD';
    if (isFinal && balanceAfter !== null && balanceAfter < 0) {
        message = `WARNING: this will take the employee's balance below zero (to ${formatDays(balanceAfter)} days).\n\n` + message;
    }
    const comments = prompt(message);
    if (comments === null) return;

    apiCall(`/leave-requests/${requestId}/approve`, 'POST', { comments: comments })
        .then(data => {
            if (data) {
                showAlert(data.status === 'APPROVED'
                    ? 'Leave approved. The days have been deducted from the balance.'
                    : 'Approved and sent to HR for final approval.', 'success');
                reload();
            }
        });
}

// Reject at the current step; a reason is required
function rejectRequest(requestId) {
    const reason = prompt('Reason for rejection:');
    if (reason === null) return;
    if (!reason.trim()) {
        showAlert('Please give a reason for rejecting the request', 'warning');
        return;
    }

    apiCall(`/leave-requests/${requestId}/reject`, 'POST', { comments: reason })
        .then(data => {
            if (data) {
                showAlert('Leave request rejected', 'success');
                reload();
            }
        });
}

// Cancel request
function cancelRequest(requestId, status) {
    const message = status === 'APPROVED'
        ? 'Cancel this approved leave? The days will be returned to the balance.'
        : 'Withdraw this leave request?';
    if (confirm(message)) {
        apiCall(`/leave-requests/${requestId}/cancel`, 'POST')
            .then(data => {
                if (data) {
                    showAlert('Leave request cancelled', 'success');
                    reload();
                }
            });
    }
}

// View request details, including the approval history
function viewRequestDetails(requestId) {
    apiCall(`/leave-requests/${requestId}`)
        .then(data => {
            if (data) {
                const status = LEAVE_STATUS[data.status] ? LEAVE_STATUS[data.status].label : data.status;
                const trail = approvalTrail(data.approvals);
                const details = `Leave Request #${data.leaveRequestId}

Employee: ${data.employeeName}
Reporting Lead: ${data.reportingLeadName || '-'}
Leave Type: ${data.leaveTypeName}
Dates: ${formatDate(data.startDate)} to ${formatDate(data.endDate)} (${formatDays(data.numberOfDays)} day(s)${data.halfDay ? ', ' + (data.halfDay === 'FIRST_HALF' ? 'first half' : 'second half') : ''})
Reason: ${data.reason}
Requested on: ${formatDate(data.requestDate)}
Status: ${status}
${data.balanceAfterApproval !== null && data.balanceAfterApproval !== undefined ? `Balance after approval: ${formatDays(data.balanceAfterApproval)}${data.balanceAfterApproval < 0 ? ' (below zero)' : ''}\n` : ''}
Approval history:
${trail || '(none yet)'}`;

                alert(details);
            }
        });
}
