package com.apimarketplace.catalog.tools;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.common.web.OrgContextHeaderForwarder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

final class CatalogToolHeaderSupport {

    private CatalogToolHeaderSupport() {
    }

    static HttpHeaders jsonHeaders(String tenantId, ToolExecutionContext context) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String effectiveTenantId = tenantId != null && !tenantId.isBlank()
                ? tenantId
                : context != null ? context.tenantId() : null;
        if (effectiveTenantId != null && !effectiveTenantId.isBlank()) {
            headers.set("X-User-ID", effectiveTenantId);
        }
        applyOrgHeaders(headers, context);
        return headers;
    }

    static void applyOrgHeaders(HttpHeaders headers, ToolExecutionContext context) {
        if (context != null) {
            if (context.orgId() != null && !context.orgId().isBlank()) {
                headers.set("X-Organization-ID", context.orgId());
            }
            if (context.orgRole() != null && !context.orgRole().isBlank()) {
                headers.set("X-Organization-Role", context.orgRole());
            }
        }
        OrgContextHeaderForwarder.forward(headers);
    }
}
