// Leave Balance JavaScript
// Employees see their own balance, Team Leads also their reports', HR everyone's and can adjust them and add leave types.

let leaveTypeNames = {};

const TRANSACTION_LABELS = {
    ALLOCATION: 'Allotted',
    MONTHLY_CREDIT: 'Monthly leave added',
    LEAVE_DEDUCTED: 'Leave taken',
    LEAVE_RESTORED: 'Leave cancelled',
    COMP_OFF_CREDIT: 'Comp-off credited',
    ADJUSTMENT: 'HR adjustment',
    RESET: 'Yearly reset'
};

// Load on page load
document.addEventListener('DOMContentLoaded', async function() {
    const year = new Date().getFullYear();
    document.getElementById('yearBalance').value = year;

    const me = await getCurrentUser();
    if (!me) return;
    await loadLeaveTypes();
    await loadEmployeeOptions(me);

    const isHr = me.role === 'HR_ADMIN';
    if (isHr) {
        document.getElementById('leaveTypeForm').addEventListener('submit', addLeaveType);
        document.getElementById('adjustForm').addEventListener('submit', adjustBalance);
    }
    if (document.getElementById('employeeIdBalance').value) {
        loadLeaveBalance();
    }
});

// Employees the user may view: self, plus the team for a lead, the HR staff a Lab Head approves, or everyone for HR
async function loadEmployeeOptions(me) {
    let employees = [];
    if (me.role === 'HR_ADMIN' || me.role === 'TEAM_LEAD' || me.role === 'LAB_HEAD') {
        employees = (await apiCall('/employees')) || [];
    }
    if (me.employee && !employees.some(e => e.employeeId === me.employee.employeeId)) {
        employees.unshift(me.employee);
    }
    const options = employees.map(e =>
        `<option value="${e.employeeId}">${escapeHtml(e.fullName)} (#${e.employeeId})</option>`).join('');

    const viewSelect = document.getElementById('employeeIdBalance');
    viewSelect.innerHTML = options || '<option value="">No employees</option>';
    if (me.employee) viewSelect.value = me.employee.employeeId;

    // Opened from a dashboard link: ?employeeId=..&year=..
    const params = new URLSearchParams(window.location.search);
    const linkedId = params.get('employeeId');
    if (linkedId && employees.some(e => String(e.employeeId) === linkedId)) {
        viewSelect.value = linkedId;
    }
    if (/^\d{4}$/.test(params.get('year') || '')) {
        document.getElementById('yearBalance').value = params.get('year');
    }

}

async function loadLeaveTypes() {
    const types = await loadLeaveTypeOptions('adjustLeaveTypeId');
    // Only the two balances can be adjusted: monthly leave (used by every other type) and comp-off
    const adjustSelect = document.getElementById('adjustLeaveTypeId');
    if (adjustSelect) {
        types.filter(t => !t.systemCode).forEach(t => {
            const option = adjustSelect.querySelector(`option[value="${t.leaveTypeId}"]`);
            if (option) option.remove();
        });
    }
    leaveTypeNames = Object.fromEntries(types.map(t => [t.leaveTypeId, t.leaveTypeName]));
    const tbody = document.getElementById('leaveTypesList');
    if (tbody) {
        tbody.innerHTML = types.length === 0
            ? '<tr><td colspan="3" class="text-center">No leave types yet. Add one above.</td></tr>'
            : types.map(t => `
                <tr>
                    <td>${t.leaveTypeId}</td>
                    <td>${escapeHtml(t.leaveTypeName)}${t.systemCode ? ' <span class="badge bg-secondary">built-in</span>' : ''}</td>
                    <td>${escapeHtml(t.description || '')}</td>
                </tr>`).join('');
    }
}

// Load leave balance
function loadLeaveBalance() {
    const employeeId = document.getElementById('employeeIdBalance').value;
    const year = document.getElementById('yearBalance').value;

    if (!employeeId || !year) {
        showAlert('Please choose an employee and year', 'warning');
        return;
    }

    apiCall(`/leave-balance/employee/${employeeId}/${year}`)
        .then(data => {
            if (data) {
                displayLeaveBalance(data);
            }
        });
    loadLedger(employeeId, year);
}

