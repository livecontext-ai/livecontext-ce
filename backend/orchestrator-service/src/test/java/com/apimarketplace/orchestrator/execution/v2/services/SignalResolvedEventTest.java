package com.apimarketplace.orchestrator.execution.v2.services;

import com.apimarketplace.orchestrator.domain.execution.SignalWaitEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SignalResolvedEvent.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SignalResolvedEvent")
class SignalResolvedEventTest {

    @Mock
    private SignalWaitEntity mockSignal;

    @Test
    @DisplayName("should store source and resolved signal")
    void shouldStoreSourceAndSignal() {
        Object source = new Object();
        SignalResolvedEvent event = new SignalResolvedEvent(source, mockSignal);

        assertSame(source, event.getSource());
        assertSame(mockSignal, event.getResolvedSignal());
    }

    @Test
    @DisplayName("should extend ApplicationEvent")
    void shouldExtendApplicationEvent() {
        SignalResolvedEvent event = new SignalResolvedEvent(this, mockSignal);
        assertNotNull(event.getTimestamp());
    }
}
