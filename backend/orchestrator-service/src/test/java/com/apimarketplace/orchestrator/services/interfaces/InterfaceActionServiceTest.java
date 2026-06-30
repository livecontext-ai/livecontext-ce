package com.apimarketplace.orchestrator.services.interfaces;

import com.apimarketplace.common.storage.domain.StorageEntity;
import com.apimarketplace.common.storage.repository.StorageRepository;
import com.apimarketplace.common.storage.service.StorageService;
import com.apimarketplace.orchestrator.utils.ExecutionConstants;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for InterfaceActionService.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InterfaceActionService")
class InterfaceActionServiceTest {

    @Mock
    private StorageService storageService;

    @Mock
    private StorageRepository storageRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private InterfaceActionService service;

    @BeforeEach
    void setUp() {
        service = new InterfaceActionService(storageService, storageRepository, objectMapper);
    }

    @Test
    @DisplayName("Should persist action data keyed by action name")
    @SuppressWarnings("unchecked")
    void shouldPersistActionDataByActionName() {
        Map<String, Object> data = Map.of("name", "Alice", "email", "alice@test.com");
        when(storageRepository.findByRunIdAndStepKeyAndItemIndexAndEpoch(anyString(), anyString(), anyInt(), anyInt(), anyString()))
                .thenReturn(Optional.empty());

        service.persistActionData("run-1", "interface:my_form", "submit", data, "tenant-1", 0);

        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(storageService).saveJsonWithContext(
                eq("tenant-1"), payloadCaptor.capture(), eq(ExecutionConstants.CONTENT_TYPE_JSON),
                isNull(), isNull(), eq("run-1"), eq("interface:my_form"), eq(0), eq(0),
                isNull(), eq("INTERFACE_ACTION")
        );

        Map<String, Object> payload = payloadCaptor.getValue();
        assertThat(payload).containsKey("output");

        Map<String, Object> output = (Map<String, Object>) payload.get("output");
        assertThat(output).containsKey("submit");

        Map<String, Object> submitAction = (Map<String, Object>) output.get("submit");
        assertThat(submitAction).containsEntry("name", "Alice");
        assertThat(submitAction).containsEntry("email", "alice@test.com");
        assertThat(submitAction).containsKey("fired_at");
    }

    @Test
    @DisplayName("Should merge multiple actions without overwriting")
    @SuppressWarnings("unchecked")
    void shouldMergeMultipleActions() throws Exception {
        // Simulate existing "submit" action in storage
        Map<String, Object> existingOutput = new HashMap<>();
        existingOutput.put("submit", Map.of("name", "Alice", "fired_at", "2026-01-01T00:00:00Z"));
        Map<String, Object> existingPayload = Map.of("output", existingOutput);

        StorageEntity existingEntity = mock(StorageEntity.class);
        when(existingEntity.getData()).thenReturn(objectMapper.writeValueAsString(existingPayload));
        when(storageRepository.findByRunIdAndStepKeyAndItemIndexAndEpoch("run-1", "interface:form", 0, 0, "tenant-1"))
                .thenReturn(Optional.of(existingEntity));

        // Fire "cancel" action
        service.persistActionData("run-1", "interface:form", "cancel", Map.of(), "tenant-1", 0);

        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(storageService).saveJsonWithContext(
                anyString(), payloadCaptor.capture(), anyString(),
                isNull(), isNull(), anyString(), anyString(), anyInt(), anyInt(),
                isNull(), eq("INTERFACE_ACTION")
        );

        Map<String, Object> payload = payloadCaptor.getValue();
        Map<String, Object> output = (Map<String, Object>) payload.get("output");

        // Both actions should be present
        assertThat(output).containsKey("submit");
        assertThat(output).containsKey("cancel");

        // Submit should retain original data
        Map<String, Object> submit = (Map<String, Object>) output.get("submit");
        assertThat(submit).containsEntry("name", "Alice");

        // Cancel should have fired_at
        Map<String, Object> cancel = (Map<String, Object>) output.get("cancel");
        assertThat(cancel).containsKey("fired_at");
    }

    @Test
    @DisplayName("Should pass correct epoch")
    void shouldPassCorrectEpoch() {
        when(storageRepository.findByRunIdAndStepKeyAndItemIndexAndEpoch(anyString(), anyString(), anyInt(), anyInt(), anyString()))
                .thenReturn(Optional.empty());

        service.persistActionData("run-1", "interface:form", "submit", Map.of(), "tenant-1", 3);

        verify(storageService).saveJsonWithContext(
                anyString(), any(Map.class), anyString(),
                isNull(), isNull(), anyString(), anyString(), eq(0), eq(3),
                isNull(), eq("INTERFACE_ACTION")
        );
    }

    @Test
    @DisplayName("Should handle empty data map")
    @SuppressWarnings("unchecked")
    void shouldHandleEmptyData() {
        when(storageRepository.findByRunIdAndStepKeyAndItemIndexAndEpoch(anyString(), anyString(), anyInt(), anyInt(), anyString()))
                .thenReturn(Optional.empty());

        service.persistActionData("run-1", "interface:form", "submit", Map.of(), "tenant-1", 0);

        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(storageService).saveJsonWithContext(
                anyString(), payloadCaptor.capture(), anyString(),
                isNull(), isNull(), anyString(), anyString(), anyInt(), anyInt(),
                isNull(), eq("INTERFACE_ACTION")
        );

        Map<String, Object> payload = payloadCaptor.getValue();
        Map<String, Object> output = (Map<String, Object>) payload.get("output");
        Map<String, Object> submitAction = (Map<String, Object>) output.get("submit");
        assertThat(submitAction).containsKey("fired_at");
    }

    @Test
    @DisplayName("Should merge existing action data only within the current split item")
    @SuppressWarnings("unchecked")
    void shouldScopeExistingActionMergeByItemIndex() throws Exception {
        Map<String, Object> itemTwoOutput = new HashMap<>();
        itemTwoOutput.put("save", Map.of("value", "item-2", "fired_at", "2026-01-01T00:00:00Z"));
        StorageEntity itemTwoEntity = mock(StorageEntity.class);
        when(itemTwoEntity.getData()).thenReturn(objectMapper.writeValueAsString(Map.of("output", itemTwoOutput)));
        when(storageRepository.findByRunIdAndStepKeyAndItemIndexAndEpoch(
                "run-1", "interface:form", 2, 7, "tenant-1"))
                .thenReturn(Optional.of(itemTwoEntity));

        service.persistActionData(
                "run-1", "interface:form", "approve", Map.of("choice", "yes"), "tenant-1", 7, "wf-1", 2);

        verify(storageRepository).findByRunIdAndStepKeyAndItemIndexAndEpoch(
                "run-1", "interface:form", 2, 7, "tenant-1");
        verify(storageRepository, never()).findByRunIdAndStepKeyAndEpoch(anyString(), anyString(), anyInt(), anyString());

        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(storageService).saveJsonWithContext(
                eq("tenant-1"), payloadCaptor.capture(), eq(ExecutionConstants.CONTENT_TYPE_JSON),
                isNull(), isNull(), eq("run-1"), eq("interface:form"), eq(2), eq(7),
                eq("wf-1"), eq("INTERFACE_ACTION")
        );

        Map<String, Object> output = (Map<String, Object>) payloadCaptor.getValue().get("output");
        assertThat(output).containsKeys("save", "approve");
        assertThat((Map<String, Object>) output.get("save")).containsEntry("value", "item-2");
        assertThat((Map<String, Object>) output.get("approve")).containsEntry("choice", "yes");
    }
}
