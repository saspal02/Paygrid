package com.saswat.paygrid.payment_service.processor;

import com.saswat.paygrid.common_lib.dto.PaymentProcessorRequest;
import com.saswat.paygrid.common_lib.dto.PaymentProcessorResponse;
import com.saswat.paygrid.common_lib.enums.PaymentMethod;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class PaymentProcessorRouter {

    private final Map<PaymentMethod, PaymentProcessor> paymentProcessors;

    public PaymentProcessorResponse charge(PaymentProcessorRequest request) {
        PaymentProcessor processor = paymentProcessors.get(request.method());
        if (processor == null) {
            throw new IllegalArgumentException("No Payment processor registered for method " + request.method());

        }

        return processor.charge(request);

    }
}
