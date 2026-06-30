package com.apimarketplace.orchestrator.services.notification;

import org.springframework.stereotype.Component;

/**
 * Bell-side name resolver for {@link SubjectNameResolver#APPLICATION} subjects.
 * Reads {@code payload.subjectName} populated by application notification
 * producers when emitting application-scoped bell events.
 */
@Component
public class ApplicationSubjectNameResolver extends PayloadSubjectNameResolver {

    @Override
    public String subjectType() {
        return SubjectNameResolver.APPLICATION;
    }
}
