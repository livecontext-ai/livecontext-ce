package com.apimarketplace.auth.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jakarta.persistence.PrePersist;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class PartnerOfferTest {

    @Test
    @DisplayName("regression: a new offer is stamped on insert, so the create response carries its date (not null)")
    void stampedOnInsert() {
        PartnerOffer offer = new PartnerOffer();
        Instant before = Instant.now();

        offer.prePersist();

        assertThat(offer.getCreatedAt()).isNotNull().isBetween(before, Instant.now());
    }

    @Test
    @DisplayName("regression: the stamp is the JPA insert callback, so the save path runs it (a plain method would not be)")
    void stampIsTheInsertCallback() throws Exception {
        assertThat(PartnerOffer.class.getDeclaredMethod("prePersist").isAnnotationPresent(PrePersist.class)).isTrue();
    }

    @Test
    @DisplayName("a date already set is kept")
    void keepsAGivenDate() {
        PartnerOffer offer = new PartnerOffer();
        Instant given = Instant.parse("2026-09-30T10:00:00Z");
        offer.setCreatedAt(given);

        offer.prePersist();

        assertThat(offer.getCreatedAt()).isEqualTo(given);
    }
}
