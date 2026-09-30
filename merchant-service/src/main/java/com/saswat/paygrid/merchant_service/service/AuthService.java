package com.saswat.paygrid.merchant_service.service;

import com.saswat.paygrid.merchant_service.dto.request.LoginRequest;
import com.saswat.paygrid.merchant_service.dto.request.MerchantSignupRequest;
import com.saswat.paygrid.merchant_service.dto.response.LoginResponse;
import com.saswat.paygrid.merchant_service.dto.response.MerchantResponse;

public interface AuthService {
    MerchantResponse signUp(MerchantSignupRequest request);

    LoginResponse login(LoginRequest request);
}
