package com.apimarketplace.conversation.service.attachment;

import com.apimarketplace.storage.client.StorageClient;
import com.apimarketplace.storage.client.dto.FileRefDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@DisplayName("HttpAttachmentBlobStore (microservice)")
@ExtendWith(MockitoExtension.class)
class HttpAttachmentBlobStoreTest {

    @Mock
    private StorageClient storageClient;

    private HttpAttachmentBlobStore store() {
        return new HttpAttachmentBlobStore(storageClient);
    }

    @Test
    @DisplayName("upload routes bytes through genericUpload(category=chat) org-aware and returns the row id + s3 key")
    void uploadRoutesThroughGenericUpload() {
        byte[] bytes = new byte[]{1, 2, 3};
        when(storageClient.genericUpload("user-1", "chat", "p.png", "image/png", bytes, "org-9"))
                .thenReturn(FileRefDto.of("user-1/general/chat/p.png", "p.png", "image/png", 3L, "row-id-1"));

        AttachmentBlobStore.BlobRef ref = store().upload("user-1", "org-9", "p.png", "image/png", bytes);

        assertThat(ref).isNotNull();
        assertThat(ref.storageId()).isEqualTo("row-id-1");
        assertThat(ref.s3Key()).isEqualTo("user-1/general/chat/p.png");
        assertThat(ref.size()).isEqualTo(3L);
    }

    @Test
    @DisplayName("upload returns null when storage-service responds without a row id (failure)")
    void uploadReturnsNullOnFailure() {
        byte[] bytes = new byte[]{1};
        when(storageClient.genericUpload("user-1", "chat", "p.png", "image/png", bytes, null))
                .thenReturn(null);

        assertThat(store().upload("user-1", null, "p.png", "image/png", bytes)).isNull();
    }

    @Test
    @DisplayName("upload returns null when the response carries no id (only path)")
    void uploadReturnsNullWhenNoId() {
        byte[] bytes = new byte[]{1};
        when(storageClient.genericUpload("user-1", "chat", "p.png", "image/png", bytes, null))
                .thenReturn(FileRefDto.of("user-1/general/chat/p.png", "p.png", "image/png", 1L));

        assertThat(store().upload("user-1", null, "p.png", "image/png", bytes)).isNull();
    }

    @Test
    @DisplayName("download fetches bytes by key under the owner tenant")
    void downloadFetchesByKey() {
        byte[] bytes = new byte[]{7, 8};
        when(storageClient.download("owner-2", "owner-2/general/chat/x.png")).thenReturn(bytes);

        Optional<byte[]> result = store().download("owner-2", "owner-2/general/chat/x.png");

        assertThat(result).contains(bytes);
    }

    @Test
    @DisplayName("download is empty when storage returns no bytes")
    void downloadEmptyWhenMissing() {
        when(storageClient.download("owner-2", "owner-2/general/chat/x.png")).thenReturn(null);

        assertThat(store().download("owner-2", "owner-2/general/chat/x.png")).isEmpty();
    }
}
