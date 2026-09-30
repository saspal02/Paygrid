package com.saswat.paygrid.merchant_service.controller;

import com.saswat.paygrid.merchant_service.dto.request.LoginRequest;
import com.saswat.paygrid.merchant_service.dto.request.MerchantSignupRequest;
import com.saswat.paygrid.merchant_service.dto.response.LoginResponse;
import com.saswat.paygrid.merchant_service.dto.response.MerchantResponse;
import com.saswat.paygrid.merchant_service.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/signup")
    public ResponseEntity<MerchantResponse> signUp(@RequestBody @Valid MerchantSignupRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                authService.signUp(request)
        );

    }
    
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@RequestBody @Valid LoginRequest request) {
        return ResponseEntity.status(HttpStatus.OK).body(
                authService.login(request)
        );
    }

}
