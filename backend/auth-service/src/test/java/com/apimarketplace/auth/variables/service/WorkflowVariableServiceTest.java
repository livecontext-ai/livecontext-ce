package com.apimarketplace.auth.variables.service;

import com.apimarketplace.auth.service.PlanLimitService;
import com.apimarketplace.auth.variables.domain.WorkflowVariableModels.UpsertVariableRequest;
import com.apimarketplace.auth.variables.domain.WorkflowVariableModels.ValueType;
import com.apimarketplace.auth.variables.domain.WorkflowVariableModels.WorkflowVariable;
import com.apimarketplace.auth.variables.repository.WorkflowVariableRepository;
import com.apimarketplace.auth.variables.service.WorkflowVariableService.QuotaStatus;
import com.apimarketplace.auth.variables.service.WorkflowVariableService.VariableConflictException;
import com.apimarketplace.auth.variables.service.WorkflowVariableService.VariableLimitExceededException;
import com.apimarketplace.auth.variables.service.WorkflowVariableService.VariableNotFoundException;
import com.apimarketplace.auth.variables.service.WorkflowVariableService.VariableValidationException;
import com.apimarketplace.common.web.AppEditionProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link WorkflowVariableService} - validation grammar, per-plan quota (CE
 * bypass / null-limit unlimited / 409-shaped overflow), duplicate-name
 * conflicts on create and rename, and the typed bundle conversion the
 * orchestrator consumes for {@code {{$vars.*}}} resolution.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowVariableService")
class WorkflowVariableServiceTest {

    private static final String TENANT = "tenant-a";
    private static final String ORG = "org-1";

    @Mock
    private WorkflowVariableRepository repository;

    @Mock
    private PlanLimitService planLimitService;

    @Mock
    private AppEditionProvider editionProvider;

    private WorkflowVariableService service;

    @BeforeEach
    void setUp() {
        service = new WorkflowVariableService(
                repository, planLimitService, editionProvider, new ObjectMapper());
    }

    private static UpsertVariableRequest request(String name, String value, String type, String description) {
        return new UpsertVariableRequest(name, value, type, description, null);
    }

    private static UpsertVariableRequest secretRequest(String name, String value) {
        return new UpsertVariableRequest(name, value, null, null, true);
    }

    private static WorkflowVariable stored(Long id, String name, String value, ValueType type) {
        return new WorkflowVariable(id, TENANT, null, name, value, type, false, null, TENANT, null, null);
    }

    /** Stubs the cloud edition + an unlimited plan so create() reaches the repository. */
    private void stubUnlimitedCloud() {
        when(editionProvider.hasCeFreeUnlimitedLocalResources()).thenReturn(false);
        when(planLimitService.getLimit(TENANT, "WORKFLOW_VARIABLE")).thenReturn(null);
    }

    // ========== validation ==========

    @Nested
    @DisplayName("validation")
    class ValidationTests {

        @Test
        @DisplayName("rejects a null request body")
        void rejectsNullRequest() {
            assertThatThrownBy(() -> service.create(null, TENANT, null, TENANT))
                    .isInstanceOf(VariableValidationException.class)
                    .hasMessageContaining("request body is required");
            verifyNoInteractions(repository);
        }

