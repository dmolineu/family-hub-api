package com.familyhub.demo.controller;

import com.familyhub.demo.dto.*;
import com.familyhub.demo.service.AuthService;
import jakarta.validation.Valid;
import com.familyhub.demo.model.Family;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthService authService;


    @PostMapping("/register")
    public ResponseEntity<ApiResponse<AuthResponse>> register(@Valid @RequestBody RegisterRequest request) {
        AuthResponse response = authService.register(request);
        return ResponseEntity.ok().body(new ApiResponse<>(response, "Register successful"));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(@Valid @RequestBody LoginRequest loginRequest) {
        AuthResponse loginResponse = authService.login(loginRequest);
        return ResponseEntity.ok().body(new ApiResponse<>(loginResponse, "Login successful"));
    }

    /**
     * Changes the sign-in password. Needs a signed-in device and the current
     * password; every other device is signed out and this one gets a new token.
     */
    @PutMapping("/password")
    public ResponseEntity<ApiResponse<AuthResponse>> changePassword(
            @AuthenticationPrincipal Family family,
            @Valid @RequestBody ChangePasswordRequest request) {
        return ResponseEntity.ok(new ApiResponse<>(authService.changePassword(family, request), "Password changed"));
    }

    @GetMapping("/check-username")
    public ResponseEntity<ApiResponse<UsernameCheckResponse>> checkUsername(
            @RequestParam(name = "username", required = true) String username) {

       return ResponseEntity.ok(new ApiResponse<>(authService.checkUsername(username), "Username check"));
    }
}
