package com.apimarketplace.common.storage.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("StorageEntity Domain Entity Tests")
class StorageEntityTest {

    @Nested
    @DisplayName("Default Constructor")
    class DefaultConstructorTests {

        @Test
        @DisplayName("should create entity with default values")
        void shouldCreateWithDefaults() {
            StorageEntity entity = new StorageEntity();

            assertThat(entity.getId()).isNull();
            assertThat(entity.getTenantId()).isNull();
            assertThat(entity.getStorageType()).isEqualTo("JSON");
            assertThat(entity.getStatus()).isEqualTo(StorageStatus.ACTIVE);
            assertThat(entity.getEpoch()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("JSON Constructor")
    class JsonConstructorTests {

        @Test
        @DisplayName("should create entity for JSON data")
        void shouldCreateForJsonData() {
            String tenantId = "tenant-123";
            Map<String, String> data = Map.of("key", "value");
            String contentType = "application/json";
            int sizeBytes = 100;
            String checksum = "abc123";
            Instant expiresAt = Instant.now().plusSeconds(3600);

            StorageEntity entity = new StorageEntity(tenantId, contentType, data, sizeBytes, checksum, expiresAt);

            assertThat(entity.getTenantId()).isEqualTo(tenantId);
            assertThat(entity.getContentType()).isEqualTo(contentType);
            assertThat(entity.getData()).isNotNull();
            assertThat(entity.getSizeBytes()).isEqualTo(sizeBytes);
            assertThat(entity.getChecksum()).isEqualTo(checksum);
            assertThat(entity.getExpiresAt()).isEqualTo(expiresAt);
            assertThat(entity.getCreatedAt()).isNotNull();
            assertThat(entity.getAccessedAt()).isNotNull();
            assertThat(entity.getStatus()).isEqualTo(StorageStatus.ACTIVE);
            assertThat(entity.getStorageType()).isEqualTo("JSON");
        }

        @Test
        @DisplayName("should handle null data in JSON constructor")
        void shouldHandleNullDataInJsonConstructor() {
            StorageEntity entity = new StorageEntity("tenant-1", "application/json", (Object) null, 0, null, null);

            assertThat(entity.getData()).isNull();
            assertThat(entity.getExpiresAt()).isNull();
        }
    }

    @Nested
    @DisplayName("Binary Constructor")
    class BinaryConstructorTests {

        @Test
        @DisplayName("should create entity for binary data")
        void shouldCreateForBinaryData() {
            byte[] data = new byte[]{1, 2, 3, 4, 5};
            Instant expiresAt = Instant.now().plusSeconds(7200);

            StorageEntity entity = new StorageEntity("tenant-1", "image/png",
                data, "image.png", "png", "image/png",
                5, "checksum123", expiresAt);

            assertThat(entity.getDataBinary()).isEqualTo(data);
            assertThat(entity.getFileName()).isEqualTo("image.png");
            assertThat(entity.getFileExtension()).isEqualTo("png");
            assertThat(entity.getMimeType()).isEqualTo("image/png");
            assertThat(entity.getStorageType()).isEqualTo("BINARY");
            assertThat(entity.getData()).isEqualTo("{}"); // Default JSON for NOT NULL constraint
            assertThat(entity.getStatus()).isEqualTo(StorageStatus.ACTIVE);
        }
    }

    @Nested
    @DisplayName("Text Constructor")
    class TextConstructorTests {

        @Test
        @DisplayName("should create entity for text data")
        void shouldCreateForTextData() {
            String textData = "This is some text content";
            Instant expiresAt = Instant.now().plusSeconds(3600);

            StorageEntity entity = new StorageEntity("tenant-1", "text/plain",
                textData, "document.txt", "txt", "text/plain",
                25, "checksum456", expiresAt);

            assertThat(entity.getDataText()).isEqualTo(textData);
            assertThat(entity.getFileName()).isEqualTo("document.txt");
            assertThat(entity.getFileExtension()).isEqualTo("txt");
            assertThat(entity.getMimeType()).isEqualTo("text/plain");
            assertThat(entity.getStorageType()).isEqualTo("TEXT");
            assertThat(entity.getData()).isEqualTo("{}");
        }
    }

    @Nested
    @DisplayName("touch")
    class TouchTests {

        @Test
        @DisplayName("should update accessedAt timestamp")
        void shouldUpdateAccessedAt() {
            StorageEntity entity = new StorageEntity("tenant-1", "application/json",
                Map.of("k", "v"), 10, null, null);

            Instant before = entity.getAccessedAt();
            entity.touch();

            assertThat(entity.getAccessedAt()).isAfterOrEqualTo(before);
        }
    }

    @Nested
    @DisplayName("isExpired")
    class IsExpiredTests {

        @Test
        @DisplayName("should return true when expiresAt is in the past")
        void shouldReturnTrueWhenExpired() {
            StorageEntity entity = new StorageEntity();
            entity.setExpiresAt(Instant.now().minusSeconds(3600));

            assertThat(entity.isExpired()).isTrue();
        }

        @Test
        @DisplayName("should return false when expiresAt is in the future")
        void shouldReturnFalseWhenNotExpired() {
            StorageEntity entity = new StorageEntity();
            entity.setExpiresAt(Instant.now().plusSeconds(3600));

            assertThat(entity.isExpired()).isFalse();
        }

        @Test
        @DisplayName("should return false when expiresAt is null")
        void shouldReturnFalseWhenExpiresAtNull() {
            StorageEntity entity = new StorageEntity();
            entity.setExpiresAt(null);

            assertThat(entity.isExpired()).isFalse();
        }
    }

    @Nested
    @DisplayName("isActive")
    class IsActiveTests {

        @Test
        @DisplayName("should return true when ACTIVE and not expired")
        void shouldReturnTrueWhenActiveAndNotExpired() {
            StorageEntity entity = new StorageEntity();
            entity.setStatus(StorageStatus.ACTIVE);
            entity.setExpiresAt(Instant.now().plusSeconds(3600));

            assertThat(entity.isActive()).isTrue();
        }

        @Test
        @DisplayName("should return false when DELETED")
        void shouldReturnFalseWhenDeleted() {
            StorageEntity entity = new StorageEntity();
            entity.setStatus(StorageStatus.DELETED);
            entity.setExpiresAt(Instant.now().plusSeconds(3600));

            assertThat(entity.isActive()).isFalse();
        }

        @Test
        @DisplayName("should return false when ARCHIVED")
        void shouldReturnFalseWhenArchived() {
            StorageEntity entity = new StorageEntity();
            entity.setStatus(StorageStatus.ARCHIVED);

            assertThat(entity.isActive()).isFalse();
        }

        @Test
        @DisplayName("should return false when ACTIVE but expired")
        void shouldReturnFalseWhenActiveButExpired() {
            StorageEntity entity = new StorageEntity();
            entity.setStatus(StorageStatus.ACTIVE);
            entity.setExpiresAt(Instant.now().minusSeconds(3600));

            assertThat(entity.isActive()).isFalse();
        }

        @Test
        @DisplayName("should return true when ACTIVE and no expiration set")
        void shouldReturnTrueWhenActiveAndNoExpiration() {
            StorageEntity entity = new StorageEntity();
            entity.setStatus(StorageStatus.ACTIVE);
            entity.setExpiresAt(null);

            assertThat(entity.isActive()).isTrue();
        }
    }

    @Nested
    @DisplayName("getDataAsMap")
    class GetDataAsMapTests {

        @Test
        @DisplayName("should return null when data is null")
        void shouldReturnNullWhenDataIsNull() {
            StorageEntity entity = new StorageEntity();
            entity.setData((String) null);

            assertThat(entity.getDataAsMap()).isNull();
        }

        @Test
        @DisplayName("should deserialize valid JSON to Map")
        void shouldDeserializeValidJsonToMap() {
            StorageEntity entity = new StorageEntity();
            entity.setData("{\"key\":\"value\",\"number\":42}");

            Map<String, Object> result = entity.getDataAsMap();

            assertThat(result).isNotNull();
            assertThat(result).containsEntry("key", "value");
            assertThat(result).containsEntry("number", 42);
        }

        @Test
        @DisplayName("should return null for invalid JSON")
        void shouldReturnNullForInvalidJson() {
            StorageEntity entity = new StorageEntity();
            entity.setData("not valid json {{{");

            assertThat(entity.getDataAsMap()).isNull();
        }
    }

    @Nested
    @DisplayName("getDataMappedAsMap")
    class GetDataMappedAsMapTests {

        @Test
        @DisplayName("should return null when dataMapped is null")
        void shouldReturnNullWhenDataMappedNull() {
            StorageEntity entity = new StorageEntity();

            assertThat(entity.getDataMappedAsMap()).isNull();
        }

        @Test
        @DisplayName("should deserialize valid JSON dataMapped to Map")
        void shouldDeserializeValidJsonDataMappedToMap() {
            StorageEntity entity = new StorageEntity();
            entity.setDataMapped("{\"mapped_field\":\"mapped_value\"}");

            Map<String, Object> result = entity.getDataMappedAsMap();

            assertThat(result).isNotNull();
            assertThat(result).containsEntry("mapped_field", "mapped_value");
        }

        @Test
        @DisplayName("should return null for invalid JSON dataMapped")
        void shouldReturnNullForInvalidJsonDataMapped() {
            StorageEntity entity = new StorageEntity();
            entity.setDataMapped("invalid json");

            assertThat(entity.getDataMappedAsMap()).isNull();
        }
    }

    @Nested
    @DisplayName("setData with Object")
    class SetDataObjectTests {

        @Test
        @DisplayName("should serialize object to JSON string")
        void shouldSerializeObjectToJson() {
            StorageEntity entity = new StorageEntity();
            entity.setData((Object) Map.of("key", "value"));

            assertThat(entity.getData()).isNotNull();
            assertThat(entity.getData()).contains("key");
            assertThat(entity.getData()).contains("value");
        }

        @Test
        @DisplayName("should handle null object for setData")
        void shouldHandleNullObjectForSetData() {
            StorageEntity entity = new StorageEntity();
            entity.setData((Object) null);

            assertThat(entity.getData()).isNull();
        }
    }

    @Nested
    @DisplayName("setMetadata with Object")
    class SetMetadataObjectTests {

        @Test
        @DisplayName("should serialize metadata object to JSON string")
        void shouldSerializeMetadataToJson() {
            StorageEntity entity = new StorageEntity();
            entity.setMetadata((Object) Map.of("source", "api"));

            assertThat(entity.getMetadata()).isNotNull();
            assertThat(entity.getMetadata()).contains("source");
        }

        @Test
        @DisplayName("should handle null metadata object")
        void shouldHandleNullMetadata() {
            StorageEntity entity = new StorageEntity();
            entity.setMetadata((Object) null);

            assertThat(entity.getMetadata()).isNull();
        }
    }

    @Nested
    @DisplayName("Run Context Fields")
    class RunContextFieldsTests {

        @Test
        @DisplayName("should set and get runId")
        void shouldSetAndGetRunId() {
            StorageEntity entity = new StorageEntity();
            entity.setRunId("run-abc-123");

            assertThat(entity.getRunId()).isEqualTo("run-abc-123");
        }

        @Test
        @DisplayName("should set and get stepKey")
        void shouldSetAndGetStepKey() {
            StorageEntity entity = new StorageEntity();
            entity.setStepKey("mcp:api_call");

            assertThat(entity.getStepKey()).isEqualTo("mcp:api_call");
        }

        @Test
        @DisplayName("should set and get itemIndex")
        void shouldSetAndGetItemIndex() {
            StorageEntity entity = new StorageEntity();
            entity.setItemIndex(5);

            assertThat(entity.getItemIndex()).isEqualTo(5);
        }

        @Test
        @DisplayName("should set and get epoch")
        void shouldSetAndGetEpoch() {
            StorageEntity entity = new StorageEntity();
            entity.setEpoch(3);

            assertThat(entity.getEpoch()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("Multimedia Fields")
    class MultimediaFieldsTests {

        @Test
        @DisplayName("should set and get width")
        void shouldSetAndGetWidth() {
            StorageEntity entity = new StorageEntity();
            entity.setWidth(1920);

            assertThat(entity.getWidth()).isEqualTo(1920);
        }

        @Test
        @DisplayName("should set and get height")
        void shouldSetAndGetHeight() {
            StorageEntity entity = new StorageEntity();
            entity.setHeight(1080);

            assertThat(entity.getHeight()).isEqualTo(1080);
        }

        @Test
        @DisplayName("should set and get duration")
        void shouldSetAndGetDuration() {
            StorageEntity entity = new StorageEntity();
            entity.setDuration(120);

            assertThat(entity.getDuration()).isEqualTo(120);
        }
    }

    @Nested
    @DisplayName("Getters and Setters")
    class GettersSettersTests {

        @Test
        @DisplayName("should set and get id")
        void shouldSetAndGetId() {
            StorageEntity entity = new StorageEntity();
            UUID id = UUID.randomUUID();
            entity.setId(id);

            assertThat(entity.getId()).isEqualTo(id);
        }

        @Test
        @DisplayName("should set and get structureSkeleton")
        void shouldSetAndGetStructureSkeleton() {
            StorageEntity entity = new StorageEntity();
            String skeleton = "{\"_t\":\"obj\",\"props\":{}}";
            entity.setStructureSkeleton(skeleton);

            assertThat(entity.getStructureSkeleton()).isEqualTo(skeleton);
        }

        @Test
        @DisplayName("should set and get dataMapped with Object")
        void shouldSetAndGetDataMappedWithObject() {
            StorageEntity entity = new StorageEntity();
            entity.setDataMapped((Object) Map.of("mapped", "data"));

            assertThat(entity.getDataMapped()).isNotNull();
            assertThat(entity.getDataMapped()).contains("mapped");
        }
    }
}
