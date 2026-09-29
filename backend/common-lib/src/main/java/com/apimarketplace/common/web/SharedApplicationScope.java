package com.apimarketplace.common.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.UUID;

/**
 * Share-link scope of the current request, read from the headers the edge (cloud gateway
 * {@code AuthenticationFilter} or CE {@code MonolithSecurityFilter}) injects after resolving a
 * {@code ShareToken}. Both edges strip any client-supplied copy of these headers, so a service
 * can read them as fact.
 *
 * <p>A share token authenticates an anonymous visitor AS THE OWNER. The edge allow-list confines
 * WHICH endpoints the visitor reaches, but only the service knows which ROW a request touches, so
 * every owner-scoped read reachable from a share link must also check that the row belongs to the
 * shared application. This class is the single place that decides what the share headers mean:
 * <ul>
 *   <li>{@link Kind#NONE} - not a share request, the normal owner/org checks apply unchanged;</li>
 *   <li>{@link Kind#APPLICATION} - an APPLICATION share link; only rows bound to
 *       {@link #publicationId()} may be served;</li>
 *   <li>{@link Kind#DENY} - a share request that cannot be bound to a publication (wrong share type,
 *       missing or malformed resource token). Serve nothing.</li>
 * </ul>
 * Callers answer 404 (never 403) on a row outside the scope, so a share holder cannot probe which
 * ids exist in the owner's workspace.
 */
public record SharedApplicationScope(Kind kind, UUID publicationId) {

    public static final String HEADER_SHARE_CONTEXT = "X-Share-Context";
    public static final String HEADER_SHARE_RESOURCE_TYPE = "X-Share-Resource-Type";
    public static final String HEADER_SHARE_RESOURCE_TOKEN = "X-Share-Resource-Token";
    public static final String RESOURCE_TYPE_APPLICATION = "APPLICATION";

    public enum Kind { NONE, APPLICATION, DENY }

    private static final SharedApplicationScope NONE = new SharedApplicationScope(Kind.NONE, null);
    private static final SharedApplicationScope DENY = new SharedApplicationScope(Kind.DENY, null);

    /** Scope from raw header values (null-safe). */
    public static SharedApplicationScope of(String shareContext, String resourceType, String resourceToken) {
        if (!"true".equalsIgnoreCase(shareContext)) {
            return NONE;
        }
        if (!RESOURCE_TYPE_APPLICATION.equalsIgnoreCase(resourceType) || resourceToken == null) {
            return DENY;
        }
        try {
            return new SharedApplicationScope(Kind.APPLICATION, UUID.fromString(resourceToken.trim()));
        } catch (IllegalArgumentException e) {
            return DENY;
        }
    }

    /** Scope of the given servlet request; a null request is an internal call, {@link Kind#NONE}. */
    public static SharedApplicationScope from(HttpServletRequest request) {
        if (request == null) {
            return NONE;
        }
        return of(request.getHeader(HEADER_SHARE_CONTEXT),
                request.getHeader(HEADER_SHARE_RESOURCE_TYPE),
                request.getHeader(HEADER_SHARE_RESOURCE_TOKEN));
    }

    /** Scope of the request bound to the current thread; no servlet request means an internal call. */
    public static SharedApplicationScope current() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return from(attrs.getRequest());
        }
        return NONE;
    }

    /** True for any share request, bound or not. */
    public boolean isShare() {
        return kind != Kind.NONE;
    }

    /**
     * Whether a row tagged with {@code rowPublicationId} is inside this scope. Always true outside
     * a share context, always false for {@link Kind#DENY}.
     */
    public boolean permitsPublication(Object rowPublicationId) {
        return switch (kind) {
            case NONE -> true;
            case DENY -> false;
            case APPLICATION -> rowPublicationId != null
                    && publicationId.toString().equalsIgnoreCase(rowPublicationId.toString().trim());
        };
    }
}
