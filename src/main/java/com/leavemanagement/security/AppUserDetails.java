package com.leavemanagement.security;

import com.leavemanagement.entity.Role;
import com.leavemanagement.entity.UserAccount;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import java.util.Collection;
import java.util.List;

/**
 * Logged-in user as seen by Spring Security
 */
@Getter
public class AppUserDetails implements UserDetails {
    private final Integer userId;
    private final String username;
    private final String passwordHash;
    private final Role role;
    private final Integer employeeId;
    private final boolean enabled;
    private final boolean mustChangePassword;

    public AppUserDetails(UserAccount account) {
        this.userId = account.getUserId();
        this.username = account.getUsername();
        this.passwordHash = account.getPasswordHash();
        this.role = account.getRole();
        this.employeeId = account.getEmployeeId();
        this.enabled = account.isEnabled();
        this.mustChangePassword = account.isMustChangePassword();
    }

    public boolean hasRole(Role r) {
        return role == r;
    }

    /**
     * The built-in admin account: HR role without an employee record. Only it may delete employees permanently.
     */
    public boolean isAdmin() {
        return role == Role.HR_ADMIN && employeeId == null;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }
}
