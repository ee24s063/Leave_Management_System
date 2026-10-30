// Dashboard JavaScript
// HR: organisation-wide cards, negative balances and a filterable balance summary.
// Team Lead: their team's pending counts and balances. The page only contains the section for the user's role.

let balanceRows = [];
let sortByRemaining = null; // null = by name (server order), 'asc' or 'desc'

document.addEventListener('DOMContentLoaded', async function() {
    const yearInput = document.getElementById('reportYear');
    yearInput.value = new Date().getFullYear();
    yearInput.addEventListener('change', loadAll);

    if (document.getElementById('hrDashboard')) {
        await loadFilters();
        document.getElementById('filterDepartment').addEventListener('change', loadBalanceSummary);
        document.getElementById('filterLeaveType').addEventListener('change', loadBalanceSummary);
        document.getElementById('sortRemaining').addEventListener('click', toggleSort);
        initRequestExport();
    }
    loadAll();
});

// --- Excel downloads: plain links (the login cookie goes with them) carrying the current filters ---

function updateExportLinks() {
    const params = new URLSearchParams({ year: selectedYear() });
    const department = document.getElementById('filterDepartment').value;
    const leaveTypeId = document.getElementById('filterLeaveType').value;
    if (department) params.set('department', department);
    if (leaveTypeId) params.set('leaveTypeId', leaveTypeId);
    document.getElementById('exportBalances').href = `${API_BASE_URL}/reports/leave-balance/export?${params}`;
    document.getElementById('exportNegative').href =
        `${API_BASE_URL}/reports/negative-balances/export?year=${selectedYear()}`;
}

function initRequestExport() {
    const year = new Date().getFullYear();
    document.getElementById('exportFrom').value = `${year}-01-01`;
    document.getElementById('exportTo').value = `${year}-12-31`;
    document.getElementById('exportRequestsForm').addEventListener('submit', e => {
        e.preventDefault();
        const from = document.getElementById('exportFrom').value;
        const to = document.getElementById('exportTo').value;
        if (to < from) {
            showAlert("'To' must not be before 'From'", 'warning');
            return;
        }
        const params = new URLSearchParams({ from, to });
        const status = document.getElementById('exportStatus').value;
        if (status) params.set('status', status);
        window.location.href = `${API_BASE_URL}/reports/leave-requests/export?${params}`;
    });
}

function selectedYear() {
    return document.getElementById('reportYear').value || new Date().getFullYear();
}

function loadAll() {
    if (document.getElementById('hrDashboard')) {
        updateExportLinks();
        loadSummary();
        loadNegativeBalances();
        loadBalanceSummary();
    }
    if (document.getElementById('teamDashboard')) {
        loadTeam();
    }
}

// Link to the Leave Balance page, opened on this employee and year (shows their ledger)
function employeeLink(employeeId, name) {
    return `<a href="/lms/leave-balance?employeeId=${employeeId}&year=${selectedYear()}">${escapeHtml(name)}</a>`;
}

function remainingCell(value) {
    return `<td class="text-end fw-bold ${value < 0 ? 'text-danger' : ''}">${formatDays(value)}</td>`;
}

function signedDays(value) {
    const n = Number(value);
    if (n === 0) return '<span class="text-muted">0</span>';
    return (n > 0 ? '+' : '') + formatDays(n);
}

// "no expiry" for the comp-off balance, which has no year
function leaveTypeCell(row) {
    return `${escapeHtml(row.leaveTypeName)}${row.year === 0 ? ' <span class="text-muted small">(no expiry)</span>' : ''}`;
}

// --- HR ---

async function loadFilters() {
    const departments = await apiCall('/reports/departments');
    if (departments) {
        document.getElementById('filterDepartment').innerHTML = '<option value="">All departments</option>' +
            departments.map(d => `<option value="${escapeHtml(d)}">${escapeHtml(d)}</option>`).join('');
    }
    const types = await apiCall('/leave-types');
    if (types) {
        // Balances exist only for the built-in types: monthly leave (shared by the others) and comp-off
        document.getElementById('filterLeaveType').innerHTML = '<option value="">All balances</option>' +
            types.filter(t => t.systemCode).map(t => `<option value="${t.leaveTypeId}">${escapeHtml(t.leaveTypeName)}</option>`).join('');
    }
}

async function loadSummary() {
    const s = await apiCall(`/reports/summary?year=${selectedYear()}`);
    if (!s) return;
    document.getElementById('activeEmployees').textContent = s.activeEmployees;
    document.getElementById('pendingTeamLead').textContent = s.leavePendingTeamLead + s.compOffPendingTeamLead;
    document.getElementById('pendingTeamLeadDetail').textContent =
        `${s.leavePendingTeamLead} leave, ${s.compOffPendingTeamLead} comp-off`;
    document.getElementById('pendingHr').textContent = s.leavePendingHr + s.compOffPendingHr;
    document.getElementById('pendingHrDetail').textContent = `${s.leavePendingHr} leave, ${s.compOffPendingHr} comp-off`;

    document.getElementById('negativeCount').textContent = s.employeesWithNegativeBalance;
    const card = document.getElementById('negativeCard');
    card.className = 'card h-100 ' + (s.employeesWithNegativeBalance > 0 ? 'text-white bg-danger' : 'bg-light');

    document.getElementById('dayTotals').textContent =
        `${s.year}: ${formatDays(s.totalAllotted)} days added, ${formatDays(s.totalUsed)} used, ` +
        `${formatDays(s.totalRemaining)} remaining (all employees, not counting comp-off).`;
}

