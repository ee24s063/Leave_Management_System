// Employees Management JavaScript (HR only)

const ROLE_LABELS = { EMPLOYEE: 'Employee', TEAM_LEAD: 'Team Lead', HR_ADMIN: 'HR / Admin', LAB_HEAD: ROLE_NAMES.LAB_HEAD };
const STATUS_COLORS = { ACTIVE: 'success', INACTIVE: 'secondary', RESIGNED: 'warning', RELIEVED: 'dark' };

let employeesById = {};
let isAdmin = false; // the built-in admin account may also delete employees permanently

// Load all employees on page load
document.addEventListener('DOMContentLoaded', async function() {
    const me = await getCurrentUser();
    isAdmin = !!(me && me.admin);
    if (isAdmin) loadMailStatus();
    loadAllEmployees();
    document.getElementById('employeeForm').addEventListener('submit', saveEmployee);
    document.getElementById('role').addEventListener('change', () => loadLeadOptions());
});

function isHrRole() {
    return document.getElementById('role').value === 'HR_ADMIN';
}

// Load employees (active only unless "include inactive" is ticked)
function loadAllEmployees() {
    const includeInactive = document.getElementById('includeInactive').checked;
    apiCall(`/employees?includeInactive=${includeInactive}`)
        .then(data => {
            if (data) {
                displayEmployees(data);
            }
        });
    loadLeadOptions();
}

// Approver choices. Most employees: a reporting lead (active Team Leads and HR).
// HR staff: a Lab Head (required) and, optionally, a Team Lead; other HR users cannot approve their leave.
async function loadLeadOptions() {
    const employees = await apiCall('/employees');
    if (!employees) return;
    const hr = isHrRole();
    const editId = document.getElementById('editEmployeeId').value;
    const others = employees.filter(e => String(e.employeeId) !== editId);
    const option = e => `<option value="${e.employeeId}">${escapeHtml(e.fullName)} (${escapeHtml(ROLE_LABELS[e.role])})</option>`;

    const leadSelect = document.getElementById('reportingLead');
    const currentLead = leadSelect.value;
    const leads = others.filter(e => e.role === 'TEAM_LEAD' || (!hr && e.role === 'HR_ADMIN'));
    leadSelect.innerHTML = '<option value="">None</option>' + leads.map(option).join('');
    leadSelect.value = leads.some(l => String(l.employeeId) === currentLead) ? currentLead : '';
    document.getElementById('reportingLeadLabel').textContent = hr ? 'Team Lead (optional, can also approve)' : 'Reporting Lead';

    const headSelect = document.getElementById('headApprover');
    const currentHead = headSelect.value;
    headSelect.innerHTML = '<option value="">Choose...</option>' + others.filter(e => e.role === 'LAB_HEAD').map(option).join('');
    headSelect.value = currentHead;
    headSelect.required = hr;
    document.getElementById('headApproverGroup').hidden = !hr;
}

// Who approves the employee's leave, shown in the list
function approversCell(emp) {
    if (emp.role === 'HR_ADMIN') {
        const parts = [`${ROLE_NAMES.LAB_HEAD}: ${emp.headApproverName || 'not set'}`];
        if (emp.reportingLeadName) parts.push(`Team Lead: ${emp.reportingLeadName}`);
        return parts.map(escapeHtml).join('<br>');
    }
    return escapeHtml(emp.reportingLeadName || '-');
}

// Display employees in table
function displayEmployees(employees) {
    const tbody = document.getElementById('employeesList');
    tbody.innerHTML = '';
    employeesById = Object.fromEntries(employees.map(e => [e.employeeId, e]));

    if (employees.length === 0) {
        const message = document.getElementById('includeInactive').checked ? 'No employees found.'
            : 'No active employees found. Tick "Include inactive" to also see inactive, resigned and relieved employees.';
        tbody.innerHTML = `<tr><td colspan="10" class="text-center">${message}</td></tr>`;
        return;
    }

    employees.forEach(emp => {
        const row = document.createElement('tr');
        row.innerHTML = `
            <td>${emp.employeeId}</td>
            <td>${escapeHtml(emp.fullName)}</td>
            <td>${escapeHtml(emp.email)}</td>
            <td>${escapeHtml(emp.phone || '-')}</td>
            <td>${escapeHtml(emp.department)}</td>
            <td>${escapeHtml(emp.designation)}</td>
            <td>${escapeHtml(ROLE_LABELS[emp.role] || '-')}</td>
            <td>${approversCell(emp)}</td>
            <td><span class="badge bg-${STATUS_COLORS[emp.status] || 'info'}">${escapeHtml(emp.status)}</span></td>
            <td class="text-nowrap">
                <button class="btn btn-sm btn-warning" onclick="editEmployee(${emp.employeeId})">Edit</button>
                <button class="btn btn-sm btn-info" onclick="resetPassword(${emp.employeeId})">Reset password</button>
                ${emp.status === 'ACTIVE'
                    ? `<button class="btn btn-sm btn-danger" onclick="deactivateEmployee(${emp.employeeId})">Deactivate</button>`
                    : `<button class="btn btn-sm btn-success" onclick="activateEmployee(${emp.employeeId})">Activate</button>`}
                ${isAdmin ? `<button class="btn btn-sm btn-outline-danger" onclick="deleteEmployeePermanently(${emp.employeeId})">Delete permanently</button>` : ''}
            </td>
        `;
        tbody.appendChild(row);
    });
}