        @ParameterizedTest(name = "rejects invalid name \"{0}\"")
        @ValueSource(strings = {"", "  ", "1starts_with_digit", "has-hyphen", "has space", "dotted.name", "$vars"})
        @DisplayName("rejects names outside the {{$vars.name}} grammar")
        void rejectsInvalidNames(String badName) {
            assertThatThrownBy(() -> service.create(request(badName, "v", null, null), TENANT, null, TENANT))
                    .isInstanceOf(VariableValidationException.class)
                    .hasMessageContaining("name must match");
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("rejects a name longer than 64 characters")
        void rejectsOverlongName() {
            String tooLong = "a".repeat(65);

            assertThatThrownBy(() -> service.create(request(tooLong, "v", null, null), TENANT, null, TENANT))
                    .isInstanceOf(VariableValidationException.class)
                    .hasMessageContaining("name must match");
        }

        @Test
        @DisplayName("accepts a 64-character name (the grammar's upper bound)")
        void acceptsMaxLengthName() {
            stubUnlimitedCloud();
            String maxName = "a".repeat(64);
            when(repository.findByName(maxName, TENANT, null)).thenReturn(Optional.empty());
            when(repository.insert(any())).thenAnswer(inv -> inv.getArgument(0));

            WorkflowVariable created = service.create(request(maxName, "v", null, null), TENANT, null, TENANT);

            assertThat(created.name()).isEqualTo(maxName);
        }

        @Test
        @DisplayName("trims surrounding whitespace off the name before validating and storing")
        void trimsName() {
            stubUnlimitedCloud();
            when(repository.findByName("api_url", TENANT, null)).thenReturn(Optional.empty());
            when(repository.insert(any())).thenAnswer(inv -> inv.getArgument(0));

            WorkflowVariable created = service.create(
                    request("  api_url  ", "v", null, null), TENANT, null, TENANT);

            assertThat(created.name()).isEqualTo("api_url");
        }

        @Test
        @DisplayName("rejects a null value - value is required, empty string is allowed")
        void rejectsNullValue() {
            assertThatThrownBy(() -> service.create(request("ok_name", null, null, null), TENANT, null, TENANT))
                    .isInstanceOf(VariableValidationException.class)
                    .hasMessageContaining("value is required");
        }

        @Test
        @DisplayName("accepts an empty-string value for STRING type")
        void acceptsEmptyStringValue() {
            stubUnlimitedCloud();
            when(repository.findByName("empty_ok", TENANT, null)).thenReturn(Optional.empty());
            when(repository.insert(any())).thenAnswer(inv -> inv.getArgument(0));

            WorkflowVariable created = service.create(request("empty_ok", "", null, null), TENANT, null, TENANT);

            assertThat(created.value()).isEmpty();
        }

        @Test
        @DisplayName("rejects a value longer than 100000 characters")
        void rejectsOverlongValue() {
            String tooLong = "x".repeat(100_001);

            assertThatThrownBy(() -> service.create(request("ok_name", tooLong, null, null), TENANT, null, TENANT))
                    .isInstanceOf(VariableValidationException.class)
                    .hasMessageContaining("value exceeds max length 100000");
        }

        @Test
        @DisplayName("accepts a value of exactly 100000 characters (boundary)")
        void acceptsMaxLengthValue() {
            stubUnlimitedCloud();
            String max = "x".repeat(100_000);
            when(repository.findByName("big", TENANT, null)).thenReturn(Optional.empty());
            when(repository.insert(any())).thenAnswer(inv -> inv.getArgument(0));

            WorkflowVariable created = service.create(request("big", max, null, null), TENANT, null, TENANT);

            assertThat(created.value()).hasSize(100_000);
        }

        @Test
        @DisplayName("rejects an unknown type token with the allowed list in the message")
        void rejectsUnknownType() {
            assertThatThrownBy(() -> service.create(request("ok_name", "v", "INTEGER", null), TENANT, null, TENANT))
                    .isInstanceOf(VariableValidationException.class)
                    .hasMessageContaining("STRING, NUMBER, BOOLEAN, JSON");
        }

        @Test
        @DisplayName("NUMBER type rejects a non-numeric value")
        void numberRejectsNonNumeric() {
            assertThatThrownBy(() -> service.create(request("n", "not-a-number", "NUMBER", null), TENANT, null, TENANT))
                    .isInstanceOf(VariableValidationException.class)
                    .hasMessageContaining("not a valid number");
        }

        @Test
        @DisplayName("NUMBER type accepts integers, decimals and scientific notation (with padding)")
        void numberAcceptsNumericForms() {
            stubUnlimitedCloud();
            when(repository.findByName(anyString(), any(), any())).thenReturn(Optional.empty());
            when(repository.insert(any())).thenAnswer(inv -> inv.getArgument(0));

            assertThat(service.create(request("n1", "42", "NUMBER", null), TENANT, null, TENANT).value())
                    .isEqualTo("42");
            assertThat(service.create(request("n2", " -3.14 ", "NUMBER", null), TENANT, null, TENANT).value())
                    .isEqualTo(" -3.14 ");
            assertThat(service.create(request("n3", "1.2e5", "NUMBER", null), TENANT, null, TENANT).value())
                    .isEqualTo("1.2e5");
        }

        @ParameterizedTest(name = "BOOLEAN type rejects \"{0}\"")
        @ValueSource(strings = {"yes", "1", "TRUEISH", ""})
        @DisplayName("BOOLEAN type rejects anything but true/false")
        void booleanRejectsNonBoolean(String bad) {
            assertThatThrownBy(() -> service.create(request("b", bad, "BOOLEAN", null), TENANT, null, TENANT))
                    .isInstanceOf(VariableValidationException.class)
                    .hasMessageContaining("must be 'true' or 'false'");
        }

        @Test
        @DisplayName("BOOLEAN type accepts true/false case-insensitively with padding")
        void booleanAcceptsTrueFalse() {
            stubUnlimitedCloud();
            when(repository.findByName(anyString(), any(), any())).thenReturn(Optional.empty());
            when(repository.insert(any())).thenAnswer(inv -> inv.getArgument(0));

            assertThat(service.create(request("b1", "true", "BOOLEAN", null), TENANT, null, TENANT)).isNotNull();
            assertThat(service.create(request("b2", " FALSE ", "boolean", null), TENANT, null, TENANT)).isNotNull();
        }

        @Test
        @DisplayName("JSON type rejects malformed JSON")
        void jsonRejectsMalformed() {
            assertThatThrownBy(() -> service.create(request("j", "{broken", "JSON", null), TENANT, null, TENANT))
                    .isInstanceOf(VariableValidationException.class)
                    .hasMessageContaining("not valid JSON");
        }

        @Test
        @DisplayName("JSON type accepts objects, arrays and scalar literals")
        void jsonAcceptsWellFormed() {
            stubUnlimitedCloud();
            when(repository.findByName(anyString(), any(), any())).thenReturn(Optional.empty());
            when(repository.insert(any())).thenAnswer(inv -> inv.getArgument(0));

            assertThat(service.create(request("j1", "{\"a\":1}", "JSON", null), TENANT, null, TENANT)).isNotNull();
            assertThat(service.create(request("j2", "[1,2,3]", "JSON", null), TENANT, null, TENANT)).isNotNull();
        }

        @Test
        @DisplayName("rejects a description longer than 500 characters")
        void rejectsOverlongDescription() {
            String tooLong = "d".repeat(501);

            assertThatThrownBy(() -> service.create(request("ok_name", "v", null, tooLong), TENANT, null, TENANT))
                    .isInstanceOf(VariableValidationException.class)
                    .hasMessageContaining("description exceeds max length 500");
        }

        @Test
        @DisplayName("trims the description and stores null when absent")
        void trimsDescription() {
            stubUnlimitedCloud();
            when(repository.findByName(anyString(), any(), any())).thenReturn(Optional.empty());
            when(repository.insert(any())).thenAnswer(inv -> inv.getArgument(0));

            WorkflowVariable withDesc = service.create(
                    request("d1", "v", null, "  padded  "), TENANT, null, TENANT);
            WorkflowVariable withoutDesc = service.create(
                    request("d2", "v", null, null), TENANT, null, TENANT);

            assertThat(withDesc.description()).isEqualTo("padded");
            assertThat(withoutDesc.description()).isNull();
        }

        @Test
        @DisplayName("null type defaults to STRING")
        void nullTypeDefaultsToString() {
            stubUnlimitedCloud();
            when(repository.findByName("s", TENANT, null)).thenReturn(Optional.empty());
            when(repository.insert(any())).thenAnswer(inv -> inv.getArgument(0));

            WorkflowVariable created = service.create(request("s", "anything", null, null), TENANT, null, TENANT);

            assertThat(created.valueType()).isEqualTo(ValueType.STRING);
        }

        @Test
        @DisplayName("create with omitted type keeps STRING semantics: any text is accepted (no NUMBER/JSON parsing applied)")
        void createOmittedTypeValidatesAsString() {
            // Effective-type validation on CREATE resolves an omitted type to
            // STRING (no existing row to preserve) - a value that would fail
            // NUMBER/BOOLEAN/JSON parsing must still be accepted unchanged.
            stubUnlimitedCloud();
            when(repository.findByName("free_text", TENANT, null)).thenReturn(Optional.empty());
            when(repository.insert(any())).thenAnswer(inv -> inv.getArgument(0));

            WorkflowVariable created = service.create(
                    request("free_text", "{not json, not-a-number", null, null), TENANT, null, TENANT);

            assertThat(created.valueType()).isEqualTo(ValueType.STRING);
            assertThat(created.value()).isEqualTo("{not json, not-a-number");
        }
    }

    // ========== quota ==========

    @Nested
    @DisplayName("quota enforcement on create")
    class QuotaTests {

        @Test
        @DisplayName("CE-free edition bypasses the plan limit entirely - PlanLimitService is never consulted")
        void ceFreeBypassesQuota() {
            when(editionProvider.hasCeFreeUnlimitedLocalResources()).thenReturn(true);
            when(repository.findByName("v1", TENANT, null)).thenReturn(Optional.empty());
            when(repository.insert(any())).thenAnswer(inv -> inv.getArgument(0));

            service.create(request("v1", "v", null, null), TENANT, null, TENANT);

            verifyNoInteractions(planLimitService);
            verify(repository, never()).countForScope(any(), any());
        }

