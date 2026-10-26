// Employees Management JavaScript (HR only)

const ROLE_LABELS = { EMPLOYEE: 'Employee', TEAM_LEAD: 'Team Lead', HR_ADMIN: 'HR / Admin', LAB_HEAD: ROLE_NAMES.LAB_HEAD };
const STATUS_COLORS = { ACTIVE: 'success', INACTIVE: 'secondary', RESIGNED: 'warning', RELIEVED: 'dark' };

let employeesById = {};

// Load all employees on page load
document.addEventListener('DOMContentLoaded', function() {
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
        tbody.innerHTML = '<tr><td colspan="9" class="text-center">No employees found</td></tr>';
        return;
    }

    employees.forEach(emp => {
        const row = document.createElement('tr');
        row.innerHTML = `
            <td>${emp.employeeId}</td>
            <td>${escapeHtml(emp.fullName)}</td>
            <td>${escapeHtml(emp.email)}</td>
            <td>${escapeHtml(emp.department)}</td>
            <td>${escapeHtml(emp.designation)}</td>
            <td>${escapeHtml(ROLE_LABELS[emp.role] || '-')}</td>
            <td>${approversCell(emp)}</td>
            <td><span class="badge bg-${STATUS_COLORS[emp.status] || 'info'}">${escapeHtml(emp.status)}</span></td>
            <td class="text-nowrap">
                <button class="btn btn-sm btn-warning" onclick="editEmployee(${emp.employeeId})">Edit</button>
                <button class="btn btn-sm btn-info" onclick="resetPassword(${emp.employeeId})">Reset password</button>
                ${emp.status === 'ACTIVE' ? `<button class="btn btn-sm btn-danger" onclick="deactivateEmployee(${emp.employeeId})">Deactivate</button>` : ''}
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
        const result = await apiCall('/employees', 'POST', data);
        if (result) {
            showPasswordNotice(result.username, result.temporaryPassword);
            resetForm();
            loadAllEmployees();
        }
    }
}

function showPasswordNotice(username, password) {
    document.getElementById('noticeUsername').textContent = username;
    document.getElementById('noticePassword').textContent = password;
    document.getElementById('passwordNotice').hidden = false;
    window.scrollTo({ top: 0, behavior: 'smooth' });
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

    apiCall(`/employees/search?name=${encodeURIComponent(name)}`)
        .then(data => {
            if (data) {
                displayEmployees(data);
            }
        });
}

// Issue a new temporary password
async function resetPassword(employeeId) {
    const emp = employeesById[employeeId];
    if (!confirm(`Issue a new temporary password for ${emp ? emp.fullName : 'this employee'}?`)) return;
    const result = await apiCall(`/employees/${employeeId}/reset-password`, 'POST');
    if (result) {
        showPasswordNotice(emp ? emp.email : '', result.temporaryPassword);
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