function formValues() {
    return {
        firstName: document.getElementById('firstName').value,
        lastName: document.getElementById('lastName').value,
        email: document.getElementById('email').value,
        phone: document.getElementById('phone').value,
        department: document.getElementById('department').value,
        designation: document.getElementById('designation').value,
        salary: parseFloat(document.getElementById('salary').value),
        dateOfJoining: document.getElementById('dateOfJoining').value,
        role: document.getElementById('role').value
    };
}

// Add a new employee, or save changes when editing
async function saveEmployee(e) {
    e.preventDefault();
    const editId = document.getElementById('editEmployeeId').value;
    const leadValue = document.getElementById('reportingLead').value;
    const headValue = document.getElementById('headApprover').value;
    const data = formValues();
    if (isHrRole() && headValue) data.headApproverId = parseInt(headValue);

    if (editId) {
        delete data.dateOfJoining;
        data.status = document.getElementById('status').value;
        if (leadValue) {
            data.reporting_LeadId = parseInt(leadValue);
        } else {
            data.clearReportingLead = true;
        }
        const result = await apiCall(`/employees/${editId}`, 'PUT', data);
        if (result) {
            showAlert('Employee updated successfully!', 'success');
            resetForm();
            loadAllEmployees();
        }
    } else {
        data.reporting_LeadId = leadValue ? parseInt(leadValue) : null;
        showAlert('Adding the employee and sending the email...', 'info');
        const result = await apiCall('/employees', 'POST', data);
        if (result) {
            showSentNotice(`Employee added. The temporary password has been sent to ${result.passwordSentTo}.`);
            resetForm();
            loadAllEmployees();
        }
    }
}

// The temporary password is emailed, never shown; this notice stays until dismissed
function showSentNotice(text) {
    document.getElementById('sentNoticeText').textContent = text;
    document.getElementById('sentNotice').hidden = false;
    window.scrollTo({ top: 0, behavior: 'smooth' });
}

// Admin: which address emails are sent from
async function loadMailStatus() {
    const status = await apiCall('/admin/mail-status');
    if (!status) return;
    document.getElementById('mailCheck').hidden = false;
    document.getElementById('mailStatus').textContent = status.configured
        ? `Temporary passwords are emailed from ${status.from}. Send yourself a test email to check delivery (and the Junk folder).`
        : 'Email is not set up yet: set LMS_MAIL_USERNAME and LMS_MAIL_PASSWORD on this computer and restart the app. '
          + 'Until then, employees cannot be added and passwords cannot be reset.';
}

async function sendTestEmail() {
    const to = document.getElementById('testEmailTo').value.trim();
    if (!to) {
        showAlert('Enter the address to send the test email to', 'warning');
        return;
    }
    const button = document.getElementById('testEmailBtn');
    button.disabled = true;
    showAlert('Sending the test email...', 'info');
    const result = await apiCall('/admin/test-email', 'POST', { to: to });
    button.disabled = false;
    if (result) showSentNotice(result.message);
}

