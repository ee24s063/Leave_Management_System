// Main JavaScript for Leave Management System (loaded on every page)

const API_BASE_URL = '/lms/api';

// Display alerts
function showAlert(message, type = 'success') {
    const alertDiv = document.createElement('div');
    alertDiv.className = `alert alert-${type} alert-dismissible fade show`;
    alertDiv.innerHTML = `
        ${escapeHtml(message)}
        <button type="button" class="btn-close" data-bs-dismiss="alert"></button>
    `;
    document.body.insertBefore(alertDiv, document.body.firstChild);

    setTimeout(() => {
        alertDiv.remove();
    }, 5000);
}

// Escape text before inserting it into HTML (names, reasons and comments are typed by users)
function escapeHtml(value) {
    if (value === null || value === undefined) return '';
    return String(value)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

// CSRF token from the page's <meta> tags; required for POST/PUT/DELETE
function csrfHeaders() {
    const token = document.querySelector('meta[name="_csrf"]');
    const header = document.querySelector('meta[name="_csrf_header"]');
    return token && header ? { [header.content]: token.content } : {};
}

// API Helper Functions
// Returns the parsed JSON (or {} for an empty success response), or null after showing the error
async function apiCall(endpoint, method = 'GET', data = null) {
    const options = {
        method: method,
        headers: {
            'Content-Type': 'application/json',
            ...(method === 'GET' ? {} : csrfHeaders())
        }
    };

    if (data) {
        options.body = JSON.stringify(data);
    }

    try {
        const response = await fetch(`${API_BASE_URL}${endpoint}`, options);
        if (response.status === 401) {
            window.location.href = '/lms/login';
            return null;
        }
        if (!response.ok) {
            let message = `Request failed (${response.status})`;
            try {
                const body = await response.json();
                if (body && body.message) message = body.message;
            } catch (ignored) {
                if (response.status === 403) message = 'You do not have permission to do this';
            }
            showAlert(message, 'danger');
            return null;
        }
        const text = await response.text();
        return text ? JSON.parse(text) : {};
    } catch (error) {
        console.error('API Error:', error);
        showAlert('An error occurred while processing your request', 'danger');
        return null;
    }
}

// Logged-in user (account, role and employee profile), fetched once per page
let currentUserPromise = null;
function getCurrentUser() {
    if (!currentUserPromise) {
        currentUserPromise = apiCall('/me');
    }
    return currentUserPromise;
}

// Names shown for each role; the Lab Head's name is set on the server (app.role-names.lab-head)
const ROLE_NAMES = Object.assign({ EMPLOYEE: 'Employee', TEAM_LEAD: 'Team Lead', HR_ADMIN: 'HR', LAB_HEAD: 'Lab Head' },
    window.SERVER_ROLE_NAMES || {});

// Leave request statuses: display label and Bootstrap badge colour
const LEAVE_STATUS = {
    PENDING_TL: { label: 'Waiting for Team Lead', badge: 'warning' },
    PENDING_HR: { label: 'Waiting for HR', badge: 'info' },
    PENDING_HEAD: { label: `Waiting for ${ROLE_NAMES.LAB_HEAD} / Team Lead`, badge: 'warning' },
    APPROVED: { label: 'Approved', badge: 'success' },
    REJECTED: { label: 'Rejected', badge: 'danger' },
    CANCELLED: { label: 'Cancelled', badge: 'secondary' }
};

// Roles with an approvals queue of their own ("Team Approvals")
function hasTeamQueue(role) {
    return role === 'TEAM_LEAD' || role === 'LAB_HEAD';
}

// Whether the user can approve/reject a request at its current step (the server checks again:
// a Team Lead only for their own reports, a Lab Head only for the HR staff they approve).
// HR staff's requests (PENDING_HEAD) go to their Lab Head or chosen Team Lead, never to other HR users.
function canApproveStatus(role, status) {
    if (role === 'HR_ADMIN') return status === 'PENDING_TL' || status === 'PENDING_HR';
    if (role === 'TEAM_LEAD') return status === 'PENDING_TL' || status === 'PENDING_HEAD';
    if (role === 'LAB_HEAD') return status === 'PENDING_HEAD';
    return false;
}

// Where a newly submitted request went, for the confirmation message
function submittedTo(result) {
    if (result.status === 'PENDING_TL') return `sent to ${result.reportingLeadName || 'your Team Lead'} for approval`;
    if (result.status === 'PENDING_HEAD') {
        return `sent to your ${ROLE_NAMES.LAB_HEAD}${result.reportingLeadName ? ' and to ' + result.reportingLeadName : ''}`
            + ' for approval (either one can approve it)';
    }
    return 'sent to HR for approval';
}

function statusBadge(status) {
    const s = LEAVE_STATUS[status] || { label: status, badge: 'light' };
    return `<span class="badge bg-${s.badge}${s.badge === 'warning' || s.badge === 'info' ? ' text-dark' : ''}">${escapeHtml(s.label)}</span>`;
}

// One line per approval step, e.g. "Team Lead: approved by Lee Test (Mar 3) - Enjoy"
function approvalTrail(approvals) {
    if (!approvals || approvals.length === 0) return '';
    return approvals.map(a => {
        const step = a.level === 'TEAM_LEAD' ? 'Team Lead' : a.level === 'LAB_HEAD' ? ROLE_NAMES.LAB_HEAD : 'HR';
        const action = a.action === 'APPROVED' ? 'approved' : 'rejected';
        return `${step}: ${action} by ${a.approverName} (${formatDate(a.actedAt)})${a.comments ? ' - ' + a.comments : ''}`;
    }).join('\n');
}

// Day counts may be halves (e.g. 11.5); show them without trailing zeros
function formatDays(value) {
    if (value === null || value === undefined || value === '') return '';
    return String(Number(value));
}

// Format date
function formatDate(dateString) {
    if (!dateString) return '';
    const options = { year: 'numeric', month: 'short', day: 'numeric' };
    return new Date(dateString).toLocaleDateString(undefined, options);
}

// Fill a <select> with the leave types from the server (if the page has it) and return the types
async function loadLeaveTypeOptions(selectId) {
    const types = await apiCall('/leave-types');
    if (!types) return [];
    const select = document.getElementById(selectId);
    if (!select) return types;
    select.innerHTML = '<option value="">Select Leave Type</option>' + types.map(t =>
        `<option value="${t.leaveTypeId}">${escapeHtml(t.leaveTypeName)}</option>`).join('');
    return types;
}

// Initialize common date input with today's date minimum
function initializeDateInputs() {
    const today = new Date().toISOString().split('T')[0];
    const dateInputs = document.querySelectorAll('input[type="date"]');
    dateInputs.forEach(input => {
        if (input.id === 'dateOfJoining' || input.dataset.allowPast !== undefined) {
            // Past dates allowed (joining date, days already worked)
        } else {
            input.min = today;
        }
    });
}

// Document ready
document.addEventListener('DOMContentLoaded', function() {
    initializeDateInputs();
});
