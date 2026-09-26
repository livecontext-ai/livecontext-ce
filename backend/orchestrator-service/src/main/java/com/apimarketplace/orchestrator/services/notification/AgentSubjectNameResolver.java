package com.apimarketplace.orchestrator.services.notification;

import org.springframework.stereotype.Component;

/**
 * Bell-side name resolver for {@link SubjectNameResolver#AGENT} subjects.
 *
 * <p>Reads {@code payload.subjectName}, which agent-service puts there when an armed agent is
 * blocked with nobody to ask ({@code AGENT_AUTHORIZATION_UNREACHABLE}). Without this bean the
 * row was written and then silently dropped at read time: the bell drops any bucket whose
 * subject type has no resolver, because it has no name to show. V518 and the endpoint both
 * admitted {@code AGENT}, the insert succeeded, and the person still saw nothing.
 */
@Component
public class AgentSubjectNameResolver extends PayloadSubjectNameResolver {

    @Override
    public String subjectType() {
        return SubjectNameResolver.AGENT;
    }
}
