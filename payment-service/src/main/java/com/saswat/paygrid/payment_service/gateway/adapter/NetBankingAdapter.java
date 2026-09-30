package com.saswat.paygrid.payment_service.gateway.adapter;

import com.saswat.paygrid.common_lib.dto.PaymentProcessorRequest;
import com.saswat.paygrid.common_lib.dto.PaymentProcessorResponse;
import com.saswat.paygrid.common_lib.enums.PaymentMethod;
import com.saswat.paygrid.payment_service.gateway.PaymentAdapter;
import com.saswat.paygrid.payment_service.gateway.dto.PaymentRequest;
import com.saswat.paygrid.payment_service.gateway.dto.PaymentResult;
import com.saswat.paygrid.payment_service.processor.PaymentProcessorRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component("NETBANKING")
@Slf4j
@RequiredArgsConstructor
public class NetBankingAdapter implements PaymentAdapter {

    private final PaymentProcessorRouter paymentProcessorRouter;

    @Override
    public PaymentResult initiate(PaymentRequest request) {
        log.info("Initiate Payment with NetBankingAdapter, paymentId: {}", request.paymentId());

        try {
            PaymentProcessorRequest paymentProcessorRequest = PaymentProcessorRequest.nonCard(
                    request.paymentId(),
                    PaymentMethod.NETBANKING,
                    request.amount(),
                    request.methodDetails()
            );

            PaymentProcessorResponse paymentProcessorResponse =
                    paymentProcessorRouter.charge(paymentProcessorRequest);

            return switch (paymentProcessorResponse) {
                case PaymentProcessorResponse.Failure failure ->
                        new PaymentResult.Failure(
                                failure.errorCode(),
                                failure.errorDescription()
                        );

                case PaymentProcessorResponse.Pending pending ->
                        new PaymentResult.Pending(
                                pending.processorReference()
                        );

                case PaymentProcessorResponse.Success success ->
                        new PaymentResult.Success(
                                success.bankReference()
                        );
            };

        } catch (Exception e) {
            log.warn("NetBanking failed, paymentId: {}", request.paymentId());
            return new PaymentResult.Failure("NBK_FAILED", e.getMessage());
        }
    }

    @Override
    public PaymentResult capture(UUID paymentId) {
        return new PaymentResult.Success("NBK_REF");
    }
}