        @Test
        @DisplayName("null plan limit means unlimited - the scope count is never even queried")
        void nullLimitIsUnlimited() {
            stubUnlimitedCloud();
            when(repository.findByName("v1", TENANT, null)).thenReturn(Optional.empty());
            when(repository.insert(any())).thenAnswer(inv -> inv.getArgument(0));

            service.create(request("v1", "v", null, null), TENANT, null, TENANT);

            verify(repository, never()).countForScope(any(), any());
        }

        @Test
        @DisplayName("throws VariableLimitExceededException carrying planCode/current/limit when count reaches the cap")
        void throwsWhenAtCap() {
            when(editionProvider.hasCeFreeUnlimitedLocalResources()).thenReturn(false);
            when(planLimitService.getLimit(TENANT, "WORKFLOW_VARIABLE")).thenReturn(3);
            when(planLimitService.getPlanCode(TENANT)).thenReturn("FREE");
            when(repository.countForScope(TENANT, null)).thenReturn(3);

            assertThatThrownBy(() -> service.create(request("v4", "v", null, null), TENANT, null, TENANT))
                    .isInstanceOfSatisfying(VariableLimitExceededException.class, ex -> {
                        assertThat(ex.planCode()).isEqualTo("FREE");
                        assertThat(ex.currentCount()).isEqualTo(3);
                        assertThat(ex.limit()).isEqualTo(3);
                        assertThat(ex.getMessage())
                                .contains("FREE")
                                .contains("DO NOT RETRY");
                    });
            verify(repository, never()).insert(any());
        }

        @Test
        @DisplayName("throws when the count already exceeds the cap (plan downgrade case)")
        void throwsWhenOverCap() {
            when(editionProvider.hasCeFreeUnlimitedLocalResources()).thenReturn(false);
            when(planLimitService.getLimit(TENANT, "WORKFLOW_VARIABLE")).thenReturn(3);
            when(planLimitService.getPlanCode(TENANT)).thenReturn("FREE");
            when(repository.countForScope(TENANT, null)).thenReturn(5);

            assertThatThrownBy(() -> service.create(request("v6", "v", null, null), TENANT, null, TENANT))
                    .isInstanceOf(VariableLimitExceededException.class);
        }

        @Test
        @DisplayName("creates normally when the count is below the cap")
        void createsBelowCap() {
            when(editionProvider.hasCeFreeUnlimitedLocalResources()).thenReturn(false);
            when(planLimitService.getLimit(TENANT, "WORKFLOW_VARIABLE")).thenReturn(3);
            when(repository.countForScope(TENANT, null)).thenReturn(2);
            when(repository.findByName("v3", TENANT, null)).thenReturn(Optional.empty());
            when(repository.insert(any())).thenAnswer(inv -> inv.getArgument(0));

            WorkflowVariable created = service.create(request("v3", "v", null, null), TENANT, null, TENANT);

            assertThat(created).isNotNull();
        }

        @Test
        @DisplayName("counts the ORG scope (not the personal one) when creating a workspace variable")
        void countsOrgScopeForWorkspaceCreate() {
            when(editionProvider.hasCeFreeUnlimitedLocalResources()).thenReturn(false);
            when(planLimitService.getLimit(TENANT, "WORKFLOW_VARIABLE")).thenReturn(10);
            when(repository.countForScope(TENANT, ORG)).thenReturn(0);
            when(repository.findByName("v1", TENANT, ORG)).thenReturn(Optional.empty());
            when(repository.insert(any())).thenAnswer(inv -> inv.getArgument(0));

            service.create(request("v1", "v", null, null), TENANT, ORG, TENANT);

            verify(repository).countForScope(TENANT, ORG);
        }
    }

    // ========== quotaForScope ==========

    @Nested
    @DisplayName("quotaForScope")
    class QuotaForScopeTests {

        @Test
        @DisplayName("CE-free reports a null (unlimited) limit but still the real usage and plan code")
        void ceFreeReportsUnlimited() {
            when(editionProvider.hasCeFreeUnlimitedLocalResources()).thenReturn(true);
            when(repository.countForScope(TENANT, null)).thenReturn(7);
            when(planLimitService.getPlanCode(TENANT)).thenReturn("FREE");

            QuotaStatus status = service.quotaForScope(TENANT, null);

            assertThat(status.used()).isEqualTo(7);
            assertThat(status.limit()).isNull();
            assertThat(status.planCode()).isEqualTo("FREE");
            verify(planLimitService, never()).getLimit(any(), any());
        }

        @Test
        @DisplayName("cloud reports the plan's limit alongside usage")
        void cloudReportsPlanLimit() {
            when(editionProvider.hasCeFreeUnlimitedLocalResources()).thenReturn(false);
            when(repository.countForScope(TENANT, ORG)).thenReturn(2);
            when(planLimitService.getLimit(TENANT, "WORKFLOW_VARIABLE")).thenReturn(3);
            when(planLimitService.getPlanCode(TENANT)).thenReturn("FREE");

            QuotaStatus status = service.quotaForScope(TENANT, ORG);

            assertThat(status.used()).isEqualTo(2);
            assertThat(status.limit()).isEqualTo(3);
            assertThat(status.planCode()).isEqualTo("FREE");
        }
    }

    // ========== create ==========

    @Nested
    @DisplayName("create")
    class CreateTests {

        @Test
        @DisplayName("rejects a duplicate name in the same scope with VariableConflictException")
        void rejectsDuplicateName() {
            stubUnlimitedCloud();
            when(repository.findByName("api_url", TENANT, null))
                    .thenReturn(Optional.of(stored(1L, "api_url", "old", ValueType.STRING)));

            assertThatThrownBy(() -> service.create(request("api_url", "v", null, null), TENANT, null, TENANT))
                    .isInstanceOfSatisfying(VariableConflictException.class,
                            ex -> assertThat(ex.name()).isEqualTo("api_url"))
                    .hasMessageContaining("api_url");
            verify(repository, never()).insert(any());
        }

        @Test
        @DisplayName("inserts a non-secret row carrying the scope, validated fields and createdBy")
        void insertsValidatedRow() {
            stubUnlimitedCloud();
            when(repository.findByName("api_url", TENANT, null)).thenReturn(Optional.empty());
            when(repository.insert(any())).thenAnswer(inv -> inv.getArgument(0));

            service.create(request("api_url", "https://x", "STRING", "desc"), TENANT, null, "creator-id");

            ArgumentCaptor<WorkflowVariable> captor = ArgumentCaptor.forClass(WorkflowVariable.class);
            verify(repository).insert(captor.capture());
            WorkflowVariable inserted = captor.getValue();
            assertThat(inserted.id()).isNull();
            assertThat(inserted.tenantId()).isEqualTo(TENANT);
            assertThat(inserted.organizationId()).isNull();
            assertThat(inserted.name()).isEqualTo("api_url");
            assertThat(inserted.value()).isEqualTo("https://x");
            assertThat(inserted.valueType()).isEqualTo(ValueType.STRING);
            assertThat(inserted.secret()).as("omitted secret defaults to false").isFalse();
            assertThat(inserted.description()).isEqualTo("desc");
            assertThat(inserted.createdBy()).isEqualTo("creator-id");
        }

