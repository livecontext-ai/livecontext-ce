package com.apimarketplace.auth.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "personal_offer_first_paid_purchase")
@Getter @Setter
public class PersonalOfferFirstPaidPurchase {
    @Id @Column(name = "user_id")
    private Long userId;
    @Column(name = "invoice_id", length = 255)
    private String invoiceId;
    @Column(name = "provider_subscription_id", length = 255)
    private String providerSubscriptionId;
    @Column(name = "paid_at")
    private Instant paidAt;
    @Column(nullable = false, length = 16)
    private String status;
    @Column(name = "verified_at")
    private Instant verifiedAt;
}
