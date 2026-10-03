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

    @Test
    @DisplayName("LC-066: the run-restricted lookup answers the builder's verdict")
    void runRestrictedAnswersTheVerdict() {
        when(showcaseSnapshotBuilder.sourceRunExists("run-gmail")).thenReturn(true);
        when(showcaseSnapshotBuilder.sourceRunExists("run-plain")).thenReturn(true);
        when(showcaseSnapshotBuilder.isSourceRunRestricted("run-gmail")).thenReturn(true);
        when(showcaseSnapshotBuilder.isSourceRunRestricted("run-plain")).thenReturn(false);

        assertThat(controller.isRunRestricted("run-gmail").getBody())
                .isEqualTo(Map.of("runExists", true, "restricted", true));
        assertThat(controller.isRunRestricted("run-plain").getBody())
                .isEqualTo(Map.of("runExists", true, "restricted", false));
    }

    @Test
    @DisplayName("LC-066 r5-3: a restricted run also says when its first restricted payload was written")
    void runRestrictedAnswersWhenItBecameRestricted() {
        java.time.Instant first = java.time.Instant.parse("2026-09-20T10:15:30Z");
        when(showcaseSnapshotBuilder.sourceRunExists("run-gmail")).thenReturn(true);
        when(showcaseSnapshotBuilder.isSourceRunRestricted("run-gmail")).thenReturn(true);
        when(showcaseSnapshotBuilder.sourceRunFirstRestrictedAt("run-gmail")).thenReturn(Optional.of(first));
        when(showcaseSnapshotBuilder.sourceRunExists("run-plain")).thenReturn(true);
        when(showcaseSnapshotBuilder.isSourceRunRestricted("run-plain")).thenReturn(false);

        assertThat(controller.isRunRestricted("run-gmail").getBody()).isEqualTo(Map.of(
                "runExists", true, "restricted", true, "firstRestrictedAt", "2026-09-20T10:15:30Z"));
        // A clean run is never asked when it became restricted.
        assertThat(controller.isRunRestricted("run-plain").getBody())
                .isEqualTo(Map.of("runExists", true, "restricted", false));
        org.mockito.Mockito.verify(showcaseSnapshotBuilder, org.mockito.Mockito.never())
                .sourceRunFirstRestrictedAt("run-plain");
    }

    @Test
    @DisplayName("LC-066 regression: a deleted run answers runExists=false and no restricted verdict (its payloads are gone too)")
    void runRestrictedLookupOfADeletedRunSaysSo() {
        when(showcaseSnapshotBuilder.sourceRunExists("run-gone")).thenReturn(false);

        ResponseEntity<?> response = controller.isRunRestricted("run-gone");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(Map.of("runExists", false));
        org.mockito.Mockito.verify(showcaseSnapshotBuilder, org.mockito.Mockito.never()).isSourceRunRestricted("run-gone");
    }

    @Test
    @DisplayName("LC-066: a failed run-restricted lookup is a 500, never restricted=false")
    void runRestrictedLookupFailureIsAServerError() {
        when(showcaseSnapshotBuilder.sourceRunExists("run-x")).thenReturn(true);
        when(showcaseSnapshotBuilder.isSourceRunRestricted("run-x")).thenThrow(new IllegalStateException("db down"));

        ResponseEntity<?> response = controller.isRunRestricted("run-x");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotEqualTo(Map.of("restricted", false));
    }
}
