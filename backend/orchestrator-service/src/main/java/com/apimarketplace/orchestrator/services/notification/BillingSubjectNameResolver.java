package com.apimarketplace.orchestrator.services.notification;

import org.springframework.stereotype.Component;

/**
 * Bell-side name resolver for {@link SubjectNameResolver#BILLING} subjects.
 * Reads {@code payload.subjectName} populated by auth-service's
 * {@code CreditAlertScheduler} when emitting {@code CREDIT_LOW} /
 * {@code CREDIT_EXHAUSTED}.
 */
@Component
public class BillingSubjectNameResolver extends PayloadSubjectNameResolver {

    @Override
    public String subjectType() {
        return SubjectNameResolver.BILLING;
    }
}