        @Test
        @DisplayName("secret=true in the request is persisted on the inserted row")
        void insertsSecretRowWhenRequested() {
            stubUnlimitedCloud();
            when(repository.findByName("api_key", TENANT, null)).thenReturn(Optional.empty());
            when(repository.insert(any())).thenAnswer(inv -> inv.getArgument(0));

            service.create(secretRequest("api_key", "sk-123"), TENANT, null, TENANT);

            ArgumentCaptor<WorkflowVariable> captor = ArgumentCaptor.forClass(WorkflowVariable.class);
            verify(repository).insert(captor.capture());
            assertThat(captor.getValue().secret()).isTrue();
        }

        @Test
        @DisplayName("secret=false explicitly in the request inserts a non-secret row")
        void insertsNonSecretRowOnExplicitFalse() {
            stubUnlimitedCloud();
            when(repository.findByName("plain", TENANT, null)).thenReturn(Optional.empty());
            when(repository.insert(any())).thenAnswer(inv -> inv.getArgument(0));

            service.create(new UpsertVariableRequest("plain", "v", null, null, false),
                    TENANT, null, TENANT);

            ArgumentCaptor<WorkflowVariable> captor = ArgumentCaptor.forClass(WorkflowVariable.class);
            verify(repository).insert(captor.capture());
            assertThat(captor.getValue().secret()).isFalse();
        }

        @Test
        @DisplayName("REGRESSION (race → 500): losing the check-then-insert race maps DataIntegrityViolation to the 409 conflict contract")
        void insertRaceMapsToVariableConflict() {
            // Two concurrent creates of the same name both pass the findByName
            // pre-check; the loser hits the scope-unique index. That must surface
            // as the SAME VariableConflictException as the explicit pre-check,
            // not a raw DataIntegrityViolationException (500).
            stubUnlimitedCloud();
            when(repository.findByName("api_url", TENANT, null)).thenReturn(Optional.empty());
            when(repository.insert(any())).thenThrow(
                    new org.springframework.dao.DataIntegrityViolationException("duplicate key"));

            assertThatThrownBy(() -> service.create(request("api_url", "v", null, null), TENANT, null, TENANT))
                    .isInstanceOfSatisfying(VariableConflictException.class,
                            ex -> assertThat(ex.name()).isEqualTo("api_url"))
                    .hasMessageContaining("api_url");
        }
    }

    // ========== upsertByName ==========

    @Nested
    @DisplayName("upsertByName (agent-facing set_variable semantics)")
    class UpsertByNameTests {

        @Test
        @DisplayName("existing name updates in place - the quota gate is NEVER consulted")
        void updateInPlaceNeverChecksQuota() {
            WorkflowVariable existing = stored(5L, "api_url", "old", ValueType.STRING);
            WorkflowVariable refreshed = stored(5L, "api_url", "new-value", ValueType.STRING);
            when(repository.findByName("api_url", TENANT, null)).thenReturn(Optional.of(existing));
            when(repository.findByIdForScope(5L, TENANT, null)).thenReturn(Optional.of(refreshed));

            WorkflowVariable result = service.upsertByName(
                    request("api_url", "new-value", null, null), TENANT, null, TENANT);

            assertThat(result.value()).isEqualTo("new-value");
            verify(repository).update(5L, TENANT, null, "api_url", "new-value", ValueType.STRING, false, null);
            verify(repository, never()).insert(any());
            // The whole point of upsert-update: count unchanged → no quota machinery.
            verifyNoInteractions(planLimitService);
            verify(repository, never()).countForScope(any(), any());
        }

        @Test
        @DisplayName("update-in-place succeeds even when the scope is AT its plan cap (cap only gates creations)")
        void updateAtCapStillSucceeds() {
            // A FREE-plan scope sitting at 3/3: updating one of the 3 must work.
            // (No countForScope/getLimit stubs on purpose - if the code consulted
            // them, Mockito would return 0/null and the never() verifications fail.)
            WorkflowVariable existing = stored(1L, "v1", "old", ValueType.STRING);
            when(repository.findByName("v1", TENANT, null)).thenReturn(Optional.of(existing));
            when(repository.findByIdForScope(1L, TENANT, null))
                    .thenReturn(Optional.of(stored(1L, "v1", "new", ValueType.STRING)));

            WorkflowVariable result = service.upsertByName(
                    request("v1", "new", null, null), TENANT, null, TENANT);

            assertThat(result.value()).isEqualTo("new");
            verifyNoInteractions(planLimitService);
        }

        @Test
        @DisplayName("new name goes through the SAME quota gate as create - 409-shaped overflow at the cap")
        void createPathEnforcesQuota() {
            when(repository.findByName("v4", TENANT, null)).thenReturn(Optional.empty());
            when(editionProvider.hasCeFreeUnlimitedLocalResources()).thenReturn(false);
            when(planLimitService.getLimit(TENANT, "WORKFLOW_VARIABLE")).thenReturn(3);
            when(planLimitService.getPlanCode(TENANT)).thenReturn("FREE");
            when(repository.countForScope(TENANT, null)).thenReturn(3);

            assertThatThrownBy(() -> service.upsertByName(
                    request("v4", "v", null, null), TENANT, null, TENANT))
                    .isInstanceOfSatisfying(VariableLimitExceededException.class, ex -> {
                        assertThat(ex.planCode()).isEqualTo("FREE");
                        assertThat(ex.currentCount()).isEqualTo(3);
                        assertThat(ex.limit()).isEqualTo(3);
                        assertThat(ex.getMessage()).contains("DO NOT RETRY");
                    });
            verify(repository, never()).insert(any());
        }

        @Test
        @DisplayName("new name below the cap inserts a row carrying scope, validated fields and createdBy")
        void createPathInsertsBelowCap() {
            when(repository.findByName("api_url", TENANT, ORG)).thenReturn(Optional.empty());
            when(editionProvider.hasCeFreeUnlimitedLocalResources()).thenReturn(false);
            when(planLimitService.getLimit(TENANT, "WORKFLOW_VARIABLE")).thenReturn(3);
            when(repository.countForScope(TENANT, ORG)).thenReturn(2);
            when(repository.insert(any())).thenAnswer(inv -> inv.getArgument(0));

            WorkflowVariable created = service.upsertByName(
                    request("api_url", "https://x", "STRING", "desc"), TENANT, ORG, "creator-id");

            ArgumentCaptor<WorkflowVariable> captor = ArgumentCaptor.forClass(WorkflowVariable.class);
            verify(repository).insert(captor.capture());
            WorkflowVariable inserted = captor.getValue();
            assertThat(inserted.tenantId()).isEqualTo(TENANT);
            assertThat(inserted.organizationId()).isEqualTo(ORG);
            assertThat(inserted.name()).isEqualTo("api_url");
            assertThat(inserted.value()).isEqualTo("https://x");
            assertThat(inserted.createdBy()).isEqualTo("creator-id");
            assertThat(created).isNotNull();
        }

