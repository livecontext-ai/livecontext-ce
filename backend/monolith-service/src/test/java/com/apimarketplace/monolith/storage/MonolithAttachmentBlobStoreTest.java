package com.apimarketplace.monolith.storage;

import com.apimarketplace.conversation.service.attachment.AttachmentBlobStore;
import com.apimarketplace.storage.domain.FileRef;
import com.apimarketplace.storage.service.file.FileStorageService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.InputStream;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("MonolithAttachmentBlobStore (CE monolith, in-process)")
@ExtendWith(MockitoExtension.class)
class MonolithAttachmentBlobStoreTest {

    @Mock
    private FileStorageService storageFileStorageService;

    private MonolithAttachmentBlobStore store() {
        return new MonolithAttachmentBlobStore(storageFileStorageService);
    }

    @Test
    @DisplayName("upload delegates to in-process uploadGeneric(category=chat) and returns the row id + s3 key")
    void uploadDelegatesInProcess() {
        byte[] bytes = new byte[]{1, 2, 3, 4};
        when(storageFileStorageService.uploadGeneric(eq("user-1"), eq("chat"), eq("p.png"), eq("image/png"),
                any(InputStream.class), anyLong()))
                .thenReturn(FileRef.of("user-1/general/chat/p.png", "p.png", "image/png", 4L, "row-id-9"));

        AttachmentBlobStore.BlobRef ref = store().upload("user-1", "org-3", "p.png", "image/png", bytes);

        assertThat(ref).isNotNull();
        assertThat(ref.storageId()).isEqualTo("row-id-9");
        assertThat(ref.s3Key()).isEqualTo("user-1/general/chat/p.png");

        // The whole byte buffer is streamed with its exact length.
        ArgumentCaptor<Long> size = ArgumentCaptor.forClass(Long.class);
        verify(storageFileStorageService).uploadGeneric(eq("user-1"), eq("chat"), eq("p.png"), eq("image/png"),
                any(InputStream.class), size.capture());
        assertThat(size.getValue()).isEqualTo(4L);
    }

    @Test
    @DisplayName("upload returns null when the in-process index yields no row id")
    void uploadReturnsNullWhenNoId() {
        when(storageFileStorageService.uploadGeneric(any(), any(), any(), any(), any(InputStream.class), anyLong()))
                .thenReturn(FileRef.of("user-1/general/chat/p.png", "p.png", "image/png", 1L));

        assertThat(store().upload("user-1", "org-3", "p.png", "image/png", new byte[]{1})).isNull();
    }

    @Test
    @DisplayName("download reads bytes in-process by key")
    void downloadInProcess() {
        byte[] bytes = new byte[]{5, 6};
        when(storageFileStorageService.download("owner-1/general/chat/x.png")).thenReturn(Optional.of(bytes));

        assertThat(store().download("owner-1", "owner-1/general/chat/x.png")).contains(bytes);
    }

    @Test
    @DisplayName("download swallows a transient object-storage error to empty (matches the HTTP impl) "
            + "so one bad attachment never aborts a whole message load")
    void downloadSwallowsTransientError() {
        when(storageFileStorageService.download("owner-1/general/chat/x.png"))
                .thenThrow(new RuntimeException("S3 timeout"));

        assertThat(store().download("owner-1", "owner-1/general/chat/x.png")).isEmpty();
    }
}
