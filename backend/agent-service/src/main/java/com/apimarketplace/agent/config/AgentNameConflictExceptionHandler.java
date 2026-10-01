package com.apimarketplace.agent.config;

import com.apimarketplace.agent.service.AgentNameConflictException;
import com.apimarketplace.agent.service.AgentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Answers an agent name held by another ACTIVE agent of the workspace (V269 index) with 409
 * {@code AGENT_NAME_CONFLICT} and the first free name, for agent-service's controllers.
 *
 * <p><b>Why a dedicated, first-ranked advice and not a method on {@link GlobalExceptionHandler}.</b>
 * Spring asks the advices in order and the FIRST one holding any matching method wins, however
 * generic that method is. In the CE monolith every service's advice lives in one context, and
 * orchestrator's GlobalExceptionHandler (an {@code IllegalArgumentException} handler answering
 * 400 INVALID_ARGUMENT, and a catch-all) is consulted before agent-service's, so a 409 declared
 * on agent's own advice never reached the UI there. {@link Ordered#HIGHEST_PRECEDENCE} puts this
 * one first in both editions, as {@code OrgAccessDeniedExceptionHandler} does for 403.
 *
 * <p><b>Scoped to the controllers that create, rename or clone agents</b> (those implementing
 * {@link AgentNameConflictSource}: AgentController, InternalAgentController), because it also
 * holds a {@link DataIntegrityViolationException} method, the path a write that lost the race to
 * the index arrives by (the losing transaction is aborted, so it is translated here, outside it).
 * A first-ranked advice holding that method more widely would change how every other controller
 * of the CE monolith answers its integrity errors. On those two controllers, an integrity error
 * that is NOT the name index is answered by agent-service's own GlobalExceptionHandler logic
 * (400); in CE it previously fell to whichever advice ranked first.
 *
 * <p><b>Wrapped causes match too.</b> Spring's exception resolution also tries the CAUSE chain
 * of a thrown exception against {@code @ExceptionHandler} types, so on these two controllers a
 * {@link DataIntegrityViolationException} (or an {@link AgentNameConflictException}) wrapped in
 * another exception is answered here as well, not only one thrown as is.
 */
@RestControllerAdvice(assignableTypes = AgentNameConflictSource.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AgentNameConflictExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(AgentNameConflictExceptionHandler.class);

    /** agent-service's own answer to every other integrity error, unchanged. */
    private final GlobalExceptionHandler otherIntegrityErrors = new GlobalExceptionHandler();

    /**
     * Computes the free name a lost race suggests, in a fresh transaction (this runs after the
     * failed one rolled back). Optional so the advice still builds without it (unit tests); the
     * conflict is then answered without a suggestion.
     */
    @Autowired(required = false)
    private AgentService agentService;

    /**
     * 409, not the 400 of its IllegalArgumentException parent: the request is well-formed and
     * conflicts with existing state, and the body carries what resolves it in one step. The UI
     * reads {@code error == AGENT_NAME_CONFLICT} and offers {@code suggestedName};
     * {@code existingAgentId} is absent when a concurrent writer won the race.
     */
    @ExceptionHandler(AgentNameConflictException.class)
    public ResponseEntity<Map<String, Object>> handleAgentNameConflict(AgentNameConflictException e) {
        Map<String, Object> body = new HashMap<>();
        body.put("error", "AGENT_NAME_CONFLICT");
        body.put("message", e.getMessage());
        if (e.getName() != null) {
            body.put("name", e.getName());
        }
        if (e.getSuggestedName() != null) {
            body.put("suggestedName", e.getSuggestedName());
        }
        if (e.getExistingAgentId() != null) {
            body.put("existingAgentId", e.getExistingAgentId().toString());
        }
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /** Without request context (the write is taken to be a create). */
    public ResponseEntity<Map<String, Object>> handleDataIntegrity(DataIntegrityViolationException e) {
        return handleDataIntegrity(e, null);
    }

    /**
     * An update of an existing agent (PUT/PATCH on a path carrying its {@code id}) that loses the
     * race renamed or re-activated THAT agent, so it gets the rename advice, and its own current
     * name counts as free in the suggestion. Anything else is a create (POST create, clone,
     * marketplace install).
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleDataIntegrity(DataIntegrityViolationException e,
                                                                   jakarta.servlet.http.HttpServletRequest request) {
        UUID renamedAgentId = renamedAgentId(request);
        java.util.function.BinaryOperator<String> suggester = agentService == null ? null
                : renamedAgentId != null
                        ? (org, name) -> agentService.allocateAgentNameForRename(org, name, renamedAgentId)
                        : (org, name) -> agentService.allocateAgentName(org, name);
        var nameConflict = AgentNameConflictException.fromIndexViolation(e, suggester, renamedAgentId != null);
        if (nameConflict.isPresent()) {
            log.warn("Agent name conflict caught at the unique index: {}", e.getMostSpecificCause().getMessage());
            return handleAgentNameConflict(nameConflict.get());
        }
        return otherIntegrityErrors.handleDataIntegrity(e);
    }

    /** The {@code id} path variable of a PUT/PATCH (an update), else null. */
    private static UUID renamedAgentId(jakarta.servlet.http.HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String method = request.getMethod();
        if (!"PUT".equalsIgnoreCase(method) && !"PATCH".equalsIgnoreCase(method)) {
            return null;
        }
        Object vars = request.getAttribute(
                org.springframework.web.servlet.HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (!(vars instanceof Map<?, ?> map) || !(map.get("id") instanceof String id)) {
            return null;
        }
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException notAnId) {
            return null;
        }
    }
}
