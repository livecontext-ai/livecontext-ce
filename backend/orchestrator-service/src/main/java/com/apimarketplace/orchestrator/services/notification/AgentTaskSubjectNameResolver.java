package com.apimarketplace.orchestrator.services.notification;

import org.springframework.stereotype.Component;

/**
 * Bell-side name resolver for {@link SubjectNameResolver#AGENT_TASK} subjects.
 * Reads {@code payload.subjectName} populated by agent-service's
 * {@code AgentTaskService.assignTask} when emitting {@code AGENT_TASK_ASSIGNED}.
 */
@Component
public class AgentTaskSubjectNameResolver extends PayloadSubjectNameResolver {

    @Override
    public String subjectType() {
        return SubjectNameResolver.AGENT_TASK;
    }
}
