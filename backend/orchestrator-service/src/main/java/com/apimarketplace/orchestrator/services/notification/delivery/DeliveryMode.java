package com.apimarketplace.orchestrator.services.notification.delivery;

import java.util.Optional;

/** Where one topic reaches a person, besides the bell (which always gets it). */
public enum DeliveryMode {
    OFF, EMAIL, CHANNEL, BOTH;

    public boolean wantsEmail() {
        return this == EMAIL || this == BOTH;
    }

    public boolean wantsChannel() {
        return this == CHANNEL || this == BOTH;
    }

    public static Optional<DeliveryMode> parse(String value) {
        if (value == null) return Optional.empty();
        for (DeliveryMode mode : values()) {
            if (mode.name().equalsIgnoreCase(value.trim())) return Optional.of(mode);
        }
        return Optional.empty();
    }
}