        @Test
        @DisplayName("upsert is rename-free: the name IS the key, so no VariableConflictException path exists")
        void renameFreeNameIsTheKey() {
            // Unlike update(id, ...), upsertByName can never collide with "another"
            // variable: a fresh name simply creates. Pin that a name unknown to the
            // scope inserts instead of conflicting.
            stubUnlimitedCloud();
            when(repository.findByName("brand_new", TENANT, null)).thenReturn(Optional.empty());
            when(repository.insert(any())).thenAnswer(inv -> inv.getArgument(0));

            WorkflowVariable created = service.upsertByName(
                    request("brand_new", "v", null, null), TENANT, null, TENANT);

            assertThat(created.name()).isEqualTo("brand_new");
            verify(repository).insert(any());
        }

        @Test
        @DisplayName("update path applies the new type and description to the existing row")
        void updatePathAppliesTypeAndDescription() {
            WorkflowVariable existing = stored(9L, "cfg", "{}", ValueType.STRING);
            when(repository.findByName("cfg", TENANT, null)).thenReturn(Optional.of(existing));
            when(repository.findByIdForScope(9L, TENANT, null))
                    .thenReturn(Optional.of(stored(9L, "cfg", "{\"a\":1}", ValueType.JSON)));

            service.upsertByName(request("cfg", "{\"a\":1}", "JSON", "  json cfg  "), TENANT, null, TENANT);

            verify(repository).update(9L, TENANT, null, "cfg", "{\"a\":1}", ValueType.JSON, false, "json cfg");
        }

