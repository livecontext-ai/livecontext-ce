package com.apimarketplace.orchestrator.services.file;

import com.apimarketplace.storage.client.StorageClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The remote internal delete route authorizes by KEY-OWNER prefix, so the owner-aware delete must
 * forward the owner tenant as X-User-ID instead of inheriting the interface default (key-only, no
 * identity, refused with 403 off a request thread).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StorageClientAdapter owner-aware delete")
class StorageClientAdapterOwnerDeleteTest {

    @Mock private StorageClient storageClient;

    private StorageClientAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new StorageClientAdapter(storageClient);
    }

    @Test
    @DisplayName("forwards the owner tenant to the storage client and returns its result")
    void forwardsOwnerTenant() {
        when(storageClient.delete("tenant-1", "tenant-1/wf/run/step/a.txt")).thenReturn(true);

        assertThat(adapter.delete("tenant-1", "tenant-1/wf/run/step/a.txt")).isTrue();
        verify(storageClient).delete("tenant-1", "tenant-1/wf/run/step/a.txt");
    }

    @Test
    @DisplayName("reports false when the storage client refuses the delete")
    void reportsRefusal() {
        when(storageClient.delete("tenant-1", "tenant-1/wf/run/step/a.txt")).thenReturn(false);

        assertThat(adapter.delete("tenant-1", "tenant-1/wf/run/step/a.txt")).isFalse();
    }
}
