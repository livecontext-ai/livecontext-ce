package com.apimarketplace.orchestrator.controllers.internal;

import com.apimarketplace.orchestrator.services.publication.ShowcaseSnapshotBuilder;
import com.apimarketplace.orchestrator.services.publication.ShowcaseSnapshotBuilder.CaptureOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("InternalPublicationSupportController full-snapshot: which 404 says what")
class InternalPublicationSupportControllerFullSnapshotTest {

    @Mock private ShowcaseSnapshotBuilder showcaseSnapshotBuilder;

    private InternalPublicationSupportController controller;

    @BeforeEach
    void setUp() {
        controller = new InternalPublicationSupportController(
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, showcaseSnapshotBuilder, null, null, null);
    }

    @Test
    @DisplayName("Bug A7: a missing epoch is a 404 whose body names EPOCH_NOT_FOUND and the epoch")
    void missingEpochIsNamed() {
        when(showcaseSnapshotBuilder.captureWithOutcome("run-1", "tenant-1", null, 7))
                .thenReturn(new CaptureOutcome(Optional.empty(), true));

        ResponseEntity<?> response = controller.getFullShowcaseSnapshot("run-1", "tenant-1", null, 7);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isEqualTo(Map.of(
                "error", InternalPublicationSupportController.EPOCH_NOT_FOUND, "epoch", 7));
    }

    @Test
    @DisplayName("A missing or out-of-scope run stays a bodiless 404 (no existence oracle)")
    void missingRunStaysBodiless() {
        when(showcaseSnapshotBuilder.captureWithOutcome("run-x", "tenant-1", null, 7))
                .thenReturn(new CaptureOutcome(Optional.empty(), false));

        ResponseEntity<?> response = controller.getFullShowcaseSnapshot("run-x", "tenant-1", null, 7);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNull();
    }

    @Test
    @DisplayName("A captured snapshot is returned as 200")
    void capturedSnapshotIsOk() {
        Map<String, Object> snapshot = Map.of("version", 1);
        when(showcaseSnapshotBuilder.captureWithOutcome("run-1", "tenant-1", "org-1", null))
                .thenReturn(new CaptureOutcome(Optional.of(snapshot), false));

        ResponseEntity<?> response = controller.getFullShowcaseSnapshot("run-1", "tenant-1", "org-1", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(snapshot);
    }
}
