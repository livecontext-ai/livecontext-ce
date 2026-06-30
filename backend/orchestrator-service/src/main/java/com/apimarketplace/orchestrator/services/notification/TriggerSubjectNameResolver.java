package com.apimarketplace.orchestrator.services.notification;

import org.springframework.stereotype.Component;

/**
 * Bell-side name resolver for {@link SubjectNameResolver#TRIGGER} subjects.
 * Reads {@code payload.subjectName} populated by trigger-service's
 * {@code TriggerLifecycleManager} when emitting {@code WEBHOOK_TRIGGER_DISABLED}.
 */
@Component
public class TriggerSubjectNameResolver extends PayloadSubjectNameResolver {

    @Override
    public String subjectType() {
        return SubjectNameResolver.TRIGGER;
    }
}
