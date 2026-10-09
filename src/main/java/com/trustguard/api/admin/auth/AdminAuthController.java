package com.trustguard.api.admin.auth;

import java.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Admin auth endpoints. Exception handlers are local on purpose: an unhandled exception would trigger
 * an /error dispatch that the default-deny chain (RULING 20) rejects, hiding the real status.
 * Passwords over 72 bytes but within the DTO cap get the generic 401 (N7), not a revealing 400.
 */
@RestController
@RequestMapping("/api/admin/auth")
public class AdminAuthController {
    private static final int HTTP_BAD_REQUEST= 400;
    private static final String REDACTED= "[REDACTED]";

    private final AdminAuthService authService;
    /**
     * creates the controller
     * @param authService auth Service
     */
    public AdminAuthController(AdminAuthService authService){
        this.authService= authService;
    }
    @PostMapping("/login")
    public AdminAuthService.LoginResult login(@Valid @RequestBody LoginRequest request, HttpServletRequest http){
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
    public ResponseEntity<Void> logout(Principal principal, HttpServletRequest http){
        authService.logout(UUID.fromString(principal.getName()), http.getRemoteAddr());
        return ResponseEntity.ok().build();
    }
    @ExceptionHandler(AdminAuthenticationException.class)
    public ResponseEntity<Map<String, Object>> handleAuthentication(AdminAuthenticationException e){
        return ResponseEntity.status(e.httpStatus()).body(e.toResponseBody());
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e){
        List<Map<String, String>> details= new ArrayList<>();
        for(FieldError fieldError : e.getBindingResult().getFieldErrors()){
            Map<String, String> detail= new LinkedHashMap<>();
            detail.put("field", fieldError.getField());
            detail.put("rejected", REDACTED);
            detail.put("message", fieldError.getDefaultMessage());
            details.add(detail);
        }
        return badRequest("Request validation failed.", details);
    }
    /**
     * Maps unreadable request bodies.
     *
     * @param e the parse failure
     * @return JSON 400 response
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException e){
        return badRequest("Malformed request body.", List.of());
    }
    private static ResponseEntity<Map<String, Object>> badRequest(String message, List<Map<String, String>> details){
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", "VALIDATION_FAILED");
        error.put("message", message);
        error.put("retryable", false);
        error.put("details", details);
        return ResponseEntity.status(HTTP_BAD_REQUEST).body(Map.of("error", error));
    }
    /**
     * Login request. toString is redacted so the password cannot leak through accidental logging.
     *
     * @param username admin username
     * @param password admin password
     */
    public record LoginRequest(@NotBlank @Size(max =  255) String username,
                               @NotBlank @Size(max = 1000) String password){
        @Override
        public String toString(){
            return "LoginRequest[username="+ username+ ", password="+ REDACTED+ "]";
        }
    }
}
