package com.apimarketplace.auth.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "personal_offer_reversed_invoice")
@Getter @Setter
public class PersonalOfferReversedInvoice {
    @Id @Column(name = "invoice_id", length = 255)
    private String invoiceId;
    @Column(nullable = false, length = 32)
    private String reason;
    @Column(name = "reversed_at", insertable = false, updatable = false)
    private Instant reversedAt;
}