        @Test
        @DisplayName("validates the name grammar BEFORE any repository lookup (same grammar as create)")
        void validatesBeforeLookup() {
            assertThatThrownBy(() -> service.upsertByName(
                    request("bad name", "v", null, null), TENANT, null, TENANT))
                    .isInstanceOf(VariableValidationException.class);
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("value-vs-type validation runs against the EFFECTIVE type after the name lookup, but never reaches a write")
        void valueTypeMismatchNeverReachesAWrite() {
            // Since the retype fix, the value is checked against the EFFECTIVE
            // type (provided one, else the existing row's), which requires the
            // findByName lookup first. A failing value must still never reach
            // insert or update.
            when(repository.findByName("n", TENANT, null)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.upsertByName(
                    request("n", "not-a-number", "NUMBER", null), TENANT, null, TENANT))
                    .isInstanceOf(VariableValidationException.class)
                    .hasMessageContaining("not a valid number");
            verify(repository, never()).insert(any());
            verify(repository, never()).update(org.mockito.ArgumentMatchers.anyLong(),
                    any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any());
        }

        @Test
        @DisplayName("REGRESSION (silent retype): upsert-update with omitted type PRESERVES the row's NUMBER type")
        void updatePathOmittedTypePreservesNumber() {
            // The retype bug: validate() used to default an omitted type to
            // STRING, so set_variable rotating a NUMBER variable's value
            // without re-passing type silently demoted the row to STRING.
            WorkflowVariable existing = stored(5L, "retries", "3", ValueType.NUMBER);
            when(repository.findByName("retries", TENANT, null)).thenReturn(Optional.of(existing));
            when(repository.findByIdForScope(5L, TENANT, null))
                    .thenReturn(Optional.of(stored(5L, "retries", "7", ValueType.NUMBER)));

            service.upsertByName(request("retries", "7", null, null), TENANT, null, TENANT);

            verify(repository).update(5L, TENANT, null, "retries", "7", ValueType.NUMBER, false, null);
        }

        @Test
        @DisplayName("REGRESSION (retype validation bypass): non-numeric value with omitted type on a NUMBER row is rejected against the preserved type")
        void updatePathValidatesValueAgainstPreservedType() {
            // Pre-fix, the omitted type resolved to STRING so 'abc' sailed
            // through validation and corrupted the stored NUMBER row (it then
            // degraded to a string at bundle time). The value must parse for
            // the EXISTING row's type when no type is passed.
            WorkflowVariable existing = stored(5L, "retries", "3", ValueType.NUMBER);
            when(repository.findByName("retries", TENANT, null)).thenReturn(Optional.of(existing));

            assertThatThrownBy(() -> service.upsertByName(
                    request("retries", "abc", null, null), TENANT, null, TENANT))
                    .isInstanceOf(VariableValidationException.class)
                    .hasMessageContaining("not a valid number");
            verify(repository, never()).update(org.mockito.ArgumentMatchers.anyLong(),
                    any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any());
        }

        @Test
        @DisplayName("upsert-update with an explicit type still retypes the row (only omission preserves)")
        void updatePathExplicitTypeRetypes() {
            WorkflowVariable existing = stored(5L, "retries", "3", ValueType.NUMBER);
            when(repository.findByName("retries", TENANT, null)).thenReturn(Optional.of(existing));
            when(repository.findByIdForScope(5L, TENANT, null))
                    .thenReturn(Optional.of(stored(5L, "retries", "seven", ValueType.STRING)));

            service.upsertByName(request("retries", "seven", "STRING", null), TENANT, null, TENANT);

            verify(repository).update(5L, TENANT, null, "retries", "seven", ValueType.STRING, false, null);
        }

        @Test
        @DisplayName("upsert-update description tri-state: ABSENT key preserves the stored description")
        void updatePathAbsentDescriptionPreserves() {
            WorkflowVariable existing = new WorkflowVariable(5L, TENANT, null, "api_url", "old",
                    ValueType.STRING, false, "keep me", TENANT, null, null);
            when(repository.findByName("api_url", TENANT, null)).thenReturn(Optional.of(existing));
            when(repository.findByIdForScope(5L, TENANT, null)).thenReturn(Optional.of(existing));

            service.upsertByName(request("api_url", "new", null, null), TENANT, null, TENANT);

            verify(repository).update(5L, TENANT, null, "api_url", "new", ValueType.STRING, false, "keep me");
        }

        @Test
        @DisplayName("upsert-update description tri-state: empty string CLEARS the stored description")
        void updatePathEmptyDescriptionClears() {
            WorkflowVariable existing = new WorkflowVariable(5L, TENANT, null, "api_url", "old",
                    ValueType.STRING, false, "stale note", TENANT, null, null);
            when(repository.findByName("api_url", TENANT, null)).thenReturn(Optional.of(existing));
            when(repository.findByIdForScope(5L, TENANT, null)).thenReturn(Optional.of(existing));

            service.upsertByName(request("api_url", "new", null, ""), TENANT, null, TENANT);

            verify(repository).update(5L, TENANT, null, "api_url", "new", ValueType.STRING, false, null);
        }

        @Test
        @DisplayName("CE-free edition creates without any quota machinery, like create()")
        void ceFreeCreateBypassesQuota() {
            when(editionProvider.hasCeFreeUnlimitedLocalResources()).thenReturn(true);
            when(repository.findByName("v1", TENANT, null)).thenReturn(Optional.empty());
            when(repository.insert(any())).thenAnswer(inv -> inv.getArgument(0));

            service.upsertByName(request("v1", "v", null, null), TENANT, null, TENANT);

            verifyNoInteractions(planLimitService);
            verify(repository, never()).countForScope(any(), any());
        }

        @Test
        @DisplayName("upsert-update writes secret=true through to the repository when requested")
        void updatePathPersistsSecretFlag() {
            WorkflowVariable existing = stored(5L, "api_key", "old", ValueType.STRING);
            when(repository.findByName("api_key", TENANT, null)).thenReturn(Optional.of(existing));
            when(repository.findByIdForScope(5L, TENANT, null))
                    .thenReturn(Optional.of(stored(5L, "api_key", "sk-new", ValueType.STRING)));

            service.upsertByName(secretRequest("api_key", "sk-new"), TENANT, null, TENANT);

            verify(repository).update(5L, TENANT, null, "api_key", "sk-new", ValueType.STRING, true, null);
        }

        @Test
        @DisplayName("REGRESSION (silent secret demotion): upsert-update with omitted secret PRESERVES the row's secret=true")
        void updatePathOmittedSecretPreservesTrue() {
            // set_variable rotating a secret's VALUE (no secret arg) must not
            // demote it to readable - the stored flag survives the upsert.
            WorkflowVariable existing = new WorkflowVariable(5L, TENANT, null, "api_key", "sk-old",
                    ValueType.STRING, true, null, TENANT, null, null);
            when(repository.findByName("api_key", TENANT, null)).thenReturn(Optional.of(existing));
            when(repository.findByIdForScope(5L, TENANT, null))
                    .thenReturn(Optional.of(existing));

            service.upsertByName(request("api_key", "sk-new", null, null), TENANT, null, TENANT);

            verify(repository).update(5L, TENANT, null, "api_key", "sk-new", ValueType.STRING, true, null);
        }

        @Test
        @DisplayName("upsert-update with explicit secret=false demotes the row - only omission preserves")
        void updatePathExplicitFalseDemotes() {
            WorkflowVariable existing = new WorkflowVariable(5L, TENANT, null, "api_key", "sk-old",
                    ValueType.STRING, true, null, TENANT, null, null);
            when(repository.findByName("api_key", TENANT, null)).thenReturn(Optional.of(existing));
            when(repository.findByIdForScope(5L, TENANT, null))
                    .thenReturn(Optional.of(existing));

            service.upsertByName(new UpsertVariableRequest("api_key", "plain-now", null, null, false),
                    TENANT, null, TENANT);

            verify(repository).update(5L, TENANT, null, "api_key", "plain-now", ValueType.STRING, false, null);
        }

        @Test
        @DisplayName("upsert-create with omitted secret coerces null to false (new rows are non-secret by default)")
        void createPathOmittedSecretDefaultsToFalse() {
            stubUnlimitedCloud();
            when(repository.findByName("fresh", TENANT, null)).thenReturn(Optional.empty());
            when(repository.insert(any())).thenAnswer(inv -> inv.getArgument(0));

            service.upsertByName(request("fresh", "v", null, null), TENANT, null, TENANT);

            ArgumentCaptor<WorkflowVariable> captor = ArgumentCaptor.forClass(WorkflowVariable.class);
            verify(repository).insert(captor.capture());
            assertThat(captor.getValue().secret()).isFalse();
        }

        @Test
        @DisplayName("upsert-create inserts a secret row when the request carries secret=true")
        void createPathPersistsSecretFlag() {
            stubUnlimitedCloud();
            when(repository.findByName("api_key", TENANT, null)).thenReturn(Optional.empty());
            when(repository.insert(any())).thenAnswer(inv -> inv.getArgument(0));

            service.upsertByName(secretRequest("api_key", "sk-123"), TENANT, null, TENANT);

            ArgumentCaptor<WorkflowVariable> captor = ArgumentCaptor.forClass(WorkflowVariable.class);
            verify(repository).insert(captor.capture());
            assertThat(captor.getValue().secret()).isTrue();
        }

        @Test
        @DisplayName("REGRESSION (race → 500): upsert-create losing the insert race maps DataIntegrityViolation to the 409 conflict contract")
        void upsertCreateInsertRaceMapsToVariableConflict() {
            // Same race as create(): the name was absent at findByName time but a
            // concurrent set_variable inserted it first - the unique-index hit
            // must become VariableConflictException, not a 500.
            stubUnlimitedCloud();
            when(repository.findByName("api_url", TENANT, null)).thenReturn(Optional.empty());
            when(repository.insert(any())).thenThrow(
                    new org.springframework.dao.DataIntegrityViolationException("duplicate key"));

            assertThatThrownBy(() -> service.upsertByName(
                    request("api_url", "v", null, null), TENANT, null, TENANT))
                    .isInstanceOfSatisfying(VariableConflictException.class,
                            ex -> assertThat(ex.name()).isEqualTo("api_url"));
        }

        @Test
        @DisplayName("update path throws VariableNotFoundException if the row vanished between update and re-fetch")
        void updatePathThrowsWhenRefetchMisses() {
            WorkflowVariable existing = stored(3L, "ghost", "v", ValueType.STRING);
            when(repository.findByName("ghost", TENANT, null)).thenReturn(Optional.of(existing));
            when(repository.findByIdForScope(3L, TENANT, null)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.upsertByName(
                    request("ghost", "v2", null, null), TENANT, null, TENANT))
                    .isInstanceOf(VariableNotFoundException.class);
        }
    }

    // ========== update ==========

    @Nested
    @DisplayName("update")
    class UpdateTests {

        @Test
        @DisplayName("throws VariableNotFoundException when the id is not in the caller's scope")
        void throwsWhenNotFound() {
            when(repository.findByIdForScope(99L, TENANT, null)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.update(99L, request("n", "v", null, null), TENANT, null))
                    .isInstanceOf(VariableNotFoundException.class)
                    .hasMessageContaining("99");
            verify(repository, never()).update(org.mockito.ArgumentMatchers.anyLong(),
                    any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any());
        }

        @Test
        @DisplayName("rejects a rename onto an existing name in the same scope")
        void rejectsRenameConflict() {
            when(repository.findByIdForScope(1L, TENANT, null))
                    .thenReturn(Optional.of(stored(1L, "old_name", "v", ValueType.STRING)));
            when(repository.findByName("taken", TENANT, null))
                    .thenReturn(Optional.of(stored(2L, "taken", "v", ValueType.STRING)));

            assertThatThrownBy(() -> service.update(1L, request("taken", "v", null, null), TENANT, null))
                    .isInstanceOf(VariableConflictException.class);
        }

