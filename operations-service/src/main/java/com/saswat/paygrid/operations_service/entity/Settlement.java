package com.saswat.paygrid.operations_service.entity;

import com.saswat.paygrid.common_lib.entity.BaseEntity;
import com.saswat.paygrid.common_lib.entity.Money;
import com.saswat.paygrid.common_lib.enums.SettlementStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "settlement")
@Builder
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class Settlement extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID merchantId;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "amountUnits", column = @Column(name = "gross_amount_units", nullable = false)),
            @AttributeOverride(name = "currency", column = @Column(name = "gross_amount_currency", nullable = false))
    })
    private Money grossAmount;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "amountUnits", column = @Column(name = "refund_amount_units", nullable = false)),
            @AttributeOverride(name = "currency", column = @Column(name = "refund_amount_currency", nullable = false))
    })
    private Money refundAmount;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "amountUnits", column = @Column(name = "fee_amount_units", nullable = false)),
            @AttributeOverride(name = "currency", column = @Column(name = "fee_amount_currency", nullable = false))
    })
    private Money feeAmount;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "amountUnits", column = @Column(name = "gst_amount_units", nullable = false)),
            @AttributeOverride(name = "currency", column = @Column(name = "gst_amount_currency", nullable = false))
    })
    private Money gstAmount;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "amountUnits", column = @Column(name = "net_amount_units", nullable = false)),
            @AttributeOverride(name = "currency", column = @Column(name = "net_amount_currency", nullable = false))
    })
    private Money netAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SettlementStatus status;

    private String bankReference;

    private LocalDateTime processedAt;

    private String failureReason;


}
