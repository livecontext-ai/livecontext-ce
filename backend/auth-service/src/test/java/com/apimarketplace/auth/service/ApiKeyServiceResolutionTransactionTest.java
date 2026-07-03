package com.apimarketplace.auth.service;

import com.apimarketplace.auth.dto.UserResolutionResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression: resolveByPlaintextKey used to run @Transactional(readOnly = true), but it
 * delegates to UserResolutionService.resolveUser, which is DELIBERATELY non-transactional
 * and performs writes in their own transactions (ensureFreeSubscription INSERT, atomic
 * last-login update). Inside an enclosing read-only transaction the first such write marked
 * it rollback-only, and the commit failed with UnexpectedRollbackException: a 500 on every
 * API-key resolution for a fresh or 10-min-idle user, in both editions (cloud
 * /resolve-by-api-key and the CE MonolithSecurityFilter resolver). Surfaced live by the
 * MCP e2e suite (CE-MCP-STREAMABLE-001.6). The behavioral proof is that e2e scenario;
 * this test pins the transaction contract so the annotation cannot silently regress.
 */
@DisplayName("ApiKeyService resolution transaction contract")
class ApiKeyServiceResolutionTransactionTest {

    @Test
    @DisplayName("resolveByPlaintextKey must not wrap resolveUser's own write transactions")
    void resolveByPlaintextKeyRunsOutsideAnyEnclosingTransaction() throws Exception {
        Method method = ApiKeyService.class.getMethod("resolveByPlaintextKey", String.class);
        Transactional tx = method.getAnnotation(Transactional.class);

        assertThat(tx)
                .as("method-level @Transactional must override the class-level default")
                .isNotNull();
        assertThat(tx.propagation())
                .as("must suspend any enclosing transaction (readOnly wrapping broke resolution)")
                .isEqualTo(Propagation.NOT_SUPPORTED);
        assertThat(method.getReturnType()).isEqualTo(UserResolutionResponse.class);
    }
}
