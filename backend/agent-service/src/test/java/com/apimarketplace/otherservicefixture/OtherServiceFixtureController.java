package com.apimarketplace.otherservicefixture;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * A controller OUTSIDE agent-service's packages, for AgentNameConflictExceptionHandlerTest: in
 * the CE monolith every service's controllers share one context, and the agent-name advice must
 * not take their integrity errors.
 */
@RestController
public class OtherServiceFixtureController {

    @GetMapping("/other-service/duplicate")
    public Map<String, Object> duplicate() {
        throw new DataIntegrityViolationException(
                "duplicate key value violates unique constraint \"uq_workflows_org_name\"");
    }
}
