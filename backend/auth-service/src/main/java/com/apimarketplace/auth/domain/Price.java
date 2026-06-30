package com.apimarketplace.auth.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Entity
@Table(name = "price")
public class Price {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id", nullable = false)
    private Plan plan;

    @NotBlank
    @Column(nullable = false)
    private String cadence; // monthly, yearly, payg

    @NotBlank
    @Column(nullable = false)
    private String currency = "usd";

    @NotNull
    @Column(name = "amount_cents", nullable = false)
    private Integer amountCents = 0; // 0 pour FREE/PAYG

    @NotBlank
    @Column(nullable = false)
    private String provider = "stripe";

    @Column(name = "provider_price_id", unique = true)
    private String providerPriceId; // price_xxx (optionnel pour FREE)

    // Constructeurs
    public Price() {}

    public Price(Plan plan, String cadence, Integer amountCents) {
        this.plan = plan;
        this.cadence = cadence;
        this.amountCents = amountCents;
    }

    // Getters et Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Plan getPlan() {
        return plan;
    }

    public void setPlan(Plan plan) {
        this.plan = plan;
    }

    public String getCadence() {
        return cadence;
    }

    public void setCadence(String cadence) {
        this.cadence = cadence;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public Integer getAmountCents() {
        return amountCents;
    }

    public void setAmountCents(Integer amountCents) {
        this.amountCents = amountCents;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getProviderPriceId() {
        return providerPriceId;
    }

    public void setProviderPriceId(String providerPriceId) {
        this.providerPriceId = providerPriceId;
    }

    // Methodes utilitaires
    public boolean isFree() {
        return amountCents == 0;
    }

    public boolean isPayg() {
        return "payg".equals(cadence);
    }
}
