// My Profile page: own details, contact update, leave balance and history


document.addEventListener('DOMContentLoaded', async function() {
    const me = await getCurrentUser();
    if (!me) return;

    if (!me.employee) {
        document.getElementById('noEmployeeNote').hidden = false;
        ['profileSection', 'balanceSection', 'historySection'].forEach(id => document.getElementById(id).hidden = true);
        return;
    }

    showProfile(me);
    document.getElementById('profileForm').addEventListener('submit', saveProfile);

    const year = new Date().getFullYear();
    document.getElementById('balanceYear').textContent = year;
    loadBalances(me.employee.employeeId, year);
    loadHistory(me.employee.employeeId);
});

function showProfile(me) {
    const e = me.employee;
    const rows = [
        ['Employee ID', e.employeeId],
        ['Name', e.fullName],
        ['Email (login)', e.email],
        ['Mobile number', e.phone || '-'],
        ['Department / Team', e.department],
        ['Designation', e.designation],
        ['Reporting Lead', e.reportingLeadName || '-'],
        ['Role', me.role.replace('_', ' ')],
        ['Date of Joining', formatDate(e.dateOfJoining)],
        ['Status', e.status]
    ];
    document.getElementById('profileDetails').innerHTML = rows.map(([label, value]) =>
        `<tr><th class="w-50">${escapeHtml(label)}</th><td>${escapeHtml(value)}</td></tr>`).join('');
    document.getElementById('phone').value = e.phone || '';
}

async function saveProfile(e) {
    e.preventDefault();
    const result = await apiCall('/me', 'PUT', { phone: document.getElementById('phone').value });
    if (result) {
        showAlert('Contact details updated', 'success');
        currentUserPromise = null;
        showProfile(await getCurrentUser());
    }
}

async function loadBalances(employeeId, year) {
    const [balances, types] = await Promise.all([
        apiCall(`/leave-balance/employee/${employeeId}/${year}`),
        apiCall('/leave-types')
    ]);
    const tbody = document.getElementById('myBalances');
    if (!balances || balances.length === 0) {
        tbody.innerHTML = '<tr><td colspan="4" class="text-center">No leave balance has been set up for this year. Please contact HR.</td></tr>';
        return;
    }
    const typeNames = Object.fromEntries((types || []).map(t => [t.leaveTypeId, t.leaveTypeName]));
    tbody.innerHTML = balances.map(b => `
        <tr>
            <td>${escapeHtml(typeNames[b.leaveTypeId] || b.leaveTypeId)}</td>
            <td>${formatDays(Number(b.totalDaysAllowed) + Number(b.adjustedDays))}</td>
            <td>${formatDays(b.usedDays)}</td>
            <td><strong class="${b.remainingDays < 0 ? 'text-danger' : ''}">${formatDays(b.remainingDays)}</strong>${b.year === 0 ? ' <span class="text-muted small">(no expiry)</span>' : ''}</td>
        </tr>`).join('');
}

async function loadHistory(employeeId) {
    const requests = await apiCall(`/leave-requests/employee/${employeeId}`);
    const tbody = document.getElementById('myHistory');
    if (!requests || requests.length === 0) {
        tbody.innerHTML = '<tr><td colspan="7" class="text-center">No leave requests yet</td></tr>';
        return;
    }
    tbody.innerHTML = requests.map(r => `
        <tr>
            <td>${escapeHtml(r.leaveTypeName)}</td>
            <td>${formatDate(r.startDate)}</td>
            <td>${formatDate(r.endDate)}</td>
            <td>${formatDays(r.numberOfDays)}${r.halfDay ? ' <span class="text-muted small">(half)</span>' : ''}</td>
            <td>${escapeHtml(r.reason)}</td>
            <td>${statusBadge(r.status)}</td>
            <td class="small" style="white-space: pre-line">${escapeHtml(approvalTrail(r.approvals))}</td>
        </tr>`).join('');
}
