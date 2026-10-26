package com.leavemanagement.controller;

import com.leavemanagement.dto.AuthResponse;
import com.leavemanagement.dto.ChangePasswordRequest;
import com.leavemanagement.dto.EmployeeView;
import com.leavemanagement.dto.MeResponse;
import com.leavemanagement.dto.UpdateProfileRequest;
import com.leavemanagement.entity.UserAccount;
import com.leavemanagement.repository.UserAccountRepository;
import com.leavemanagement.security.AppUserDetails;
import com.leavemanagement.security.CurrentUser;
import com.leavemanagement.security.JwtService;
import com.leavemanagement.service.EmployeeService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import java.util.NoSuchElementException;

/**
 * The logged-in user's own profile and password
 */
@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
public class ProfileController {
    private final CurrentUser currentUser;
    private final EmployeeService employeeService;
    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    @GetMapping
    public MeResponse me() {
        AppUserDetails me = currentUser.get();
        EmployeeView employee = me.getEmployeeId() == null ? null
                : employeeService.getEmployeeById(me.getEmployeeId())
                        .map(e -> employeeService.toView(e, true))
                        .orElse(null);
        return new MeResponse(me.getUserId(), me.getUsername(), me.getRole(), me.isMustChangePassword(), employee, me.isAdmin());
    }

    /**
     * Employees may update their own contact details; everything else is changed by HR
     */
    @PutMapping
    public EmployeeView updateProfile(@Valid @RequestBody UpdateProfileRequest request) {
        Integer employeeId = currentUser.requireEmployeeId();
        return employeeService.toView(employeeService.updateOwnPhone(employeeId, request.phone()), true);
    }

    /**
     * Change the password. All earlier tokens stop working (other browsers and devices are logged out);
     * this browser gets a new login cookie, and the new token is also returned for Bearer clients.
     */
    @PostMapping("/password")
    public AuthResponse changePassword(@Valid @RequestBody ChangePasswordRequest request,
                                               HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        UserAccount account = userAccountRepository.findById(currentUser.get().getUserId())
                .orElseThrow(() -> new NoSuchElementException("Account not found"));
        if (!passwordEncoder.matches(request.currentPassword(), account.getPasswordHash())) {
            throw new IllegalArgumentException("Current password is incorrect");
        }
        if (request.currentPassword().equals(request.newPassword())) {
            throw new IllegalArgumentException("New password must be different from the current one");
        }
        account.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        account.setMustChangePassword(false);
        account.revokeTokens();
        userAccountRepository.save(account);

        JwtService.IssuedToken token = jwtService.issue(account);
        if (!JwtService.hasBearerHeader(httpRequest)) {
            jwtService.addCookie(httpRequest, httpResponse, token);
        }
        return AuthResponse.of(token, account);
    }
}
