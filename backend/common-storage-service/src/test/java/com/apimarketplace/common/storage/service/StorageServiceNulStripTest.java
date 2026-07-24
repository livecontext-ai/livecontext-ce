package com.apimarketplace.common.storage.service;

import com.apimarketplace.common.storage.domain.StorageEntity;
import com.apimarketplace.common.storage.exception.StorageSerializationException;
import com.apimarketplace.common.storage.repository.StorageRepository;
import com.apimarketplace.common.storage.service.api.MappingOperations;
import com.apimarketplace.common.storage.service.api.QuotaOperations;
import com.apimarketplace.common.storage.util.JsonSkeletonGenerator;
import com.apimarketplace.common.storage.util.StorageUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

/**
 * Service-level funnel tests: {@code saveJsonWithContext} must persist a
 * NUL-stripped payload (the entity funnel applies before the row reaches the
 * repository) and must surface a serialization failure to the caller instead
 * of silently storing garbage.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StorageService - saveJsonWithContext NUL strip + honest serialization failure")
class StorageServiceNulStripTest {

    private static final String NUL = String.valueOf((char) 0);

    @Mock private StorageRepository storageRepository;
    @Mock private QuotaOperations quotaService;
    @Mock private MappingOperations mappingService;
    @Mock private StorageUtils storageUtils;
    @Mock private JsonSkeletonGenerator skeletonGenerator;
    @Mock private StorageBreakdownService breakdownService;

    private StorageService service;

    @BeforeEach
    void setUp() {
        service = new StorageService(storageRepository, quotaService, mappingService,
                storageUtils, skeletonGenerator, new ObjectMapper(), breakdownService);
        lenient().when(storageRepository.save(any(StorageEntity.class))).thenAnswer(inv -> {
            StorageEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });
    }

    @Test
    @DisplayName("payload containing U+0000 lands in the repository with the codepoint stripped (a<NUL>b -> ab)")
    void savedRowHasNulStripped() {
        UUID id = service.saveJsonWithContext("tenant-1", Map.of("k", "a" + NUL + "b"),
                "application/json", null, null, "run-1", "mcp:step", 0, 1, 0, "wf-1", "STEP_OUTPUT");

        assertThat(id).isNotNull();
        ArgumentCaptor<StorageEntity> captor = ArgumentCaptor.forClass(StorageEntity.class);
        verify(storageRepository).save(captor.capture());
        assertThat(captor.getValue().getData())
                .doesNotContain(NUL)
                .contains("\"ab\"");
    }

    @Test
    @DisplayName("unserializable payload FAILS the save loudly (StorageSerializationException) - no toString garbage row")
    void unserializablePayloadFailsLoudly() {
        Object unserializable = new Object() {
            @Override
            public String toString() {
                return "GARBAGE-NOT-JSON";
            }
        };

        assertThatThrownBy(() -> service.saveJsonWithContext("tenant-1", unserializable,
                "application/json", null, null, "run-1", "mcp:step", 0, 1, 0, "wf-1", "STEP_OUTPUT"))
                .isInstanceOf(StorageSerializationException.class);
    }
}
