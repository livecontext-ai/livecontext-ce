package com.apimarketplace.storage.service.file;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.ByteArrayInputStream;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("S3FileStorageService.openStreamRange - one byte range of an object")
class S3FileStorageServiceRangeTest {

    @Mock S3Client s3Client;

    private S3FileStorageService service() {
        S3FileStorageService svc = new S3FileStorageService();
        ReflectionTestUtils.setField(svc, "s3Client", s3Client);
        ReflectionTestUtils.setField(svc, "bucket", "files");
        return svc;
    }

    private static ResponseInputStream<GetObjectResponse> body(GetObjectResponse meta, int size) {
        return new ResponseInputStream<>(meta, AbortableInputStream.create(new ByteArrayInputStream(new byte[size])));
    }

    @Test
    @DisplayName("forwards the range to S3 and returns the store's Content-Range with the part's length")
    void rangeIsForwarded() {
        GetObjectResponse meta = GetObjectResponse.builder()
                .contentRange("bytes 0-1023/18370402").contentLength(1024L).contentType("video/mp4").build();
        ArgumentCaptor<GetObjectRequest> sent = ArgumentCaptor.forClass(GetObjectRequest.class);
        when(s3Client.getObject(sent.capture())).thenReturn(body(meta, 1024));

        Optional<RangedDownload> part = service().openStreamRange("1/general/ep01.mp4", "bytes=0-1023");

        assertThat(part).isPresent();
        assertThat(part.get().contentRange()).isEqualTo("bytes 0-1023/18370402");
        assertThat(part.get().body().contentLength()).isEqualTo(1024L);
        assertThat(sent.getValue().range()).isEqualTo("bytes=0-1023");
        assertThat(sent.getValue().key()).isEqualTo("1/general/ep01.mp4");
    }

    @Test
    @DisplayName("a store that ignores the range (no Content-Range) is not a range answer: empty")
    void ignoredRangeIsEmpty() {
        GetObjectResponse meta = GetObjectResponse.builder().contentLength(18_370_402L).build();
        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(body(meta, 4));

        assertThat(service().openStreamRange("1/general/ep01.mp4", "bytes=0-")).isEmpty();
    }

    @Test
    @DisplayName("an unsatisfiable range (S3 416) is empty, never an exception: the caller serves the whole file")
    void unsatisfiableRangeIsEmpty() {
        when(s3Client.getObject(any(GetObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(416).message("InvalidRange").build());

        assertThat(service().openStreamRange("1/general/ep01.mp4", "bytes=99999999-")).isEmpty();
    }

    @Test
    @DisplayName("no spec, no call")
    void blankSpec() {
        assertThat(service().openStreamRange("1/general/ep01.mp4", " ")).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(s3Client);
    }
}
