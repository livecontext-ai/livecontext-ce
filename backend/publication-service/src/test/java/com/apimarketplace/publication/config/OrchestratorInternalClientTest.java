package com.apimarketplace.publication.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@DisplayName("OrchestratorInternalClient")
class OrchestratorInternalClientTest {

    private static final String BASE_URL = "http://orchestrator";
    private static final String TENANT_ID = "42";
    private static final String ORG_ID = "22222222-2222-4222-8222-222222222222";

    private MockRestServiceServer server;
    private OrchestratorInternalClient client;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.createServer(restTemplate);
        client = new OrchestratorInternalClient(restTemplate, BASE_URL);
    }

    @Test
    @DisplayName("existsBySourcePublication sends organization scope to orchestrator")
    void existsBySourcePublicationSendsOrganizationScope() {
        UUID publicationId = UUID.randomUUID();
        String expectedUrl = BASE_URL + "/api/internal/publication-support/workflows/exists-by-source"
                + "?pubId=" + publicationId
                + "&tenantId=" + TENANT_ID
                + "&organizationId=" + ORG_ID;
        server.expect(requestTo(expectedUrl))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-User-ID", TENANT_ID))
                .andExpect(header("X-Organization-ID", ORG_ID))
                .andRespond(withSuccess("true", MediaType.APPLICATION_JSON));

        boolean exists = client.existsBySourcePublication(publicationId, TENANT_ID, ORG_ID);

        assertThat(exists).isTrue();
        server.verify();
    }

    @Test
    @DisplayName("getAcquiredWorkflows sends organization scope to orchestrator")
    void getAcquiredWorkflowsSendsOrganizationScope() {
        String expectedUrl = BASE_URL + "/api/internal/publication-support/workflows/acquired/" + TENANT_ID
                + "?organizationId=" + ORG_ID;
        server.expect(requestTo(expectedUrl))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-User-ID", TENANT_ID))
                .andExpect(header("X-Organization-ID", ORG_ID))
                .andRespond(withSuccess("""
                        [{"id":"workflow-1","organizationId":"22222222-2222-4222-8222-222222222222"}]
                        """, MediaType.APPLICATION_JSON));

        List<Map<String, Object>> workflows = client.getAcquiredWorkflows(TENANT_ID, ORG_ID);

        assertThat(workflows).hasSize(1);
        assertThat(workflows.getFirst()).containsEntry("organizationId", ORG_ID);
        server.verify();
    }

    @Test
    @DisplayName("getWorkflowForPublication sends organization scope to orchestrator")
    void getWorkflowForPublicationSendsOrganizationScope() {
        UUID workflowId = UUID.randomUUID();
        String expectedUrl = BASE_URL + "/api/internal/publication-support/workflows/" + workflowId + "/for-publication";
        server.expect(requestTo(expectedUrl))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-User-ID", TENANT_ID))
                .andExpect(header("X-Organization-ID", ORG_ID))
                .andRespond(withSuccess("""
                        {"id":"%s","tenantId":"teammate","organizationId":"%s","plan":{}}
                        """.formatted(workflowId, ORG_ID), MediaType.APPLICATION_JSON));

        Map<String, Object> workflow = client.getWorkflowForPublication(workflowId, TENANT_ID, ORG_ID);

        assertThat(workflow).containsEntry("organizationId", ORG_ID);
        server.verify();
    }

    @Test
    @DisplayName("createApplicationWorkflow forwards organization scope from request body")
    void createApplicationWorkflowForwardsOrganizationScopeFromBody() {
        String expectedUrl = BASE_URL + "/api/internal/publication-support/workflows/create-application";
        server.expect(requestTo(expectedUrl))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-User-ID", TENANT_ID))
                .andExpect(header("X-Organization-ID", ORG_ID))
                .andRespond(withSuccess("""
                        {"id":"workflow-1"}
                        """, MediaType.APPLICATION_JSON));

        Map<String, Object> result = client.createApplicationWorkflow(
                Map.of("organizationId", ORG_ID, "title", "Acquired App"),
                TENANT_ID);

        assertThat(result).containsEntry("id", "workflow-1");
        server.verify();
    }

    @Test
    @DisplayName("getWorkflowIdsByTenant sends organization scope to orchestrator")
    void getWorkflowIdsByTenantSendsOrganizationScope() {
        String expectedUrl = BASE_URL + "/api/internal/publication-support/workflows/ids-by-tenant/" + TENANT_ID
                + "?organizationId=" + ORG_ID;
        server.expect(requestTo(expectedUrl))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-User-ID", TENANT_ID))
                .andExpect(header("X-Organization-ID", ORG_ID))
                .andRespond(withSuccess("[\"workflow-1\"]", MediaType.APPLICATION_JSON));

        List<String> ids = client.getWorkflowIdsByTenant(TENANT_ID, ORG_ID);

        assertThat(ids).containsExactly("workflow-1");
        server.verify();
    }

    @Test
    @DisplayName("validateShowcaseRun sends organization scope to orchestrator")
    void validateShowcaseRunSendsOrganizationScope() {
        String runId = "run_123";
        String expectedUrl = BASE_URL + "/api/internal/publication-support/runs/" + runId + "/validate-showcase";
        server.expect(requestTo(expectedUrl))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-User-ID", TENANT_ID))
                .andExpect(header("X-Organization-ID", ORG_ID))
                .andRespond(withSuccess("""
                        {"runIdPublic":"run_123","publishable":true}
                        """, MediaType.APPLICATION_JSON));

        Map<String, Object> result = client.validateShowcaseRun(runId, TENANT_ID, ORG_ID);

        assertThat(result).containsEntry("publishable", true);
        server.verify();
    }

    @Test
    @DisplayName("captureShowcaseSnapshot sends organization scope and epoch filter to orchestrator")
    void captureShowcaseSnapshotSendsOrganizationScopeAndEpochFilter() {
        String runId = "run_123";
        String expectedUrl = BASE_URL + "/api/internal/publication-support/runs/" + runId + "/full-snapshot"
                + "?tenantId=" + TENANT_ID
                + "&organizationId=" + ORG_ID
                + "&epochFilter=4";
        server.expect(requestTo(expectedUrl))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-User-ID", TENANT_ID))
                .andExpect(header("X-Organization-ID", ORG_ID))
                .andRespond(withSuccess("""
                        {"version":1,"epochFilter":4}
                        """, MediaType.APPLICATION_JSON));

        Map<String, Object> snapshot = client.captureShowcaseSnapshot(runId, TENANT_ID, ORG_ID, 4);

        assertThat(snapshot).containsEntry("epochFilter", 4);
        server.verify();
    }

    @Test
    @DisplayName("getInterfaceSnapshotsForRun sends organization scope to orchestrator")
    void getInterfaceSnapshotsForRunSendsOrganizationScope() {
        String runId = "run_123";
        String expectedUrl = BASE_URL + "/api/internal/publication-support/runs/" + runId + "/interface-snapshots"
                + "?tenantId=" + TENANT_ID
                + "&organizationId=" + ORG_ID;
        server.expect(requestTo(expectedUrl))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-User-ID", TENANT_ID))
                .andExpect(header("X-Organization-ID", ORG_ID))
                .andRespond(withSuccess("""
                        {"iface-1":{"variableMappings":{},"actionMappings":{}}}
                        """, MediaType.APPLICATION_JSON));

        Map<String, Map<String, Object>> snapshots = client.getInterfaceSnapshotsForRun(runId, TENANT_ID, ORG_ID);

        assertThat(snapshots).containsKey("iface-1");
        server.verify();
    }
}
