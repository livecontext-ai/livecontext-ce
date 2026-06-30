package com.apimarketplace.orchestrator.services.notification;

import org.springframework.stereotype.Component;

/**
 * Bell-side name resolver for {@link SubjectNameResolver#CREDENTIAL} subjects.
 * Reads {@code payload.subjectName} populated by auth-service's
 * {@code OAuth2RefreshScheduler} when emitting {@code CRED_EXPIRED}.
 */
@Component
public class CredentialSubjectNameResolver extends PayloadSubjectNameResolver {

    @Override
    public String subjectType() {
        return SubjectNameResolver.CREDENTIAL;
    }
}
