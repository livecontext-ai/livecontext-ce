package com.apimarketplace.orchestrator.services.persistence;

import com.apimarketplace.common.storage.service.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * LC-066 (review findings r5-3 / r5-4): WHEN a run became restricted, and WHICH epochs hold the
 * restricted data. A showcase captured before the run's first restricted payload, or the showcase
 * of an epoch with no restricted row, holds none of it.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StepPayloadService - restricted since / restricted epoch (LC-066)")
class StepPayloadServiceRestrictedSinceTest {

    @Mock private StorageService storageService;
    @Mock private OutputSchemaMapper outputSchemaMapper;

    private StepPayloadService service;

    @BeforeEach
    void setUp() {
        service = new StepPayloadService(storageService, outputSchemaMapper);
    }

    @Test
    @DisplayName("an epoch with a restricted row (or an epoch-0 row, the column default) is restricted")
    void epochWithRestrictedRowIsRestricted() {
        when(storageService.runEpochsHoldRestrictedData("run-1", Set.of(2, 0))).thenReturn(true);

        assertThat(service.isRunEpochRestrictedStrict("run-1", 2)).isTrue();
    }

    @Test
    @DisplayName("an earlier epoch of a run restricted later (no restricted row in it) is not restricted")
    void cleanEarlierEpochIsNotRestricted() {
        when(storageService.runEpochsHoldRestrictedData("run-1", Set.of(1, 0))).thenReturn(false);

        assertThat(service.isRunEpochRestrictedStrict("run-1", 1)).isFalse();
    }

    @Test
    @DisplayName("epoch 0 asks for epoch 0 alone (no duplicate-element crash)")
    void epochZeroIsAskedOnce() {
        when(storageService.runEpochsHoldRestrictedData("run-1", Set.of(0))).thenReturn(true);

        assertThat(service.isRunEpochRestrictedStrict("run-1", 0)).isTrue();
    }

    @Test
    @DisplayName("a run marked restricted by a fire in flight, with no restricted row yet, cannot be scoped: restricted")
    void markedRunWithoutRowsIsRestrictedForEveryEpoch() {
        service.markRunRestricted("run-marked");
        when(storageService.runEpochsHoldRestrictedData("run-marked", Set.of(4, 0))).thenReturn(false);
        when(storageService.runHoldsRestrictedData("run-marked")).thenReturn(false);

        assertThat(service.isRunEpochRestrictedStrict("run-marked", 4)).isTrue();
    }

    @Test
    @DisplayName("LC-066 retention: a run whose restricted rows were ALL purged is still restricted (durable record)")
    void fullyPurgedRunStaysRestricted() {
        when(storageService.runHoldsRestrictedData("run-purged")).thenReturn(false);
        when(storageService.runHasRecordedRestriction("run-purged")).thenReturn(true);

        assertThat(service.isRunRestrictedStrict("run-purged")).isTrue();
        assertThat(service.isRunRestrictedStrict("run-never")).isFalse();
    }

    @Test
    @DisplayName("LC-066 retention: once a purged run is known restricted, its epochs are judged by their own rows")
    void purgedRunEpochsJudgedByTheirRows() {
        when(storageService.runHasRecordedRestriction("run-purged")).thenReturn(true);
        assertThat(service.isRunRestrictedStrict("run-purged")).isTrue(); // remembered in this JVM
        when(storageService.runEpochsHoldRestrictedData("run-purged", Set.of(3, 0))).thenReturn(false);

        assertThat(service.isRunEpochRestrictedStrict("run-purged", 3)).isFalse();
    }

    @Test
    @DisplayName("a lookup failure propagates (the capture fails, it never freezes an epoch of unknown class)")
    void lookupFailurePropagates() {
        when(storageService.runEpochsHoldRestrictedData("run-1", Set.of(1, 0)))
                .thenThrow(new IllegalStateException("storage down"));

        assertThatThrownBy(() -> service.isRunEpochRestrictedStrict("run-1", 1))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("the moment of the run's first restricted payload comes from its storage rows")
    void firstRestrictedPayloadAtComesFromStorage() {
        Instant first = Instant.parse("2026-09-01T08:00:00Z");
        when(storageService.firstRestrictedDataAt("run-1")).thenReturn(Optional.of(first));

        assertThat(service.firstRestrictedPayloadAt("run-1")).contains(first);
        assertThat(service.firstRestrictedPayloadAt(" ")).isEmpty();
    }
}