        @Test
        @DisplayName("REGRESSION (rename race → 500): a rename that passes the pre-check but loses the DB unique-index race maps to the 409 conflict")
        void renameRaceMapsToVariableConflict() {
            // Pre-check passes (new name free at read time), but a concurrent
            // create/rename claims it before our UPDATE lands - the scope-unique
            // index throws. Must surface as the 409 conflict contract, not a raw
            // DataIntegrityViolationException (500) - mirrors the insert-path race.
            when(repository.findByIdForScope(1L, TENANT, null))
                    .thenReturn(Optional.of(stored(1L, "old_name", "v", ValueType.STRING)));
            when(repository.findByName("new_name", TENANT, null)).thenReturn(Optional.empty());
            when(repository.update(org.mockito.ArgumentMatchers.eq(1L), any(), any(), any(), any(), any(),
                    org.mockito.ArgumentMatchers.anyBoolean(), any()))
                    .thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate key"));

            assertThatThrownBy(() -> service.update(1L, request("new_name", "v", null, null), TENANT, null))
                    .isInstanceOf(VariableConflictException.class);
        }

        @Test
        @DisplayName("keeping the same name does NOT trigger the conflict check against itself")
        void sameNameSkipsConflictCheck() {
            WorkflowVariable existing = stored(1L, "api_url", "old", ValueType.STRING);
            when(repository.findByIdForScope(1L, TENANT, null)).thenReturn(Optional.of(existing));

            service.update(1L, request("api_url", "new-value", null, null), TENANT, null);

            verify(repository, never()).findByName(any(), any(), any());
            verify(repository).update(1L, TENANT, null, "api_url", "new-value", ValueType.STRING, false, null);
        }

        @Test
        @DisplayName("returns the freshly re-fetched row after the update")
        void returnsRefetchedRow() {
            WorkflowVariable before = stored(1L, "api_url", "old", ValueType.STRING);
            WorkflowVariable after = stored(1L, "api_url", "new-value", ValueType.STRING);
            when(repository.findByIdForScope(1L, TENANT, null))
                    .thenReturn(Optional.of(before))
                    .thenReturn(Optional.of(after));

            WorkflowVariable result = service.update(1L, request("api_url", "new-value", null, null), TENANT, null);

            assertThat(result.value()).isEqualTo("new-value");
        }

        @Test
        @DisplayName("validates the payload BEFORE touching the repository")
        void validatesBeforeLookup() {
            assertThatThrownBy(() -> service.update(1L, request("bad name", "v", null, null), TENANT, null))
                    .isInstanceOf(VariableValidationException.class);
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("writes secret=true through to the repository when the request carries it")
        void persistsSecretFlagOnUpdate() {
            WorkflowVariable existing = stored(1L, "api_key", "old", ValueType.STRING);
            when(repository.findByIdForScope(1L, TENANT, null)).thenReturn(Optional.of(existing));

            service.update(1L, secretRequest("api_key", "sk-new"), TENANT, null);

            verify(repository).update(1L, TENANT, null, "api_key", "sk-new", ValueType.STRING, true, null);
        }

        @Test
        @DisplayName("REGRESSION (silent secret demotion): omitting secret on update PRESERVES the existing row's flag")
        void omittedSecretPreservesExistingFlagOnUpdate() {
            // The demotion bug: the request shape is write-only, so rotating a
            // secret's value without re-asserting secret=true used to resolve
            // Boolean.TRUE.equals(null) to false and silently demote the row to
            // readable. An omitted (null) secret must keep the stored flag.
            WorkflowVariable existing = new WorkflowVariable(1L, TENANT, null, "api_key", "old",
                    ValueType.STRING, true, null, TENANT, null, null);
            when(repository.findByIdForScope(1L, TENANT, null)).thenReturn(Optional.of(existing));

            service.update(1L, request("api_key", "new", null, null), TENANT, null);

            verify(repository).update(1L, TENANT, null, "api_key", "new", ValueType.STRING, true, null);
        }

        @Test
        @DisplayName("explicit secret=false on update still demotes the row - only omission preserves")
        void explicitFalseStillDemotesOnUpdate() {
            WorkflowVariable existing = new WorkflowVariable(1L, TENANT, null, "api_key", "old",
                    ValueType.STRING, true, null, TENANT, null, null);
            when(repository.findByIdForScope(1L, TENANT, null)).thenReturn(Optional.of(existing));

            service.update(1L, new UpsertVariableRequest("api_key", "new", null, null, false),
                    TENANT, null);

            verify(repository).update(1L, TENANT, null, "api_key", "new", ValueType.STRING, false, null);
        }

        @Test
        @DisplayName("REGRESSION (silent retype): omitting type on update PRESERVES the existing row's NUMBER type")
        void omittedTypePreservesExistingTypeOnUpdate() {
            // The retype bug: validate() used to default an omitted type to
            // STRING, so updating a NUMBER variable's value without re-passing
            // type silently demoted the row to STRING.
            WorkflowVariable existing = stored(1L, "retries", "3", ValueType.NUMBER);
            when(repository.findByIdForScope(1L, TENANT, null)).thenReturn(Optional.of(existing));

            service.update(1L, request("retries", "5", null, null), TENANT, null);

            verify(repository).update(1L, TENANT, null, "retries", "5", ValueType.NUMBER, false, null);
        }

        @Test
        @DisplayName("REGRESSION (retype validation bypass): value 'abc' with omitted type on a NUMBER row -> validation error, no write")
        void valueValidatedAgainstEffectiveTypeOnUpdate() {
            // Pre-fix, the omitted type resolved to STRING so 'abc' passed
            // validation and corrupted the NUMBER row. The value must be
            // validated against the EXISTING row's type when type is omitted.
            WorkflowVariable existing = stored(1L, "retries", "3", ValueType.NUMBER);
            when(repository.findByIdForScope(1L, TENANT, null)).thenReturn(Optional.of(existing));

            assertThatThrownBy(() -> service.update(1L, request("retries", "abc", null, null), TENANT, null))
                    .isInstanceOf(VariableValidationException.class)
                    .hasMessageContaining("not a valid number");
            verify(repository, never()).update(org.mockito.ArgumentMatchers.anyLong(),
                    any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any());
        }

        @Test
        @DisplayName("explicit type on update still retypes the row (only omission preserves)")
        void explicitTypeStillRetypesOnUpdate() {
            WorkflowVariable existing = stored(1L, "retries", "3", ValueType.NUMBER);
            when(repository.findByIdForScope(1L, TENANT, null)).thenReturn(Optional.of(existing));

            service.update(1L, request("retries", "seven", "STRING", null), TENANT, null);

            verify(repository).update(1L, TENANT, null, "retries", "seven", ValueType.STRING, false, null);
        }

        @Test
        @DisplayName("description tri-state on update: ABSENT key preserves the stored description")
        void absentDescriptionPreservesOnUpdate() {
            WorkflowVariable existing = new WorkflowVariable(1L, TENANT, null, "api_url", "old",
                    ValueType.STRING, false, "keep me", TENANT, null, null);
            when(repository.findByIdForScope(1L, TENANT, null)).thenReturn(Optional.of(existing));

            service.update(1L, request("api_url", "new", null, null), TENANT, null);

            verify(repository).update(1L, TENANT, null, "api_url", "new", ValueType.STRING, false, "keep me");
        }

        @Test
        @DisplayName("description tri-state on update: empty string CLEARS the stored description to null")
        void emptyDescriptionClearsOnUpdate() {
            WorkflowVariable existing = new WorkflowVariable(1L, TENANT, null, "api_url", "old",
                    ValueType.STRING, false, "stale note", TENANT, null, null);
            when(repository.findByIdForScope(1L, TENANT, null)).thenReturn(Optional.of(existing));

            service.update(1L, request("api_url", "new", null, ""), TENANT, null);

            verify(repository).update(1L, TENANT, null, "api_url", "new", ValueType.STRING, false, null);
        }

        @Test
        @DisplayName("description tri-state on update: non-empty text REPLACES the stored description (trimmed)")
        void textDescriptionReplacesOnUpdate() {
            WorkflowVariable existing = new WorkflowVariable(1L, TENANT, null, "api_url", "old",
                    ValueType.STRING, false, "old note", TENANT, null, null);
            when(repository.findByIdForScope(1L, TENANT, null)).thenReturn(Optional.of(existing));

            service.update(1L, request("api_url", "new", null, "  fresh note  "), TENANT, null);

            verify(repository).update(1L, TENANT, null, "api_url", "new", ValueType.STRING, false, "fresh note");
        }
    }

