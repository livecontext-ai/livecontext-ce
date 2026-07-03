package com.apimarketplace.conversation.service;

import com.apimarketplace.conversation.entity.UserChatDefaults;
import com.apimarketplace.conversation.repository.UserChatDefaultsRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserChatDefaultsServiceTest {

    @Mock
    private UserChatDefaultsRepository repository;

    @InjectMocks
    private UserChatDefaultsService service;

    @Test
    @DisplayName("get returns an empty map when no defaults are stored for (user, org)")
    void getReturnsEmptyWhenNoRowExists() {
        when(repository.findByUserIdAndOrganizationId("u1", "orgA")).thenReturn(Optional.empty());

        Map<String, Object> result = service.get("u1", "orgA");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("get returns the stored config for (user, org)")
    void getReturnsStoredConfig() {
        Map<String, Object> stored = Map.of("webSearch", false, "temperature", 0.4);
        when(repository.findByUserIdAndOrganizationId("u1", "orgA"))
                .thenReturn(Optional.of(new UserChatDefaults("u1", "orgA", stored)));

        Map<String, Object> result = service.get("u1", "orgA");

        assertThat(result).containsEntry("webSearch", false).containsEntry("temperature", 0.4);
    }

    @Test
    @DisplayName("save drops unknown and null keys, keeping only whitelisted ChatConfig keys")
    void saveSanitizesUnknownAndNullKeys() {
        when(repository.findByUserIdAndOrganizationId("u1", "orgA")).thenReturn(Optional.empty());
        when(repository.save(any(UserChatDefaults.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> incoming = new LinkedHashMap<>();
        incoming.put("webSearch", true);                 // allowed
        incoming.put("temperature", 0.9);                // allowed
        incoming.put("turnLimits", Map.of("loopIdenticalStop", 20)); // allowed (nested)
        incoming.put("defaultSkillIds", List.of("s1"));  // allowed
        incoming.put("evilKey", "drop-me");              // unknown → dropped
        incoming.put("systemPrompt", null);              // null → dropped

        Map<String, Object> saved = service.save("u1", "orgA", incoming);

        assertThat(saved).containsOnlyKeys("webSearch", "temperature", "turnLimits", "defaultSkillIds");
        assertThat(saved).doesNotContainKey("evilKey");
        assertThat(saved).doesNotContainKey("systemPrompt");
    }

    @Test
    @DisplayName("save upserts into the existing row for (user, org) rather than creating a duplicate")
    void saveUpsertsExistingRow() {
        UserChatDefaults existing = new UserChatDefaults("u1", "orgA", new HashMap<>(Map.of("webSearch", true)));
        when(repository.findByUserIdAndOrganizationId("u1", "orgA")).thenReturn(Optional.of(existing));
        when(repository.save(any(UserChatDefaults.class))).thenAnswer(inv -> inv.getArgument(0));

        service.save("u1", "orgA", Map.of("webSearch", false));

        ArgumentCaptor<UserChatDefaults> captor = ArgumentCaptor.forClass(UserChatDefaults.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue()).isSameAs(existing); // reused, not a new entity
        assertThat(captor.getValue().getConfig()).containsEntry("webSearch", false);
    }

    @Test
    @DisplayName("save with a null body persists an empty config (clears defaults) without error")
    void saveNullBodyPersistsEmpty() {
        when(repository.findByUserIdAndOrganizationId("u1", "orgA")).thenReturn(Optional.empty());
        when(repository.save(any(UserChatDefaults.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> saved = service.save("u1", "orgA", null);

        assertThat(saved).isEmpty();
        verify(repository).save(any(UserChatDefaults.class));
    }

    @Test
    @DisplayName("save keeps inactivityTimeout (V372 whitelisted) so a per-workspace default reaches new conversations")
    void saveKeepsInactivityTimeout() {
        when(repository.findByUserIdAndOrganizationId("u1", "orgA")).thenReturn(Optional.empty());
        when(repository.save(any(UserChatDefaults.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> incoming = new LinkedHashMap<>();
        incoming.put("executionTimeout", 1800);   // existing sibling, whitelisted
        incoming.put("inactivityTimeout", 120);    // V372 - must survive sanitize alongside executionTimeout

        Map<String, Object> saved = service.save("u1", "orgA", incoming);

        // Regression for the ALLOWED_KEYS gap: pre-fix sanitize() dropped inactivityTimeout,
        // so a per-(user, workspace) inactivity default could never seed a new conversation.
        assertThat(saved).containsEntry("inactivityTimeout", 120);
        assertThat(saved).containsEntry("executionTimeout", 1800);
    }

    @Test
    @DisplayName("save keeps inactivityTimeout=0 (disabled sentinel) - the whitelist must not coerce or drop falsy values")
    void saveKeepsZeroInactivityTimeoutVerbatim() {
        when(repository.findByUserIdAndOrganizationId("u1", "orgA")).thenReturn(Optional.empty());
        when(repository.save(any(UserChatDefaults.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> incoming = new LinkedHashMap<>();
        incoming.put("inactivityTimeout", 0);

        Map<String, Object> saved = service.save("u1", "orgA", incoming);

        // 0 = "watchdog disabled" and must round-trip verbatim: a user whose workspace
        // default disables the watchdog would otherwise get the 5-min default back on
        // every new conversation.
        assertThat(saved).containsEntry("inactivityTimeout", 0);
    }

    @Test
    @DisplayName("save keeps compactionModelProvider/compactionModelName (whitelisted) and still drops unknown keys")
    void saveKeepsCompactionModelKeys() {
        when(repository.findByUserIdAndOrganizationId("u1", "orgA")).thenReturn(Optional.empty());
        when(repository.save(any(UserChatDefaults.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> incoming = new LinkedHashMap<>();
        incoming.put("compactionModelProvider", "openai");       // whitelisted (summariser model)
        incoming.put("compactionModelName", "gpt-5-mini");       // whitelisted (summariser model)
        incoming.put("compactionSummariserVendor", "drop-me");   // unknown → dropped

        Map<String, Object> saved = service.save("u1", "orgA", incoming);

        // Pre-change sanitize() dropped both model keys, so a per-(user, workspace)
        // default summariser model could never seed a new conversation's chatConfig.
        assertThat(saved).containsEntry("compactionModelProvider", "openai");
        assertThat(saved).containsEntry("compactionModelName", "gpt-5-mini");
        assertThat(saved).doesNotContainKey("compactionSummariserVendor");
    }

    @Test
    @DisplayName("get never writes")
    void getIsReadOnly() {
        when(repository.findByUserIdAndOrganizationId("u1", "orgA")).thenReturn(Optional.empty());
        service.get("u1", "orgA");
        verify(repository, never()).save(any());
    }
}
