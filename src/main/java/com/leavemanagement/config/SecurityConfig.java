package com.leavemanagement.config;

import com.leavemanagement.repository.UserAccountRepository;
import com.leavemanagement.security.CurrentUser;
import com.leavemanagement.security.JwtAuthenticationFilter;
import com.leavemanagement.security.JwtService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.authentication.logout.LogoutFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

/**
 * Login and role-based URL rules. Roles: EMPLOYEE, TEAM_LEAD, HR_ADMIN.
 * Per-record rules (own data, own team) are enforced in AccessPolicy.
 *
 * Login is stateless (no server session): the login form and POST /api/auth/login issue a JWT
 * (JwtService). Browsers keep it in an HttpOnly cookie, so pages and their CSRF protection work as before;
 * other clients send "Authorization: Bearer <token>" and need no CSRF token.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    private static final String HR = "HR_ADMIN";
    private static final String LEAD = "TEAM_LEAD";
    private static final String HEAD = "LAB_HEAD";

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // Back to the login form with an error, without creating a server session to hold the error
    private static SimpleUrlAuthenticationFailureHandler loginFailureHandler() {
        SimpleUrlAuthenticationFailureHandler handler = new SimpleUrlAuthenticationFailureHandler("/login?error");
        handler.setAllowSessionCreation(false);
        return handler;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, UserAccountRepository userAccountRepository,
                                                   JwtService jwtService, CurrentUser currentUser) throws Exception {
        http
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // The login lasts one request; JwtAuthenticationFilter stores it here so Spring sees it as existing
            .securityContext(context -> context.securityContextRepository(new RequestAttributeSecurityContextRepository()))
            .requestCache(cache -> cache.requestCache(new NullRequestCache()))
            // Reads the token and re-checks the account on every request (disabled, new role, cancelled token)
            .addFilterBefore(new JwtAuthenticationFilter(jwtService, userAccountRepository), LogoutFilter.class)
            // The CSRF token lives in a cookie instead of the session; Bearer-token clients are exempt
            .csrf(csrf -> csrf
                .csrfTokenRepository(new CookieCsrfTokenRepository())
                .ignoringRequestMatchers(JwtService::hasBearerHeader)
                .ignoringRequestMatchers("/api/auth/login"))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/login", "/css/**", "/js/**", "/error").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()

                // HR-only pages and API docs
                .requestMatchers("/employees").hasRole(HR)

                // Dashboard: HR sees the organisation, a Team Lead sees their own team
                .requestMatchers("/dashboard").hasAnyRole(LEAD, HR)
                .requestMatchers("/api/reports/team").hasRole(LEAD)
                .requestMatchers("/api/reports/**").hasRole(HR)
                .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/api-docs/**").hasRole(HR)

                // Employee records: HR manages them; list and view are filtered per role in the controller
                .requestMatchers(HttpMethod.POST, "/api/employees/**").hasRole(HR)
                .requestMatchers(HttpMethod.PUT, "/api/employees/**").hasRole(HR)
                .requestMatchers(HttpMethod.DELETE, "/api/employees/**").hasRole(HR)
                .requestMatchers("/api/employees/search", "/api/employees/department/**",
                                 "/api/employees/count/**").hasRole(HR)

                // Leave types: everyone reads, HR changes
                .requestMatchers(HttpMethod.GET, "/api/leave-types/**").authenticated()
                .requestMatchers("/api/leave-types/**").hasRole(HR)

                // Leave balances: HR sets up and resets
                .requestMatchers(HttpMethod.POST, "/api/leave-balance/**").hasRole(HR)

                // Leave requests: approvals by Team Leads and HR (HR staff's by their Lab Head or Team Lead);
                // organisation-wide queries by HR
                .requestMatchers("/api/leave-requests/*/approve", "/api/leave-requests/*/reject").hasAnyRole(LEAD, HR, HEAD)
                .requestMatchers("/api/leave-requests/pending/team").hasAnyRole(LEAD, HR, HEAD)
                .requestMatchers("/api/leave-requests/pending/hr").hasRole(HR)
                .requestMatchers("/api/leave-requests/department/**", "/api/leave-requests/date-range").hasRole(HR)

                // Comp-off requests: same two-step approval as leave
                .requestMatchers("/api/comp-off/*/approve", "/api/comp-off/*/reject").hasAnyRole(LEAD, HR, HEAD)
                .requestMatchers("/api/comp-off/pending/team").hasAnyRole(LEAD, HR, HEAD)
                .requestMatchers("/api/comp-off/pending/hr").hasRole(HR)

                .anyRequest().authenticated())
            .formLogin(form -> form
                .loginPage("/login")
                .failureHandler(loginFailureHandler())
                .successHandler((request, response, authentication) -> {
                    userAccountRepository.findById(currentUser.get().getUserId()).ifPresent(account ->
                            jwtService.addCookie(request, response, jwtService.issue(account)));
                    response.sendRedirect(request.getContextPath() + "/");
                })
                .permitAll())
            // Logging out cancels all of the user's tokens (every browser and device) and removes the cookie
            .logout(logout -> logout
                .addLogoutHandler((request, response, authentication) -> {
                    currentUser.find().flatMap(u -> userAccountRepository.findById(u.getUserId())).ifPresent(account -> {
                        account.revokeTokens();
                        userAccountRepository.save(account);
                    });
                    jwtService.clearCookie(request, response);
                })
                .logoutSuccessUrl("/login?logout")
                .permitAll())
            // API calls get 401 instead of a redirect to the login page
            .exceptionHandling(ex -> ex.defaultAuthenticationEntryPointFor(
                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED), new AntPathRequestMatcher("/api/**")));
        return http.build();
    }
}