    // ========== delete ==========

    @Nested
    @DisplayName("delete")
    class DeleteTests {

        @Test
        @DisplayName("throws VariableNotFoundException when no row matched the scope")
        void throwsWhenNothingDeleted() {
            when(repository.deleteByIdForScope(42L, TENANT, null)).thenReturn(false);

            assertThatThrownBy(() -> service.delete(42L, TENANT, null))
                    .isInstanceOf(VariableNotFoundException.class)
                    .hasMessageContaining("42");
        }

        @Test
        @DisplayName("completes silently when the row was deleted")
        void completesOnDelete() {
            when(repository.deleteByIdForScope(42L, TENANT, ORG)).thenReturn(true);

            service.delete(42L, TENANT, ORG);

            verify(repository).deleteByIdForScope(42L, TENANT, ORG);
        }
    }

    // ========== bundleForScope ==========

    @Nested
    @DisplayName("bundleForScope typed conversion")
    class BundleTests {

        @Test
        @DisplayName("STRING values pass through verbatim")
        void stringPassesThrough() {
            when(repository.findAllForScope(TENANT, null))
                    .thenReturn(List.of(stored(1L, "s", "hello", ValueType.STRING)));

            Map<String, Object> bundle = service.bundleForScope(TENANT, null);

            assertThat(bundle).containsExactly(Map.entry("s", "hello"));
        }

        @Test
        @DisplayName("NUMBER values become BigDecimal so SpEL comparisons are numeric, not lexicographic")
        void numberBecomesBigDecimal() {
            when(repository.findAllForScope(TENANT, null))
                    .thenReturn(List.of(stored(1L, "n", " 42.5 ", ValueType.NUMBER)));

            Map<String, Object> bundle = service.bundleForScope(TENANT, null);

            assertThat(bundle.get("n")).isEqualTo(new BigDecimal("42.5"));
        }

        @Test
        @DisplayName("BOOLEAN values become Boolean")
        void booleanBecomesBoolean() {
            when(repository.findAllForScope(TENANT, null)).thenReturn(List.of(
                    stored(1L, "yes", " true ", ValueType.BOOLEAN),
                    stored(2L, "no", "false", ValueType.BOOLEAN)));

            Map<String, Object> bundle = service.bundleForScope(TENANT, null);

            assertThat(bundle.get("yes")).isEqualTo(Boolean.TRUE);
            assertThat(bundle.get("no")).isEqualTo(Boolean.FALSE);
        }

        @Test
        @DisplayName("JSON values are parsed into navigable structures (map / list)")
        void jsonIsParsed() {
            when(repository.findAllForScope(TENANT, null)).thenReturn(List.of(
                    stored(1L, "cfg", "{\"host\":\"db\",\"port\":5432}", ValueType.JSON),
                    stored(2L, "tags", "[\"a\",\"b\"]", ValueType.JSON)));

            Map<String, Object> bundle = service.bundleForScope(TENANT, null);

            assertThat(bundle.get("cfg")).isEqualTo(Map.of("host", "db", "port", 5432));
            assertThat(bundle.get("tags")).isEqualTo(List.of("a", "b"));
        }

        @Test
        @DisplayName("an unparseable stored NUMBER degrades to its raw string instead of breaking the whole bundle")
        void unparseableNumberDegradesToString() {
            when(repository.findAllForScope(TENANT, null)).thenReturn(List.of(
                    stored(1L, "corrupt", "not-a-number", ValueType.NUMBER),
                    stored(2L, "fine", "7", ValueType.NUMBER)));

            Map<String, Object> bundle = service.bundleForScope(TENANT, null);

            assertThat(bundle.get("corrupt")).isEqualTo("not-a-number");
            assertThat(bundle.get("fine")).isEqualTo(new BigDecimal("7"));
        }

        @Test
        @DisplayName("an unparseable stored JSON degrades to its raw string")
        void unparseableJsonDegradesToString() {
            when(repository.findAllForScope(TENANT, null)).thenReturn(List.of(
                    stored(1L, "broken", "{oops", ValueType.JSON)));

            Map<String, Object> bundle = service.bundleForScope(TENANT, null);

            assertThat(bundle.get("broken")).isEqualTo("{oops");
        }

        @Test
        @DisplayName("preserves the repository's name ordering (LinkedHashMap)")
        void preservesOrdering() {
            when(repository.findAllForScope(TENANT, null)).thenReturn(List.of(
                    stored(1L, "alpha", "1", ValueType.STRING),
                    stored(2L, "beta", "2", ValueType.STRING),
                    stored(3L, "gamma", "3", ValueType.STRING)));

            Map<String, Object> bundle = service.bundleForScope(TENANT, null);

            assertThat(bundle.keySet()).containsExactly("alpha", "beta", "gamma");
        }

        @Test
        @DisplayName("an empty scope yields an empty map")
        void emptyScopeYieldsEmptyMap() {
            when(repository.findAllForScope(TENANT, null)).thenReturn(List.of());

            assertThat(service.bundleForScope(TENANT, null)).isEmpty();
        }
    }
}