// Every change to the balances: allotments, leave taken/cancelled, comp-off credits, HR adjustments
function loadLedger(employeeId, year) {
    apiCall(`/leave-balance/transactions/${employeeId}/${year}`).then(rows => {
        const tbody = document.getElementById('ledgerList');
        if (!rows) return;
        if (rows.length === 0) {
            tbody.innerHTML = '<tr><td colspan="6" class="text-center">No balance changes recorded</td></tr>';
            return;
        }
        tbody.innerHTML = rows.slice().reverse().map(t => `
            <tr>
                <td>${formatDate(t.createdAt)}</td>
                <td>${escapeHtml(t.leaveTypeName)}</td>
                <td>${escapeHtml(TRANSACTION_LABELS[t.type] || t.type)}</td>
                <td class="${t.days < 0 ? 'text-danger' : 'text-success'} fw-bold">${t.days > 0 ? '+' : ''}${formatDays(t.days)}</td>
                <td>${escapeHtml(t.reason || '')}${t.referenceId ? ` <span class="text-muted">(#${t.referenceId})</span>` : ''}</td>
                <td>${escapeHtml(t.createdByName)}</td>
            </tr>`).join('');
    });
}

// Display leave balance
function displayLeaveBalance(balances) {
    const tbody = document.getElementById('leaveBalanceList');
    tbody.innerHTML = '';

    if (balances.length === 0) {
        tbody.innerHTML = '<tr><td colspan="5" class="text-center">No leave balance found</td></tr>';
        return;
    }

    balances.forEach(balance => {
        const row = document.createElement('tr');
        const available = Number(balance.totalDaysAllowed) + Number(balance.adjustedDays);
        const percentageUsed = available > 0 ? Math.min(100, balance.usedDays / available * 100).toFixed(1) : 0;
        const negative = balance.remainingDays < 0;

        row.innerHTML = `
            <td>${escapeHtml(leaveTypeNames[balance.leaveTypeId] || balance.leaveTypeId)}</td>
            <td>${formatDays(balance.totalDaysAllowed)}</td>
            <td>${Number(balance.adjustedDays) > 0 ? '+' : ''}${formatDays(balance.adjustedDays)}</td>
            <td>${formatDays(balance.usedDays)}</td>
            <td>
                <strong class="${negative ? 'text-danger' : ''}">${formatDays(balance.remainingDays)}</strong>
                ${negative ? '<span class="badge bg-danger ms-1">below zero</span>' : ''}
                <div class="progress" style="height: 20px;">
                    <div class="progress-bar ${negative ? 'bg-danger' : ''}" role="progressbar" style="width: ${negative ? 100 : percentageUsed}%"
                         aria-valuenow="${balance.usedDays}" aria-valuemin="0" aria-valuemax="${available}">
                        ${negative ? 'over' : percentageUsed + '% used'}
                    </div>
                </div>
            </td>
        `;
        tbody.appendChild(row);
    });
}

// Download the selected employee's balance history for the selected year as Excel (HR)
function exportLedger() {
    const employeeId = document.getElementById('employeeIdBalance').value;
    const year = document.getElementById('yearBalance').value;
    if (!employeeId || !year) {
        showAlert('Please choose an employee and year', 'warning');
        return;
    }
    window.location.href = `${API_BASE_URL}/reports/transactions/export?employeeId=${employeeId}&year=${year}`;
}

// Add or remove days for the selected employee (HR)
async function adjustBalance(e) {
    e.preventDefault();
    const select = document.getElementById('employeeIdBalance');
    const employeeId = select.value;
    const year = document.getElementById('yearBalance').value;
    const days = parseFloat(document.getElementById('adjustDays').value);
    if (!employeeId || !year) {
        showAlert('Choose the employee and year above first', 'warning');
        return;
    }
    const name = select.options[select.selectedIndex].text;
    if (!confirm(`${days > 0 ? 'Add' : 'Remove'} ${formatDays(Math.abs(days))} day(s) for ${name}?`)) return;
    const result = await apiCall('/leave-balance/adjust', 'POST', {
        employeeId: parseInt(employeeId),
        leaveTypeId: parseInt(document.getElementById('adjustLeaveTypeId').value),
        year: parseInt(year),
        days: days,
        reason: document.getElementById('adjustReason').value
    });
    if (result) {
        showAlert(`Balance adjusted. Remaining: ${formatDays(result.remainingDays)} day(s)`, 'success');
        document.getElementById('adjustForm').reset();
        loadLeaveBalance();
    }
}

// Add a leave type (HR)
async function addLeaveType(e) {
    e.preventDefault();
    const result = await apiCall('/leave-types', 'POST', {
        leaveTypeName: document.getElementById('leaveTypeName').value.trim(),
        description: document.getElementById('leaveTypeDescription').value.trim() || null
    });
    if (result) {
        showAlert('Leave type added', 'success');
        document.getElementById('leaveTypeForm').reset();
        loadLeaveTypes();
    }
}
