package com.saswat.paygrid.payment_service.processor.strategy;

import com.saswat.paygrid.common_lib.dto.PaymentProcessorRequest;
import com.saswat.paygrid.common_lib.dto.PaymentProcessorResponse;
import com.saswat.paygrid.common_lib.util.RandomizerUtil;
import com.saswat.paygrid.payment_service.processor.PaymentProcessor;
import org.springframework.stereotype.Component;

@Component
public class NetBankingPaymentProcessor implements PaymentProcessor {

    @Override
    public PaymentProcessorResponse charge(PaymentProcessorRequest request) {

        final String BANK_CODE_FAIL = "BANK_CODE_FAIL";

        String bankCode = request.methodDetails() != null ?
                request.methodDetails().get("bank").toString()  : null;

        if (BANK_CODE_FAIL.equals(bankCode)) {
            return new PaymentProcessorResponse.Failure("BANK_REJECTED",
                    "Bank rejected the transaction registration");
        }

        String processorRef = "NBK_PROCESSOR_" + RandomizerUtil.randomBase64(16);

        String redirectRef = "http://REDIRECT_BANK.com/"+ processorRef;

        return new PaymentProcessorResponse.Pending(processorRef);
    }
}
