package com.apimarketplace.auth.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Client de facturation (utilisateur ou organisation)
 */
@Entity
@Table(name = "billing_customer",
       indexes = {
           @Index(name = "idx_bc_user", columnList = "user_id", unique = true),
           @Index(name = "idx_bc_provider_customer", columnList = "provider_customer_id", unique = true)
       })
public class BillingCustomer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @NotBlank
    @Column(nullable = false)
    private String provider;

    @Column(name = "provider_customer_id", unique = true)
    private String providerCustomerId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    // Constructeurs
    public BillingCustomer() {}

    public BillingCustomer(User user, String provider) {
        this.user = user;
        this.provider = provider;
    }

    // Callbacks JPA
    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
    }

    // Getters et Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getProviderCustomerId() { return providerCustomerId; }
    public void setProviderCustomerId(String providerCustomerId) { this.providerCustomerId = providerCustomerId; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BillingCustomer that = (BillingCustomer) o;
        return Objects.equals(id, that.id) && Objects.equals(user, that.user);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, user);
    }

    @Override
    public String toString() {
        return "BillingCustomer{" +
                "id=" + id +
                ", userId=" + (user != null ? user.getId() : "null") +
                ", provider='" + provider + '\'' +
                '}';
    }
}
