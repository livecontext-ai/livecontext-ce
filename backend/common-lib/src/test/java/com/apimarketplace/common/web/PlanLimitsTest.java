package com.apimarketplace.common.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the {@link PlanLimits} constants holder contract. The {@code UNLIMITED}
 * value is consumed by frontend rendering and by 4 backend callsites - its
 * literal value (9999) is part of the wire contract.
 */
@DisplayName("PlanLimits")
class PlanLimitsTest {

    @Test
    @DisplayName("UNLIMITED is 9999 (frontend rendering contract - do not change without coordinating)")
    void unlimitedSentinelIs9999() {
        assertThat(PlanLimits.UNLIMITED).isEqualTo(9999);
    }

    @Test
    @DisplayName("Class is final and constructor is private (constants holder, no instantiation)")
    void classIsFinalAndConstructorPrivate() {
        assertThat(Modifier.isFinal(PlanLimits.class.getModifiers())).isTrue();

        Constructor<?>[] constructors = PlanLimits.class.getDeclaredConstructors();
        assertThat(constructors).hasSize(1);
        assertThat(Modifier.isPrivate(constructors[0].getModifiers())).isTrue();
    }
}
