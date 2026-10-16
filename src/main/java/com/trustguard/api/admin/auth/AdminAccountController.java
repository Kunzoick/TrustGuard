package com.trustguard.api.admin.auth;
import java.security.Principal;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Admin account lifecycle endpoints: change own password and unlock another admin. Both are authenticated by
 * the admin chain. The caller id is the principal name, never a request field.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminAccountController {
    private final AdminAccountService accountService;

    /**
     * Creates the controller.
     *
     * @param accountService account lifecycle service
     */
    public AdminAccountController(AdminAccountService accountService) {
        this.accountService = accountService;
    }

    /**
     * Changes the caller's own password. The caller's token stops working afterwards.
     *
     * @param request   current and new password
     * @param principal authenticated admin (name is the admin id)
     * @param http      servlet request
     * @return empty 200
     */
    @PostMapping("/auth/password")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody PasswordChangeRequest request,
                                               Principal principal, HttpServletRequest http) {
        accountService.changePassword(UUID.fromString(principal.getName()), request.currentPassword(),
                request.newPassword(), http.getRemoteAddr());
        return ResponseEntity.ok().build();
    }

    /**
     * Unlocks another admin. Any authenticated admin may do this. A no-op when the target is not locked.
     *
     * @param adminId   admin to unlock
     * @param principal authenticated admin (name is the admin id)
     * @param http      servlet request
     * @return empty 200
     */
    @PostMapping("/admins/{adminId}/unlock")
    public ResponseEntity<Void> unlock(@PathVariable("adminId") UUID adminId, Principal principal,
                                       HttpServletRequest http) {
        accountService.unlock(UUID.fromString(principal.getName()), adminId, http.getRemoteAddr());
        return ResponseEntity.ok().build();
    }

    /**
     * Password change request. toString is redacted so passwords cannot leak through accidental logging.
     *
     * @param currentPassword the caller's current password
     * @param newPassword     the proposed new password
     */
    public record PasswordChangeRequest(
            @NotBlank @Size(max = AdminAuthConstants.MAX_PASSWORD_INPUT_LENGTH) String currentPassword,
            @NotBlank @Size(max = AdminAuthConstants.MAX_PASSWORD_INPUT_LENGTH) String newPassword) {

        @Override
        public String toString() {
            return "PasswordChangeRequest[currentPassword=" + AdminAuthConstants.REDACTED
                    + ", newPassword=" + AdminAuthConstants.REDACTED + "]";
        }
    }
}
