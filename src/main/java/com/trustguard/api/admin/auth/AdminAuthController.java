package com.trustguard.api.admin.auth;

import java.security.Principal;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Admin auth endpoints (login, logout). Exception mapping lives in AdminExceptionHandler (RULING 27); an
 * unhandled exception would trigger an /error dispatch that the default-deny chain (RULING 20) rejects.
 * Passwords over 72 bytes but within the DTO cap get the generic 401 (N7), not a revealing 400.
 */
@RestController
@RequestMapping("/api/admin/auth")
public class AdminAuthController {

    private final AdminAuthService authService;

    /**
     * Creates the controller.
     *
     * @param authService auth service
     */
    public AdminAuthController(AdminAuthService authService) {
        this.authService = authService;
    }

    /**
     * Authenticates an admin and issues a token.
     *
     * @param request username and password
     * @param http    servlet request
     * @return the token and its lifetime
     */
    @PostMapping("/login")
    public AdminAuthService.LoginResult login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        return authService.login(request.username(), request.password(), http.getRemoteAddr());
    }

    /**
     * Logs the caller out by invalidating all of their tokens. Returns 200 with no body.
     *
     * @param principal authenticated admin (name is the admin id)
     * @param http      servlet request
     * @return empty 200
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(Principal principal, HttpServletRequest http) {
        authService.logout(UUID.fromString(principal.getName()), http.getRemoteAddr());
        return ResponseEntity.ok().build();
    }

    /**
     * Login request. toString is redacted so the password cannot leak through accidental logging.
     *
     * @param username admin username
     * @param password admin password
     */
    public record LoginRequest(@NotBlank @Size(max = 255) String username,
                               @NotBlank @Size(max = 1000) String password) {

        @Override
        public String toString() {
            return "LoginRequest[username=" + username + ", password=" + AdminAuthConstants.REDACTED + "]";
        }
    }
}