// Fill the form with an employee's details for editing
function editEmployee(employeeId) {
    const emp = employeesById[employeeId];
    if (!emp) return;
    document.getElementById('editEmployeeId').value = emp.employeeId;
    document.getElementById('firstName').value = emp.firstName;
    document.getElementById('lastName').value = emp.lastName;
    document.getElementById('email').value = emp.email;
    document.getElementById('phone').value = emp.phone || '';
    document.getElementById('department').value = emp.department;
    document.getElementById('designation').value = emp.designation;
    document.getElementById('salary').value = emp.salary;
    document.getElementById('dateOfJoining').value = emp.dateOfJoining;
    document.getElementById('dateOfJoining').disabled = true;
    document.getElementById('role').value = emp.role || 'EMPLOYEE';
    document.getElementById('headApprover').value = '';
    document.getElementById('status').value = emp.status;
    document.getElementById('statusGroup').hidden = false;
    document.getElementById('formTitle').textContent = `Edit Employee #${emp.employeeId}`;
    document.getElementById('submitBtn').textContent = 'Save Changes';
    document.getElementById('cancelEditBtn').hidden = false;
    document.getElementById('reportingLead').value = '';
    loadLeadOptions().then(() => {
        document.getElementById('reportingLead').value = emp.reporting_LeadId || '';
        document.getElementById('headApprover').value = emp.headApproverId || '';
    });
    window.scrollTo({ top: 0, behavior: 'smooth' });
}

function resetForm() {
    document.getElementById('employeeForm').reset();
    document.getElementById('editEmployeeId').value = '';
    document.getElementById('dateOfJoining').disabled = false;
    document.getElementById('statusGroup').hidden = true;
    document.getElementById('formTitle').textContent = 'Add New Employee';
    document.getElementById('submitBtn').textContent = 'Add Employee';
    document.getElementById('cancelEditBtn').hidden = true;
    loadLeadOptions();
}

// Search employees by name
function searchEmployees() {
    const name = document.getElementById('searchName').value;
    if (!name.trim()) {
        showAlert('Please enter a name to search', 'warning');
        return;
    }

    // Follows the "Include inactive" box, like the full list
    const includeInactive = document.getElementById('includeInactive').checked;
    apiCall(`/employees/search?name=${encodeURIComponent(name)}&includeInactive=${includeInactive}`)
        .then(data => {
            if (data) {
                displayEmployees(data);
            }
        });
}

// Issue a new temporary password
async function resetPassword(employeeId) {
    const emp = employeesById[employeeId];
    if (!confirm(`Send a new temporary password to ${emp ? emp.fullName + ' (' + emp.email + ')' : 'this employee'}?`
        + '\nTheir current password will stop working.')) return;
    showAlert('Sending the email...', 'info');
    const result = await apiCall(`/employees/${employeeId}/reset-password`, 'POST');
    if (result) {
        showSentNotice(result.message + '.');
    }
}

// Deactivate employee
function deactivateEmployee(employeeId) {
    if (confirm('Deactivate this employee? Their login will be disabled.')) {
        apiCall(`/employees/${employeeId}`, 'DELETE')
            .then(data => {
                if (data) {
                    showAlert('Employee deactivated successfully!', 'success');
                    loadAllEmployees();
                }
            });
    }
}

// Make an inactive, resigned or relieved employee active again (their login is enabled)
async function activateEmployee(employeeId) {
    const emp = employeesById[employeeId];
    if (!confirm(`Activate ${emp ? emp.fullName : 'this employee'} again? Their login will be enabled.`)) return;
    const result = await apiCall(`/employees/${employeeId}`, 'PUT', { status: 'ACTIVE' });
    if (result) {
        showAlert(`${result.fullName} is active again.`, 'success');
        loadAllEmployees();
    }
}

// Admin only: delete the employee and all of their data. The full name must be typed to confirm.
async function deleteEmployeePermanently(employeeId) {
    const emp = employeesById[employeeId];
    if (!emp) return;
    const typed = prompt(`DELETE PERMANENTLY: ${emp.fullName} (#${emp.employeeId})\n\n`
        + 'This removes the employee, their login, all their leave and comp-off requests, balances and balance history, '
        + 'and every approval they gave. It cannot be undone. (To keep their records, use Deactivate instead.)\n\n'
        + `Type the full name "${emp.fullName}" to confirm:`);
    if (typed === null) return;
    if (typed.trim().replace(/\s+/g, ' ').toLowerCase() !== emp.fullName.trim().replace(/\s+/g, ' ').toLowerCase()) {
        showAlert('The name did not match, so nothing was deleted.', 'warning');
        return;
    }
    const result = await apiCall(`/employees/${employeeId}/delete-permanently`, 'POST', { confirmName: typed });
    if (result) {
        showAlert(result.message + '.', 'success');
        resetForm();
        loadAllEmployees();
    }
}