async function loadNegativeBalances() {
    const rows = await apiCall(`/reports/negative-balances?year=${selectedYear()}`);
    const tbody = document.getElementById('negativeList');
    if (!rows) return;
    if (rows.length === 0) {
        tbody.innerHTML = '<tr><td colspan="6" class="text-center text-muted">No balances below zero</td></tr>';
        return;
    }
    tbody.innerHTML = rows.map(r => `
        <tr>
            <td>${employeeLink(r.employeeId, r.employeeName)}</td>
            <td>${escapeHtml(r.department)}</td>
            <td>${escapeHtml(r.leadName || '-')}</td>
            <td>${leaveTypeCell(r)}</td>
            ${remainingCell(r.remainingDays)}
            <td>${r.negativeSince ? formatDate(r.negativeSince) : '<span class="text-muted">not in history</span>'}</td>
        </tr>`).join('');
}

async function loadBalanceSummary() {
    const params = new URLSearchParams({ year: selectedYear() });
    const department = document.getElementById('filterDepartment').value;
    const leaveTypeId = document.getElementById('filterLeaveType').value;
    if (department) params.set('department', department);
    if (leaveTypeId) params.set('leaveTypeId', leaveTypeId);
    updateExportLinks();

    const rows = await apiCall(`/reports/leave-balance?${params}`);
    if (!rows) return;
    balanceRows = rows;
    renderBalanceSummary();
}

function toggleSort(e) {
    e.preventDefault();
    sortByRemaining = sortByRemaining === 'asc' ? 'desc' : 'asc';
    renderBalanceSummary();
}

function renderBalanceSummary() {
    const tbody = document.getElementById('balanceList');
    document.getElementById('sortArrow').textContent =
        sortByRemaining === 'asc' ? '▲' : sortByRemaining === 'desc' ? '▼' : '';
    if (balanceRows.length === 0) {
        tbody.innerHTML = '<tr><td colspan="10" class="text-center text-muted">No balances for this year and filter</td></tr>';
        return;
    }
    const rows = balanceRows.slice();
    if (sortByRemaining) {
        const dir = sortByRemaining === 'asc' ? 1 : -1;
        rows.sort((a, b) => dir * (Number(a.remainingDays) - Number(b.remainingDays)));
    }
    tbody.innerHTML = rows.map(r => `
        <tr>
            <td>${r.employeeId}</td>
            <td>${employeeLink(r.employeeId, r.employeeName)}${r.employeeStatus !== 'ACTIVE'
                ? ` <span class="badge bg-secondary">${escapeHtml(r.employeeStatus.toLowerCase())}</span>` : ''}</td>
            <td>${escapeHtml(r.department)}</td>
            <td>${escapeHtml(r.leadName || '-')}</td>
            <td>${leaveTypeCell(r)}</td>
            <td class="text-end">${formatDays(r.totalDays)}</td>
            <td class="text-end">${formatDays(r.usedDays)}</td>
            <td class="text-end">${signedDays(r.compOffCredited)}</td>
            <td class="text-end">${signedDays(r.adjustments)}</td>
            ${remainingCell(r.remainingDays)}
        </tr>`).join('');
}

// --- Team Lead ---

async function loadTeam() {
    const team = await apiCall(`/reports/team?year=${selectedYear()}`);
    if (!team) return;
    document.getElementById('teamSize').textContent = team.teamSize;
    document.getElementById('waitingForMe').textContent = team.waitingForMe;
    document.getElementById('waitingForHr').textContent = team.waitingForHr;

    const tbody = document.getElementById('teamBalanceList');
    if (team.balances.length === 0) {
        tbody.innerHTML = `<tr><td colspan="7" class="text-center text-muted">${team.teamSize === 0
            ? 'No one reports to you yet' : 'No balances set up for this year'}</td></tr>`;
        return;
    }
    tbody.innerHTML = team.balances.map(r => `
        <tr>
            <td>${employeeLink(r.employeeId, r.employeeName)}</td>
            <td>${leaveTypeCell(r)}</td>
            <td class="text-end">${formatDays(r.totalDays)}</td>
            <td class="text-end">${formatDays(r.usedDays)}</td>
            <td class="text-end">${signedDays(r.compOffCredited)}</td>
            <td class="text-end">${signedDays(r.adjustments)}</td>
            ${remainingCell(r.remainingDays)}
        </tr>`).join('');
}
