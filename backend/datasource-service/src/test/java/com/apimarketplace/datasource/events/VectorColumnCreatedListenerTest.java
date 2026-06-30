package com.apimarketplace.datasource.events;

import com.apimarketplace.datasource.crud.repository.VectorRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * {@link VectorColumnCreatedListener} - the post-commit HNSW index builder.
 * Wired for the first time here: the index-creation code existed since V75
 * but had no caller, so every similarity search sequential-scanned.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VectorColumnCreatedListener")
class VectorColumnCreatedListenerTest {

    @Mock
    private VectorRepository vectorRepository;

    @Test
    @DisplayName("builds the HNSW index with the event's datasource, dimension and metric")
    void buildsIndexFromEvent() {
        new VectorColumnCreatedListener(vectorRepository)
                .onVectorColumnCreated(new VectorColumnCreatedEvent(42L, 1536, "cosine"));

        verify(vectorRepository).createHnswIndex(42L, 1536, "cosine");
    }

    @Test
    @DisplayName("swallows index-build failures - similarity search must keep working via seq scan")
    void swallowsBuildFailures() {
        doThrow(new IllegalStateException("mixed dimensions on datasource"))
                .when(vectorRepository).createHnswIndex(7L, 768, "l2");

        assertThatCode(() -> new VectorColumnCreatedListener(vectorRepository)
                .onVectorColumnCreated(new VectorColumnCreatedEvent(7L, 768, "l2")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("REGRESSION: listener runs without a transaction too (fallbackExecution) - table creation publishes outside any tx and the default silently dropped the event")
    void listenerHasFallbackExecution() throws Exception {
        var annotation = VectorColumnCreatedListener.class
                .getMethod("onVectorColumnCreated", VectorColumnCreatedEvent.class)
                .getAnnotation(org.springframework.transaction.event.TransactionalEventListener.class);

        org.assertj.core.api.Assertions.assertThat(annotation.fallbackExecution())
                .as("createDataSource publishes with NO active transaction; without fallbackExecution "
                        + "the HNSW build event is dropped and the index is never created (caught live in CE e2e)")
                .isTrue();
        org.assertj.core.api.Assertions.assertThat(annotation.phase())
                .isEqualTo(org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT);
    }
}
