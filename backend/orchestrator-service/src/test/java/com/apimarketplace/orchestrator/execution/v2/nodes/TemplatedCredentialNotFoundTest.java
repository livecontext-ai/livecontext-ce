package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.credential.client.CredentialClient;
import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ServiceRegistry;
import com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A credentialId written as {@code {{...}}} that resolves to a credential the tenant does not have
 * fails the node naming the field: upstream data chose that account, and silently running on the
 * default one would hide it. A LITERAL id keeps each node's historical behaviour.
 */
@DisplayName("ssh / sftp / database - templated credentialId not found")
class TemplatedCredentialNotFoundTest {

    private static final String TEMPLATE = "{{core:pick.output.credential}}";

    private final ExecutionContext context = ExecutionContext.create(
        "run-1", "wr-1", "tenant-1", "item-1", 0, Map.of(), mock(WorkflowPlan.class));
    private final CredentialClient credentialClient = mock(CredentialClient.class);

    private <T extends BaseNode> T wire(T node, String configKey) {
        ServiceRegistry registry = mock(ServiceRegistry.class);
        when(registry.getCredentialClient()).thenReturn(credentialClient);
        node.acceptServices(registry);
        V2TemplateAdapter adapter = mock(V2TemplateAdapter.class);
        when(adapter.resolveTemplates(anyMap(), any())).thenAnswer(TemplateResolutionStubs.resolving(Map.of(TEMPLATE, 404L)));
        node.setTemplateAdapter(adapter);
        node.setDeferredScalars(Map.of(configKey, Map.of("credentialId", TEMPLATE)));
        when(credentialClient.getCredentialById(anyString(), anyLong())).thenReturn(Optional.empty());
        return node;
    }

    private void assertFailsNamingTheField(NodeExecutionResult result, String configKey) {
        assertFalse(result.isSuccess());
        String error = result.errorMessage().orElse("");
        assertTrue(error.contains(configKey + ".credentialId") && error.contains(TEMPLATE), error);
        verify(credentialClient, never()).getDefaultCredential(anyString(), anyString());
    }

    @Test
    @DisplayName("regression: ssh never falls back to the default credential for a templated id")
    void ssh() {
        SshNode node = wire(new SshNode("core:ssh",
            new Core.SshConfig(null, null, null, null, null, null, "ls", null, null)), "ssh");

        assertFailsNamingTheField(node.execute(context), "ssh");
    }

    @Test
    @DisplayName("regression: sftp never falls back to the default credential for a templated id")
    void sftp() {
        SftpNode node = wire(new SftpNode("core:sftp",
            new Core.SftpConfig(null, null, null, null, null, null, "list", "/", null, null, null, null)), "sftp");

        assertFailsNamingTheField(node.execute(context), "sftp");
    }

    @Test
    @DisplayName("regression: database never falls back to the default credential for a templated id")
    void database() {
        DatabaseNode node = wire(new DatabaseNode("core:db",
            new Core.DatabaseConfig(null, null, null, null, null, null, null, "select 1", null, null, null, null)),
            "database");

        assertFailsNamingTheField(node.execute(context), "database");
    }

    @Test
    @DisplayName("a LITERAL ssh credentialId that is not found still falls back to the default, as before")
    void literalIdKeepsTheFallback() {
        SshNode node = new SshNode("core:ssh",
            new Core.SshConfig(null, null, null, null, null, null, "ls", null, 404L));
        ServiceRegistry registry = mock(ServiceRegistry.class);
        when(registry.getCredentialClient()).thenReturn(credentialClient);
        node.acceptServices(registry);
        when(credentialClient.getCredentialById(anyString(), anyLong())).thenReturn(Optional.empty());
        when(credentialClient.getDefaultCredential(anyString(), anyString())).thenReturn(Optional.empty());

        node.execute(context);

        verify(credentialClient).getDefaultCredential(anyString(), anyString());
    }
}
