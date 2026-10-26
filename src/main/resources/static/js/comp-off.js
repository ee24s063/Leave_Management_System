// Comp-off JavaScript: request days back for work on weekends/holidays; Team Lead then HR approve
// (HR staff's requests: their Lab Head or chosen Team Lead, either one's approval is final)

const COMP_VIEWS = {
    mine: null,
    team: '/comp-off/pending/team',
    hr: '/comp-off/pending/hr',
    all: '/comp-off'
};

let me = null;
let currentView = 'mine';

document.addEventListener('DOMContentLoaded', async function() {
    me = await getCurrentUser();
    if (!me) return;

    const today = new Date().toISOString().split('T')[0];
    ['workedFrom', 'workedTo'].forEach(id => document.getElementById(id).max = today);

    if (!me.employee) {
        document.getElementById('applySection').hidden = true;
        document.getElementById('tab-mine').hidden = true;
    } else {
        document.getElementById('compOffForm').addEventListener('submit', submitCompOff);
        loadMyBalance();
    }
    document.getElementById('tab-team').hidden = !hasTeamQueue(me.role);
    document.getElementById('tab-hr').hidden = me.role !== 'HR_ADMIN';

    const start = me.role === 'HR_ADMIN' ? 'hr' : hasTeamQueue(me.role) ? 'team' : 'mine';
    showView(me.employee || start !== 'mine' ? start : 'all');
    refreshCounts();
});

async function loadMyBalance() {
    const year = new Date().getFullYear();
    const [balances, types] = await Promise.all([
        apiCall(`/leave-balance/employee/${me.employee.employeeId}/${year}`),
        apiCall('/leave-types')
    ]);
    const compType = (types || []).find(t => t.systemCode === 'COMP_OFF');
    const balance = compType && (balances || []).find(b => b.leaveTypeId === compType.leaveTypeId);
    document.getElementById('myCompOffBalance').textContent = balance ? formatDays(balance.remainingDays) : '0';
}

function showView(view) {
    currentView = view;
    document.querySelectorAll('#viewTabs .nav-link').forEach(a => a.classList.remove('active'));
    document.querySelector(`#tab-${view} .nav-link`).classList.add('active');
    const url = view === 'mine' ? `/comp-off/employee/${me.employee.employeeId}` : COMP_VIEWS[view];
    // Ignore a late answer for a tab the user has already left, so it cannot replace the current list
    apiCall(url).then(data => data && currentView === view && displayRequests(data));
}

function reload() {
    showView(currentView);
    refreshCounts();
    if (me.employee) loadMyBalance();
}

async function refreshCounts() {
    if (hasTeamQueue(me.role)) {
        const team = await apiCall('/comp-off/pending/team');
        document.getElementById('count-team').textContent = team && team.length ? team.length : '';
    }
    if (me.role === 'HR_ADMIN') {
        const hr = await apiCall('/comp-off/pending/hr');
        document.getElementById('count-hr').textContent = hr && hr.length ? hr.length : '';
    }
}

function isMine(req) {
    return me.employee && req.employeeId === me.employee.employeeId;
}

function canAct(req) {
    if (isMine(req)) return false;
    return canApproveStatus(me.role, req.status);
}

function canWithdraw(req) {
    return ['PENDING_TL', 'PENDING_HR', 'PENDING_HEAD'].includes(req.status) && (isMine(req) || me.role === 'HR_ADMIN');
}

function displayRequests(requests) {
    const tbody = document.getElementById('compOffList');
    if (requests.length === 0) {
        tbody.innerHTML = '<tr><td colspan="8" class="text-center">No comp-off requests here</td></tr>';
        return;
    }
    tbody.innerHTML = requests.map(r => {
        const worked = r.workedFrom === r.workedTo ? formatDate(r.workedFrom)
            : `${formatDate(r.workedFrom)} to ${formatDate(r.workedTo)}`;
        const trail = approvalTrail(r.approvals);
        return `
        <tr>
            <td>${r.compOffRequestId}</td>
            <td>${escapeHtml(r.employeeName)}${isMine(r) ? ' <span class="badge bg-light text-dark">you</span>' : ''}</td>
            <td>${worked}</td>
            <td>${formatDays(r.days)}</td>
            <td>${escapeHtml(r.reason)}</td>
            <td>${formatDate(r.requestDate)}</td>
            <td>${statusBadge(r.status)}${trail ? `<div class="small text-muted" style="white-space: pre-line">${escapeHtml(trail)}</div>` : ''}</td>
            <td class="text-nowrap">
                ${canAct(r) ? `
                    <button class="btn btn-sm btn-success" onclick="approveCompOff(${r.compOffRequestId}, '${r.status}')">Approve</button>
                    <button class="btn btn-sm btn-danger" onclick="rejectCompOff(${r.compOffRequestId})">Reject</button>` : ''}
                ${canWithdraw(r) ? `<button class="btn btn-sm btn-warning" onclick="withdrawCompOff(${r.compOffRequestId})">Withdraw</button>` : ''}
            </td>
        </tr>`;
    }).join('');
}

async function submitCompOff(e) {
    e.preventDefault();
    const workedFrom = document.getElementById('workedFrom').value;
    const workedTo = document.getElementById('workedTo').value;
    if (workedTo < workedFrom) {
        showAlert('The last day worked must be on or after the first day', 'danger');
        return;
    }
    const result = await apiCall('/comp-off', 'POST', {
        workedFrom: workedFrom,
        workedTo: workedTo,
        reason: document.getElementById('compOffReason').value
    });
    if (result) {
        showAlert(`Comp-off request for ${formatDays(result.days)} day(s) ${submittedTo(result)}.`, 'success');
        document.getElementById('compOffForm').reset();
        if (currentView !== 'mine') showView('mine'); else reload();
    }
}

function approveCompOff(id, status) {
    let message = 'Approval comments (optional):';
    if (status === 'PENDING_HEAD') {
        message = 'Your approval is final: the days will be added to the comp-off balance.\n\n' + message;
    } else if (me.role === 'HR_ADMIN' && status === 'PENDING_TL') {
        message = 'The Team Lead has not approved this yet. Approving now completes both steps and credits the days.\n\n' + message;
    } else if (me.role === 'HR_ADMIN') {
        message = 'Final approval: the days will be added to the employee\'s comp-off balance.\n\n' + message;
    } else {
        message = 'After your approval the request goes to HR for final approval.\n\n' + message;
    }
    const comments = prompt(message);
    if (comments === null) return;
    apiCall(`/comp-off/${id}/approve`, 'POST', { comments: comments }).then(data => {
        if (data) {
            showAlert(data.status === 'APPROVED' ? 'Comp-off approved and credited.' : 'Approved and sent to HR.', 'success');
            reload();
        }
    });
}

function rejectCompOff(id) {
    const reason = prompt('Reason for rejection:');
    if (reason === null) return;
    if (!reason.trim()) {
        showAlert('Please give a reason for rejecting the request', 'warning');
        return;
    }
    apiCall(`/comp-off/${id}/reject`, 'POST', { comments: reason }).then(data => {
        if (data) {
            showAlert('Comp-off request rejected', 'success');
            reload();
        }
    });
}

function withdrawCompOff(id) {
    if (!confirm('Withdraw this comp-off request?')) return;
    apiCall(`/comp-off/${id}/cancel`, 'POST').then(data => {
        if (data) {
            showAlert('Comp-off request withdrawn', 'success');
            reload();
        }
    });
}
