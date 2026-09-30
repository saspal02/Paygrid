package com.saswat.paygrid.payment_service.controller;

import com.saswat.paygrid.common_lib.context.MerchantContext;
import com.saswat.paygrid.payment_service.dto.request.CreateOrderRequest;
import com.saswat.paygrid.payment_service.dto.response.OrderResponse;
import com.saswat.paygrid.payment_service.dto.response.PaymentResponse;
import com.saswat.paygrid.payment_service.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final MerchantContext merchantContext;

    private final OrderService orderService;

    @PostMapping
    public ResponseEntity<OrderResponse> create(@RequestBody @Valid CreateOrderRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(orderService.create(merchantContext.getMerchantId(), request));

    }

    @GetMapping("/{orderId}")
    public ResponseEntity<OrderResponse> getById(@PathVariable final UUID orderId) {
        return ResponseEntity.ok(orderService.getById(merchantContext.getMerchantId(), orderId));
    }

    @PostMapping("/{orderId}/cancel")
    public ResponseEntity<OrderResponse> cancel(@PathVariable final UUID orderId) {
        return ResponseEntity.ok(orderService.cancel(merchantContext.getMerchantId(), orderId));
    }

    @GetMapping("/{orderId}/payments")
    public ResponseEntity<List<PaymentResponse>> listPayments(@PathVariable final UUID orderId) {
        return ResponseEntity.ok(orderService.listPayments(merchantContext.getMerchantId(), orderId));
    }

}
