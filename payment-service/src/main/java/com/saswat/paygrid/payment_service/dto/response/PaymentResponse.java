package com.saswat.paygrid.payment_service.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.saswat.paygrid.common_lib.entity.Money;
import com.saswat.paygrid.common_lib.enums.PaymentMethod;
import com.saswat.paygrid.common_lib.enums.PaymentStatus;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PaymentResponse(
        UUID id,
        UUID orderId,
        UUID merchantId,
        Money amount,
        PaymentStatus status,
        PaymentMethod method,
        Map<String, Object> methodDetails,
        String errorCode,
        String errorDescription,
        LocalDateTime capturedAt,
        LocalDateTime createdAt


) {
}
