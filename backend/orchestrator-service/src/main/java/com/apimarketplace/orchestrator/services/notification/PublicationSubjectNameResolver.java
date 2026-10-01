package com.apimarketplace.orchestrator.services.notification;

import org.springframework.stereotype.Component;

/**
 * Bell-side name resolver for {@code CREATOR_PUBLISHED}: a creator the user follows put a
 * new listing on the marketplace. publication-service owns the listing, so its emitter
 * ships the listing title inline as {@code payload.subjectName}.
 */
@Component
public class PublicationSubjectNameResolver extends PayloadSubjectNameResolver {

    @Override
    public String subjectType() {
        return SubjectNameResolver.PUBLICATION;
    }
}
