package com.saswat.paygrid.merchant_service.service.impl;

import com.saswat.paygrid.common_lib.cache.ApiKeyCache;
import com.saswat.paygrid.common_lib.exception.ResourceNotFoundException;
import com.saswat.paygrid.common_lib.util.RandomizerUtil;
import com.saswat.paygrid.merchant_service.dto.request.CreateApiKeyRequest;
import com.saswat.paygrid.merchant_service.dto.response.ApiKeyCreateResponse;
import com.saswat.paygrid.merchant_service.dto.response.ApiKeyResponse;
import com.saswat.paygrid.merchant_service.entity.ApiKey;
import com.saswat.paygrid.merchant_service.entity.Merchant;
import com.saswat.paygrid.merchant_service.mapper.ApiKeyMapper;
import com.saswat.paygrid.merchant_service.repository.ApiKeyRepository;
import com.saswat.paygrid.merchant_service.repository.MerchantRepository;
import com.saswat.paygrid.merchant_service.service.ApiKeyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class ApiKeyServiceImpl implements ApiKeyService {

    private final MerchantRepository merchantRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final ApiKeyMapper apiKeyMapper;
    private final PasswordEncoder passwordEncoder;
    private final ApiKeyCache apiKeyCache;


    @Override
    @Transactional
    public ApiKeyCreateResponse create(UUID merchantId, CreateApiKeyRequest request) {
        Merchant merchant = merchantRepository.findById(merchantId)
                .orElseThrow(() -> new ResourceNotFoundException("merchant", merchantId));

        String keyId = "rzp_" + request.environment().name().toLowerCase() + "_" + RandomizerUtil.randomBase64(24);
        String rawSecret = RandomizerUtil.randomBase64(40);

        ApiKey apiKey = ApiKey.builder()
                .merchant(merchant)
                .keyId(keyId)
                .keySecretHash(passwordEncoder.encode(rawSecret))
                .environment(request.environment())
                .build();

        apiKey = apiKeyRepository.save(apiKey);

        return new ApiKeyCreateResponse(apiKey.getId(), keyId, rawSecret, request.environment());

    }

    @Override
    public List<ApiKeyResponse> listByMerchant(UUID merchantId) {
        return apiKeyMapper.toResponseList(apiKeyRepository.findByMerchant_Id(merchantId));
    }

    @Override
    @Transactional
    public void revoke(UUID merchantId, UUID keyId) {
        ApiKey apikey = apiKeyRepository.findByIdAndMerchant_Id(keyId, merchantId)
                .orElseThrow(() -> new ResourceNotFoundException("ApiKey", keyId));

        apikey.setEnabled(false);
        apiKeyCache.evict(apikey.getKeyId());
    }

    @Override
    @Transactional
    public ApiKeyCreateResponse rotate(UUID merchantId, UUID keyId) {
        ApiKey apikey = apiKeyRepository.findByIdAndMerchant_Id(keyId, merchantId)
                .orElseThrow(() -> new ResourceNotFoundException("ApiKey", keyId));

        if (!apikey.isEnabled()) throw new RuntimeException("Cannot rotate a disabled key");

        String newRawSecret = RandomizerUtil.randomBase64(40);
        apikey.setPreviousKeySecretHash(apikey.getKeySecretHash());
        apikey.setKeySecretHash(passwordEncoder.encode(newRawSecret));
        apikey.setRotatedAt(LocalDateTime.now());
        apikey.setGracePeriodExpiresAt(LocalDateTime.now().plusHours(24));
        apiKeyRepository.save(apikey);

        apiKeyCache.evict(apikey.getKeyId());

        return new ApiKeyCreateResponse(apikey.getId(), apikey.getKeyId(),
                newRawSecret, apikey.getEnvironment());


    }
}
