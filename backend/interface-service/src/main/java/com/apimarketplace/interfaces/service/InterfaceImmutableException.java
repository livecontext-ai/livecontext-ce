package com.apimarketplace.interfaces.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when a write to an interface is refused because the interface is a frozen
 * clone of a marketplace publication ({@code sourcePublicationId != null}).
 *
 * <p>Mirrors the {@code OrgAccessDeniedException} pattern: a {@link RuntimeException}
 * annotated with {@link ResponseStatus} so Spring maps it to a stable HTTP status
 * (409 CONFLICT) without each controller having to translate. The agent-tool layer
 * ({@code InterfaceCrudModule.executeUpdate}) catches this type specifically and
 * re-emits it as a {@code ToolErrorCode.RESOURCE_CONFLICT} failure with
 * {@code code='INTERFACE_IMMUTABLE'} metadata plus an agent-callable next-action,
 * matching the {@code APPLICATION_PLAN_IMMUTABLE} shape on the workflow side.
 *
 * <p>Acquired interfaces are immutable in place by design: the acquirer received an
 * exact snapshot of the publisher's HTML/CSS/JS at acquisition time, and that's the
 * contract they agreed to. Letting the content drift would silently break the
 * publish/acquire isolation guarantee and defeat the {@code APPLICATION} workflow-type
 * pin that already enforces plan-side immutability.
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class InterfaceImmutableException extends RuntimeException {

    public InterfaceImmutableException(String message) {
        super(message);
    }
}
