package com.apimarketplace.auth.variables.service;

import com.apimarketplace.auth.service.PlanLimitService;
import com.apimarketplace.auth.variables.domain.WorkflowVariableModels;
import com.apimarketplace.auth.variables.domain.WorkflowVariableModels.UpsertVariableRequest;
import com.apimarketplace.auth.variables.domain.WorkflowVariableModels.ValueType;
import com.apimarketplace.auth.variables.domain.WorkflowVariableModels.WorkflowVariable;
import com.apimarketplace.auth.variables.repository.WorkflowVariableRepository;
import com.apimarketplace.common.web.AppEditionProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * CRUD + quota enforcement for workflow variables ({@code {{$vars.name}}}).
 *
 * <p>Creation is capped per plan via {@code auth.plan.max_workflow_variables}
 * (ResourceType key {@code WORKFLOW_VARIABLE}, NULL = unlimited). CE-free
 * installs bypass the cap entirely, consistent with every other resource limit
 * ({@code AppEditionProvider.hasCeFreeUnlimitedLocalResources()} - the same
 * decision EntitlementGuard's ceFreeNoopMode applies in the other services).
 * Updates and deletes are never quota-gated.
 */
@Service
public class WorkflowVariableService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowVariableService.class);

    /** ResourceType key - must match Plan.getResourceLimit and auth-client's ResourceType enum. */
    public static final String RESOURCE_TYPE = "WORKFLOW_VARIABLE";

    private static final Pattern NAME_REGEX = Pattern.compile(WorkflowVariableModels.NAME_PATTERN);

    private final WorkflowVariableRepository repository;
    private final PlanLimitService planLimitService;
    private final AppEditionProvider editionProvider;
    private final ObjectMapper objectMapper;

    public WorkflowVariableService(WorkflowVariableRepository repository,
                                   PlanLimitService planLimitService,
                                   AppEditionProvider editionProvider,
                                   ObjectMapper objectMapper) {
        this.repository = repository;
        this.planLimitService = planLimitService;
        this.editionProvider = editionProvider;
        this.objectMapper = objectMapper;
    }

    public List<WorkflowVariable> listForScope(String tenantId, String organizationId) {
        return repository.findAllForScope(tenantId, organizationId);
    }

    /** Current usage vs plan cap, for the UI meter. {@code limit} null = unlimited. */
    public QuotaStatus quotaForScope(String tenantId, String organizationId) {
        int used = repository.countForScope(tenantId, organizationId);
        if (editionProvider.hasCeFreeUnlimitedLocalResources()) {
            return new QuotaStatus(used, null, planLimitService.getPlanCode(tenantId));
        }
        return new QuotaStatus(used,
                planLimitService.getLimit(tenantId, RESOURCE_TYPE),
                planLimitService.getPlanCode(tenantId));
    }

    @Transactional
    public WorkflowVariable create(UpsertVariableRequest request, String tenantId,
                                   String organizationId, String createdBy) {
        Validated validated = validate(request);
        ValueType effectiveType = validated.type() != null ? validated.type() : ValueType.STRING;
        validateValueForType(validated.value(), effectiveType);
        enforceQuota(tenantId, organizationId);
        repository.findByName(validated.name(), tenantId, organizationId).ifPresent(existing -> {
            throw new VariableConflictException(validated.name());
        });
        WorkflowVariable created = insertMappingDuplicateToConflict(
                new WorkflowVariable(
                        null, tenantId, organizationId, validated.name(), validated.value(),
                        effectiveType, Boolean.TRUE.equals(validated.secret()), validated.description(), createdBy, null, null));
        log.info("Created workflow variable '{}' (id={}) for tenant {} (org {})",
                created.name(), created.id(), tenantId, organizationId);
        return created;
    }

    /**
     * Create-or-update by name (agent-facing set_variable semantics): an
     * existing variable of the scope is updated in place (no quota check -
     * count unchanged); a new name goes through the same quota gate as
     * {@link #create}.
     */
    @Transactional
    public WorkflowVariable upsertByName(UpsertVariableRequest request, String tenantId,
                                         String organizationId, String createdBy) {
        Validated validated = validate(request);
        var existing = repository.findByName(validated.name(), tenantId, organizationId);
        if (existing.isPresent()) {
            WorkflowVariable current = existing.get();
            long id = current.id();
            // Preserve-on-omit contract (secret / type / description): rotating
            // a value without re-passing the metadata must never demote the
            // secret flag, retype a NUMBER/JSON variable to STRING, or wipe the
            // description. Empty-string description explicitly clears it.
            boolean secretFlag = validated.secret() != null ? validated.secret() : current.secret();
            ValueType effectiveType = validated.type() != null ? validated.type() : current.valueType();
            validateValueForType(validated.value(), effectiveType);
            String description = validated.descriptionProvided() ? validated.description() : current.description();
            repository.update(id, tenantId, organizationId,
                    validated.name(), validated.value(), effectiveType, secretFlag, description);
            return repository.findByIdForScope(id, tenantId, organizationId)
                    .orElseThrow(() -> new VariableNotFoundException(id));
        }
        ValueType effectiveTypeForCreate = validated.type() != null ? validated.type() : ValueType.STRING;
        validateValueForType(validated.value(), effectiveTypeForCreate);
        enforceQuota(tenantId, organizationId);
        WorkflowVariable created = insertMappingDuplicateToConflict(
                new WorkflowVariable(
                        null, tenantId, organizationId, validated.name(), validated.value(),
                        effectiveTypeForCreate, Boolean.TRUE.equals(validated.secret()), validated.description(), createdBy, null, null));
        log.info("Upsert created workflow variable '{}' (id={}) for tenant {} (org {})",
                created.name(), created.id(), tenantId, organizationId);
        return created;
    }

    @Transactional
    public WorkflowVariable update(long id, UpsertVariableRequest request,
                                   String tenantId, String organizationId) {
        Validated validated = validate(request);
        WorkflowVariable existing = repository.findByIdForScope(id, tenantId, organizationId)
                .orElseThrow(() -> new VariableNotFoundException(id));
        if (!existing.name().equals(validated.name())) {
            repository.findByName(validated.name(), tenantId, organizationId).ifPresent(other -> {
                throw new VariableConflictException(validated.name());
            });
        }
        // Same preserve-on-omit contract as upsertByName (secret / type / description).
        boolean secretFlag = validated.secret() != null ? validated.secret() : existing.secret();
        ValueType effectiveType = validated.type() != null ? validated.type() : existing.valueType();
        validateValueForType(validated.value(), effectiveType);
        String description = validated.descriptionProvided() ? validated.description() : existing.description();
        try {
            repository.update(id, tenantId, organizationId,
                    validated.name(), validated.value(), effectiveType, secretFlag, description);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // Rename that passed the pre-check but lost the race to the
            // scope-unique index - same 409 contract as the create paths.
            throw new VariableConflictException(validated.name());
        }
        return repository.findByIdForScope(id, tenantId, organizationId)
                .orElseThrow(() -> new VariableNotFoundException(id));
    }

    @Transactional
    public void delete(long id, String tenantId, String organizationId) {
        if (!repository.deleteByIdForScope(id, tenantId, organizationId)) {
            throw new VariableNotFoundException(id);
        }
        log.info("Deleted workflow variable id={} for tenant {} (org {})", id, tenantId, organizationId);
    }

    /**
     * Decrypted, TYPED bundle for the orchestrator's per-run fetch: NUMBER
     * becomes a BigDecimal, BOOLEAN a Boolean, JSON the parsed structure -
     * so SpEL comparisons and navigation behave natively downstream.
     */
    public Map<String, Object> bundleForScope(String tenantId, String organizationId) {
        Map<String, Object> bundle = new LinkedHashMap<>();
        for (WorkflowVariable variable : repository.findAllForScope(tenantId, organizationId)) {
            bundle.put(variable.name(), toTypedValue(variable));
        }
        return bundle;
    }

    private Object toTypedValue(WorkflowVariable variable) {
        try {
            return switch (variable.valueType()) {
                case STRING -> variable.value();
                case NUMBER -> new BigDecimal(variable.value().trim());
                case BOOLEAN -> parseStrictBoolean(variable.value());
                case JSON -> objectMapper.readValue(variable.value(), Object.class);
            };
        } catch (Exception e) {
            // A stored value that no longer parses (should be prevented by
            // validate()) degrades to its raw string rather than breaking
            // every run of the scope.
            log.warn("Workflow variable '{}' has unparseable {} value, serving as string",
                    variable.name(), variable.valueType());
            return variable.value();
        }
    }

    /**
     * Boolean.parseBoolean never throws (anything not "true" is false), which
     * would silently serve a corrupted stored value as {@code false}. Parse
     * strictly instead so corruption degrades to the raw string via the
     * catch block, like the other types.
     */
    private static boolean parseStrictBoolean(String value) {
        String normalized = value.trim().toLowerCase();
        if (normalized.equals("true")) return true;
        if (normalized.equals("false")) return false;
        throw new IllegalArgumentException("not a boolean: " + value);
    }

    /**
     * A concurrent create that loses the check-then-insert race hits the
     * scope-unique index; surface it as the same 409 conflict contract as the
     * explicit pre-check instead of a raw 500.
     */
    private WorkflowVariable insertMappingDuplicateToConflict(WorkflowVariable variable) {
        try {
            return repository.insert(variable);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new VariableConflictException(variable.name());
        }
    }

    /**
     * Quota semantics: the cap is the CALLING user's plan limit, applied to the
     * ACTIVE SCOPE's row count. In a mixed-plan workspace each member is capped
     * by their own plan against the shared pool (a FREE member cannot grow a
     * TEAM owner's workspace beyond the FREE cap). Deliberate: mirrors how the
     * other EntitlementGuard resource caps resolve the caller's plan.
     */
    private void enforceQuota(String tenantId, String organizationId) {
        if (editionProvider.hasCeFreeUnlimitedLocalResources()) {
            return;
        }
        Integer limit = planLimitService.getLimit(tenantId, RESOURCE_TYPE);
        if (limit == null) {
            return;
        }
        int current = repository.countForScope(tenantId, organizationId);
        if (current >= limit) {
            throw new VariableLimitExceededException(
                    planLimitService.getPlanCode(tenantId), current, limit);
        }
    }

    private Validated validate(UpsertVariableRequest request) {
        if (request == null) {
            throw new VariableValidationException("request body is required");
        }
        String name = request.name() != null ? request.name().trim() : "";
        if (!NAME_REGEX.matcher(name).matches()) {
            throw new VariableValidationException(
                    "name must match " + WorkflowVariableModels.NAME_PATTERN
                            + " (letters, digits, underscore; must not start with a digit)");
        }
        String value = request.value();
        if (value == null) {
            throw new VariableValidationException("value is required");
        }
        if (value.length() > WorkflowVariableModels.MAX_VALUE_LENGTH) {
            throw new VariableValidationException(
                    "value exceeds max length " + WorkflowVariableModels.MAX_VALUE_LENGTH);
        }
        // type omitted (null/blank) = PRESERVE on update, STRING on create -
        // resolved per call path against the existing row. Same preserve-on-omit
        // contract as `secret`: an agent updating a value without re-passing the
        // type must never demote a NUMBER/JSON variable to STRING.
        ValueType type = null;
        if (request.type() != null && !request.type().isBlank()) {
            type = ValueType.fromValue(request.type());
            if (type == null) {
                throw new VariableValidationException("type must be one of STRING, NUMBER, BOOLEAN, JSON");
            }
        }
        // description tri-state: key absent = preserve; empty string = clear;
        // non-empty = replace. (The UI always sends the key.)
        boolean descriptionProvided = request.description() != null;
        String description = null;
        if (descriptionProvided) {
            String trimmed = request.description().trim();
            if (trimmed.length() > WorkflowVariableModels.MAX_DESCRIPTION_LENGTH) {
                throw new VariableValidationException(
                        "description exceeds max length " + WorkflowVariableModels.MAX_DESCRIPTION_LENGTH);
            }
            description = trimmed.isEmpty() ? null : trimmed;
        }
        return new Validated(name, value, type, request.secret(), descriptionProvided, description);
    }

    private void validateValueForType(String value, ValueType type) {
        switch (type) {
            case NUMBER -> {
                try {
                    new BigDecimal(value.trim());
                } catch (NumberFormatException e) {
                    throw new VariableValidationException("value is not a valid number");
                }
            }
            case BOOLEAN -> {
                String normalized = value.trim().toLowerCase();
                if (!normalized.equals("true") && !normalized.equals("false")) {
                    throw new VariableValidationException("value must be 'true' or 'false'");
                }
            }
            case JSON -> {
                try {
                    objectMapper.readTree(value);
                } catch (Exception e) {
                    throw new VariableValidationException("value is not valid JSON");
                }
            }
            case STRING -> { /* any text */ }
        }
    }

    private record Validated(String name, String value, ValueType type, Boolean secret,
                             boolean descriptionProvided, String description) {
    }

    public record QuotaStatus(int used, Integer limit, String planCode) {
    }

    // ===== exceptions (mapped to HTTP statuses by the controller) =====

    public static class VariableValidationException extends RuntimeException {
        public VariableValidationException(String message) {
            super(message);
        }
    }

    public static class VariableNotFoundException extends RuntimeException {
        public VariableNotFoundException(long id) {
            super("workflow variable " + id + " not found in this scope");
        }
    }

    public static class VariableConflictException extends RuntimeException {
        private final String name;

        public VariableConflictException(String name) {
            super("a variable named '" + name + "' already exists in this scope");
            this.name = name;
        }

        public String name() {
            return name;
        }
    }

    /**
     * Same wire contract as auth-client's LimitExceededError (409 +
     * PLAN_RESOURCE_LIMIT_EXCEEDED) so the existing frontend upgrade toast
     * handles it with zero changes. auth-service cannot reuse that record
     * directly (no auth-client dependency), the controller mirrors the shape.
     */
    public static class VariableLimitExceededException extends RuntimeException {
        private final String planCode;
        private final int currentCount;
        private final int limit;

        public VariableLimitExceededException(String planCode, int currentCount, int limit) {
            super(String.format(
                    "LIMIT REACHED: Your %s plan allows max %d workflow variable%s (currently %d/%d). "
                            + "Tell the user to upgrade their plan or delete an existing variable. "
                            + "DO NOT RETRY this operation.",
                    planCode, limit, limit == 1 ? "" : "s", currentCount, limit));
            this.planCode = planCode;
            this.currentCount = currentCount;
            this.limit = limit;
        }

        public String planCode() {
            return planCode;
        }

        public int currentCount() {
            return currentCount;
        }

        public int limit() {
            return limit;
        }
    }
}
