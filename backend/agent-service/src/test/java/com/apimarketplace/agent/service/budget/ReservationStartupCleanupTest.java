package com.apimarketplace.agent.service.budget;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("ReservationStartupCleanup")
@ExtendWith(MockitoExtension.class)
class ReservationStartupCleanupTest {

    @Mock
    private BudgetReservationService budgetReservationService;

    @InjectMocks
    private ReservationStartupCleanup cleanup;

    @Test
    @DisplayName("calls clearAllReservations on ApplicationReadyEvent")
    void callsClearAllOnStartup() {
        when(budgetReservationService.clearAllReservations()).thenReturn(3);

        cleanup.clearLeakedReservationsOnStartup();

        verify(budgetReservationService).clearAllReservations();
    }

    @Test
    @DisplayName("handles zero-cleared case without error")
    void handlesZeroCleared() {
        when(budgetReservationService.clearAllReservations()).thenReturn(0);

        assertThatCode(() -> cleanup.clearLeakedReservationsOnStartup())
            .doesNotThrowAnyException();

        verify(budgetReservationService).clearAllReservations();
    }

    @Test
    @DisplayName("swallows exceptions - never fails startup")
    void swallowsExceptions() {
        doThrow(new RuntimeException("DB down"))
            .when(budgetReservationService).clearAllReservations();

        assertThatCode(() -> cleanup.clearLeakedReservationsOnStartup())
            .doesNotThrowAnyException();
    }
}
