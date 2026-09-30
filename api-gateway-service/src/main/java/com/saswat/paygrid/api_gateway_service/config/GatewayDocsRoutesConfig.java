package com.saswat.paygrid.api_gateway_service.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.stripPrefix;
import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.uri;
import static org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http;

@Configuration
public class GatewayDocsRoutesConfig {

    private static final int STRIP_PREFIX_SEGMENTS = 1;

    @Bean
    public RouterFunction<ServerResponse> docsRoutes(
            @Value("${MERCHANT_SERVICE_URI:http://merchant-service}") String merchantUri,
            @Value("${PAYMENT_SERVICE_URI:http://payment-service}") String paymentUri,
            @Value("${VAULT_SERVICE_URI:http://vault-service}") String vaultUri,
            @Value("${OPERATIONS_SERVICE_URI:http://operations-service}") String operationsUri) {

        return route("merchant-service-docs")
                .GET("/merchant-service/v3/api-docs", http())
                .before(uri(merchantUri))
                .before(stripPrefix(STRIP_PREFIX_SEGMENTS))
                .build()

                .and(route("payment-service-docs")
                        .GET("/payment-service/v3/api-docs", http())
                        .before(uri(paymentUri))
                        .before(stripPrefix(STRIP_PREFIX_SEGMENTS))
                        .build())

                .and(route("vault-service-docs")
                        .GET("/vault-service/v3/api-docs", http())
                        .before(uri(vaultUri))
                        .before(stripPrefix(STRIP_PREFIX_SEGMENTS))
                        .build())

                .and(route("operations-service-docs")
                        .GET("/operations-service/v3/api-docs", http())
                        .before(uri(operationsUri))
                        .before(stripPrefix(STRIP_PREFIX_SEGMENTS))
                        .build());
    }
}
