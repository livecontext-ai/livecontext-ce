package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.domain.workflow.Trigger;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.services.InterfaceRenderService;
import com.apimarketplace.orchestrator.services.template.ReportedParams;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * No credential may reach `resolved_params`, on any exit path, for any node.
 *
 * That map is copied into `workflow_step_data.input_data` and rendered in the
 * inspector's Params column, so a value that AUTHENTICATES is published to
 * everyone who can read the run the moment it is put there. The nodes that hold
 * one are careful about it today; nothing made that carefulness a rule, so a
 * future `resolvedParams.put("password", password)` would have shipped with the
 * whole suite green.
 *
 * Each case drives a node with a distinctive secret and asserts the secret's
 * VALUE appears nowhere in the reported map, keys or values, at any depth. Value
 * matching rather than key matching on purpose: a leak rarely arrives under a key
 * called "password" - it arrives inside a url, a connection string or a nested
 * config object, which is exactly how the api-key-in-query leak reached the
 * column.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("resolved_params never carries a credential")
class ResolvedParamsSecretExclusionTest {

    private static final String SECRET = "s3cr3t-should-never-be-persisted";

    @Mock private WorkflowPlan mockPlan;

    private ExecutionContext context() {
        return ExecutionContext.create("run-1", "wr-1", "tenant-1", "item-0", 0, Map.of(), mockPlan);
    }

    /** Every string anywhere in the reported map, keys and values, at any depth. */
    private static void assertNoSecret(Object resolvedParams, String where) {
        assertNotNull(resolvedParams, where + " must report something");
        String flattened = flatten(resolvedParams);
        assertFalse(flattened.contains(SECRET),
            where + " leaked a credential into resolved_params: " + flattened);
    }

    private static String flatten(Object value) {
        if (value == null) return "";
        if (value instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                sb.append(e.getKey()).append('=').append(flatten(e.getValue())).append(';');
            }
            return sb.toString();
        }
        if (value instanceof Iterable<?> items) {
            StringBuilder sb = new StringBuilder();
            for (Object item : items) sb.append(flatten(item)).append(',');
            return sb.toString();
        }
        if (value.getClass().isArray()) {
            // Arrays, or every assertNoSecret in this file is blind to the one type the
            // gate grew an explicit walk for: an Object[] falls through to String.valueOf,
            // which renders `[Ljava.lang.Object;@1b6d` and hides whatever is inside it.
            StringBuilder sb = new StringBuilder();
            int length = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < length; i++) {
                sb.append(flatten(java.lang.reflect.Array.get(value, i))).append(',');
            }
            return sb.toString();
        }
        return String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static Object paramsOf(NodeExecutionResult result) {
        return result.output().get("resolved_params");
    }

    @Test
    @DisplayName("ssh keeps its password and private key out")
    void sshExcludesCredentials() {
        Core.SshConfig config = new Core.SshConfig(
            "host", 22, "user", "password", SECRET, SECRET, "ls -la", 30, null);
        SshNode node = new SshNode("core:ssh", config);

        assertNoSecret(paramsOf(node.execute(context())), "ssh");
    }

    @Test
    @DisplayName("sftp keeps its password and private key out")
    void sftpExcludesCredentials() {
        Core.SftpConfig config = new Core.SftpConfig(
            "host", 22, "user", "password", SECRET, SECRET, "upload", "/remote", null, null, 30, null);
        SftpNode node = new SftpNode("core:sftp", config);

        assertNoSecret(paramsOf(node.execute(context())), "sftp");
    }

    @Test
    @DisplayName("database keeps its password out")
    void databaseExcludesPassword() {
        Core.DatabaseConfig config = new Core.DatabaseConfig(
            "postgresql", "host", 5432, "db", "user", SECRET, false, "SELECT 1", null, "query", 30, null);
        DatabaseNode node = new DatabaseNode("core:db", config);

        assertNoSecret(paramsOf(node.execute(context())), "database");
    }

    @Test
    @DisplayName("an api key placed in the QUERY STRING is masked, not published through the url")
    void httpRequestMasksApiKeyInUrl() {
        // The leak this test exists for: the key is not put under a key called
        // "apiKeyValue", it is concatenated into the url that IS reported. Masking
        // it by parameter name is what keeps the url useful and the key private.
        Core.HttpAuthConfig auth = new Core.HttpAuthConfig(
            null, null, null, "api_key", SECRET, "query", null, null);
        HttpRequestNode node = new HttpRequestNode(
            "core:http", "GET", "https://example.invalid/v1/items", "api-key", auth,
            List.of(), List.of(), null, null, null, 5);

        NodeExecutionResult result = node.execute(context());

        Object params = paramsOf(result);
        assertNoSecret(params, "http_request");
        // The url is reported either way, so the request stays diagnosable. On this
        // path it is still the pre-append one - the node fails before enriching -
        // which is itself the point: the ONLY url that ever carries the key is the
        // enriched one, and that one goes through the mask.
        assertTrue(flatten(params).contains("https://example.invalid/v1/items"),
            "the url must remain visible: " + flatten(params));
        assertTrue(flatten(params).contains("apiKeyName=api_key"),
            "the parameter NAME is not a secret and is what identifies the masked value");
    }


    @Test
    @DisplayName("a webhook trigger keeps its basic password, header value and JWT secret out of the WHOLE row")
    void triggerExcludesWebhookAuthSecrets() {
        // The widest exposure of all of them: TriggerCreator puts basicPassword /
        // authHeaderValue / jwtSecretKey into the trigger's params, TriggerNode copied that
        // whole map, and a trigger reports on EVERY epoch of every run. The scheme and the
        // header NAME stay, because a 401 on a secured webhook is diagnosed with those.
        //
        // Asserted on the whole output, not on resolved_params alone, and with a template
        // adapter WIRED. Without one `resolveTriggerParams` returns an empty map and the
        // node takes its no-resolution fallback: the earlier version of this test ran in
        // exactly the configuration where the leak cannot happen, and stayed green while
        // `output.basicPassword` carried the secret into output_data, into the Output
        // column and into every downstream template.
        Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("authType", "jwt");
        params.put("authHeaderName", "X-Signature");
        params.put("basicUsername", "svc-account");
        params.put("basicPassword", SECRET);
        params.put("authHeaderValue", SECRET);
        params.put("jwtSecretKey", SECRET);
        params.put("jwtAlgorithm", "HS256");
        params.put("customerId", "cus_42");
        Trigger trigger = new Trigger("webhook-1", "incoming", "single", "webhook", params, null);
        TriggerNode node = new TriggerNode("trigger:incoming", trigger);
        // Resolves every template to itself, which is what a webhook's literal auth config
        // does in production: the values arrive already literal and come straight back.
        com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter adapter =
            org.mockito.Mockito.mock(
                com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter.class);
        org.mockito.Mockito.when(adapter.resolveTemplates(
                org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.any()))
            .thenReturn(params);
        node.setTemplateAdapter(adapter);

        NodeExecutionResult result = node.execute(context());

        assertNoSecret(paramsOf(result), "trigger");
        // The whole row, both columns. output_data is as public as input_data and lives as
        // long as the run.
        String wholeOutput = flatten(result.output());
        assertFalse(wholeOutput.contains(SECRET),
            "no column of the row may carry the credential: " + wholeOutput);
        // And the node still hands the DAG what an author actually mapped forward. Masking
        // the output by the credential word rules would not hide a row, it would break the
        // run, so the exclusion is by name and covers the three auth-config keys only.
        assertTrue(result.output().containsKey("customerId"),
            "an author's own mapped value still reaches {{trigger:x.output.customerId}}: " + wholeOutput);
        String flat = flatten(paramsOf(result));
        assertTrue(flat.contains("authType=jwt"), "the scheme identifies what ran: " + flat);
        assertTrue(flat.contains("authHeaderName=X-Signature"), "the header NAME is not a secret: " + flat);
        assertTrue(flat.contains("basicUsername=svc-account"), "nor is the account: " + flat);
    }

    @Test
    @DisplayName("a download url keeps the credential in its query string out, and stays readable")
    void downloadFileMasksASignedUrl() {
        // A signed link IS the credential: the provider puts it in the query string, and
        // this node reports the url on both its exit paths.
        DownloadFileNode node = DownloadFileNode.builder()
            .nodeId("core:download")
            .urlExpression("https://files.invalid/report.pdf?token=" + SECRET + "&page=2")
            .build();

        Object reported = paramsOf(node.execute(context()));

        assertNoSecret(reported, "download_file");
        String flat = flatten(reported);
        assertTrue(flat.contains("files.invalid/report.pdf"), "the url must stay readable: " + flat);
        assertTrue(flat.contains("page=2"), "and its ordinary parameters with it: " + flat);
    }

    @Test
    @DisplayName("an http request keeps a client_secret in its BODY out, and reports the rest of it")
    void httpRequestMasksASecretInTheBody() {
        // An OAuth token exchange posts the secret in the body, which was reported whole.
        // The request has to actually REACH the enrichment for this to prove anything -
        // the older api-key case in this class never does, which is why it can only assert
        // the absence. A stubbed RestTemplate gets us there, and the node timeout must stay
        // NULL for the stub to be used at all: a configured one makes `resolveRestTemplate`
        // build its own template, and the test then went over the real network to time out.
        HttpRequestNode node = new HttpRequestNode(
            "core:http", "POST", "http://example.com/oauth/token", "none", null,
            List.of(), List.of(),
            "json", "{\"grant_type\":\"client_credentials\",\"client_secret\":\"" + SECRET + "\"}",
            "application/json", null);
        org.springframework.web.client.RestTemplate restTemplate =
            org.mockito.Mockito.mock(org.springframework.web.client.RestTemplate.class);
        org.mockito.Mockito.when(restTemplate.exchange(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(String.class)))
            .thenReturn(new org.springframework.http.ResponseEntity<>(
                "{}", org.springframework.http.HttpStatus.OK));
        node.setRestTemplate(restTemplate);

        Object reported = paramsOf(node.execute(context()));

        assertNoSecret(reported, "http_request body");
        assertTrue(flatten(reported).contains("client_credentials"),
            "the rest of the body stays, or the panel stops being diagnosable: " + flatten(reported));
    }

    @Test
    @DisplayName("an http request reports its header NAMES and its query parameters, with `key=` masked like the url's")
    void httpRequestReportsHeaderNamesAndMasksTheQueryKeyParam() {
        // Two halves of one row. Before this work the node reported neither: `headers` and
        // `queryParams` were the strings "3 header(s)" and "2 param(s)", which answer no
        // question a 401 or a 415 raises. Reporting them for real puts a credential back on
        // the table, so each half has its own rule - header NAMES only, because the values
        // are the part that authenticates; query parameters with their values, masked by the
        // URL predicate, which is what the url on the line above uses. The two predicates
        // differ: to the map rule a parameter literally named `key` is a map-entry name, to
        // the URL rule `?key=` IS the credential, and every Google API sends exactly that.
        HttpRequestNode node = new HttpRequestNode(
            "core:http", "GET", "https://maps.googleapis.com/maps/api/geocode/json", "none", null,
            List.of(new Core.HttpParam("q1", "key", SECRET),
                    new Core.HttpParam("q2", "address", "1600 Amphitheatre Parkway")),
            List.of(new Core.HttpParam("h1", "Authorization", "Bearer " + SECRET),
                    new Core.HttpParam("h2", "Accept", "application/json")),
            null, null, null, null);
        org.springframework.web.client.RestTemplate restTemplate =
            org.mockito.Mockito.mock(org.springframework.web.client.RestTemplate.class);
        org.mockito.Mockito.when(restTemplate.exchange(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(String.class)))
            .thenReturn(new org.springframework.http.ResponseEntity<>(
                "{}", org.springframework.http.HttpStatus.OK));
        node.setRestTemplate(restTemplate);

        Object reported = paramsOf(node.execute(context()));

        assertNoSecret(reported, "http_request headers/queryParams");
        String flat = flatten(reported);
        assertTrue(flat.contains("Authorization"),
            "a header NAME identifies the scheme that failed: " + flat);
        assertTrue(flat.contains("Accept"),
            "and the ordinary ones are what a 415 is read with: " + flat);
        assertTrue(flat.contains("address=1600 Amphitheatre Parkway"),
            "a non-credential query parameter keeps its value: " + flat);
        assertTrue(flat.contains("key=" + com.apimarketplace.orchestrator.services.template
                .ReportedParams.WITHHELD_CREDENTIAL),
            "and `key` is masked here exactly as it is inside the url: " + flat);
    }

    @Test
    @DisplayName("an agent keeps a `credentials` param out, whatever the author put in it")
    void agentExcludesCredentialParams() {
        // The default branch of the agent's report copies the author's OWN template params,
        // so a `credentials` entry among them went straight onto the step row. Remove the
        // gate from AgentNode and this is the only test that notices.
        Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("credentials", SECRET);
        params.put("api_key", SECRET);
        com.apimarketplace.orchestrator.domain.workflow.Agent agent =
            new com.apimarketplace.orchestrator.domain.workflow.Agent(
                "agent-1", "agent", "Writer", null, null, "openai", "gpt-4o",
                "You write", "Write something", 0.7, 4096, 10, 5, List.of(), null,
                params, List.of(), null, List.of(), null, null);
        AgentNode node = new AgentNode("agent:writer", agent);

        Object reported = paramsOf(node.execute(context()));

        assertNoSecret(reported, "agent");
        // And the counter-check the masking regression is about: a count that happens to
        // contain a credential word stays readable.
        assertTrue(flatten(reported).contains("maxTokens=4096"),
            "maxTokens is a count, not a credential: " + flatten(reported));
    }

    @Test
    @DisplayName("a tool step keeps a credential-named argument out of what it reports")
    void stepNodeExcludesCredentialArguments() {
        // A tool argument can be a token the author typed or a {{$vars.secret}} the engine
        // resolved a moment ago; both were persisted verbatim. The map handed to the
        // gateway is untouched - only the reported copy is masked.
        Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("api_key", SECRET);
        params.put("query", "SELECT 1");
        com.apimarketplace.orchestrator.domain.workflow.Step step =
            new com.apimarketplace.orchestrator.domain.workflow.Step(
                "tool-1", "mcp", "Call API", null, params, null, null, null);
        StepNode node = new StepNode("mcp:call_api", step);

        Object reported = paramsOf(node.execute(context()));

        assertNoSecret(reported, "step");
        assertTrue(flatten(reported).contains("SELECT 1"),
            "the rest of the arguments stay, or the panel stops being diagnosable: " + flatten(reported));
    }

    @Test
    @DisplayName("a set node assigning from a workspace variable reports the assignment, not the secret")
    void setWithholdsAWorkspaceVariableValue() {
        // The field is named `greeting` deliberately. Named `api_token` - as it was - the
        // key-NAME rule masks it inside forReport before `valueFrom` is ever consulted, so
        // the test passed with the whole workspace-variable rule deleted from SetNode: it
        // asserted a different rule than the one it is named for. A name no word rule
        // matches is what makes this a test of the $vars vector.
        Core.SetConfig config = new Core.SetConfig(
            List.of(new Core.SetFieldAssignment("greeting", "{{$vars.welcome_message}}", "string")),
            true, null);
        SetNode node = new SetNode("core:set", config);
        // The resolution really does hand back the workspace variable's value - that is
        // the point of $vars - so the mock returns it and the node must not report it.
        com.apimarketplace.orchestrator.services.TemplateEngine engine =
            org.mockito.Mockito.mock(com.apimarketplace.orchestrator.services.TemplateEngine.class);
        org.mockito.Mockito.when(engine.evaluateTemplate(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(
                    com.apimarketplace.orchestrator.domain.WorkflowExecutionContext.class)))
            .thenReturn(SECRET);
        node.setTemplateAdapter(
            new com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter(engine));

        Object reported = paramsOf(node.execute(context()));

        assertNoSecret(reported, "set");
        assertTrue(flatten(reported).contains("greeting"), "the assignment still reads: " + flatten(reported));
        // By CONSTANT, and the workspace one specifically: the two markers say different
        // things about why a value is absent, and a test that cannot tell them apart cannot
        // tell a working rule from the wrong rule firing.
        assertTrue(
            flatten(reported).contains(
                com.apimarketplace.orchestrator.services.template.ReportedParams
                    .WITHHELD_WORKSPACE_VARIABLE),
            "the reader must be told the value came from a workspace variable: " + flatten(reported));
    }

    @Test
    @DisplayName("an interface variable fed by a WORKSPACE variable reports its wiring, never its value")
    void interfaceWithholdsAWorkspaceVariableValue() {
        // A workspace variable can be declared secret, and the interface node reports what
        // each template variable held when it ran. Everything else a mapping can address is
        // the run's own data, already persisted and already in the Output column; $vars is
        // the one source whose scalar content must not be copied here.
        InterfaceRenderService renderService =
            org.mockito.Mockito.mock(InterfaceRenderService.class);
        org.mockito.Mockito.when(renderService.resolveVariablesForReporting(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
            .thenReturn(Map.of("token", SECRET));
        InterfaceNode node = new InterfaceNode(
            "interface:form", "11111111-2222-3333-4444-555555555555", Map.of(), false);
        node.setVariableMapping(Map.of("token", "{{$vars.api_token}}"));
        com.apimarketplace.orchestrator.execution.v2.engine.ServiceRegistry registry =
            org.mockito.Mockito.mock(
                com.apimarketplace.orchestrator.execution.v2.engine.ServiceRegistry.class);
        org.mockito.Mockito.when(registry.getInterfaceRenderService()).thenReturn(renderService);
        node.acceptServices(registry);

        Object params = paramsOf(node.execute(context()));

        assertNoSecret(params, "interface");
        // The wiring still reads, which is what the panel is opened for.
        assertTrue(flatten(params).contains("{{$vars.api_token}}"),
            "the expression is not a secret and is the whole diagnosis: " + flatten(params));
        assertTrue(flatten(params).contains(InterfaceNode.WITHHELD),
            "and the reader must be told the value was withheld rather than absent");
    }

    @Test
    @DisplayName("an interface variable fed by the run's OWN data is described normally")
    void interfaceDescribesWorkflowDataNormally() {
        // The counterpart, so the rule above cannot quietly become "never report a value":
        // a mapping onto a node's output describes what it held, because that data is
        // already in the run and already in the Output column.
        InterfaceRenderService renderService =
            org.mockito.Mockito.mock(InterfaceRenderService.class);
        org.mockito.Mockito.when(renderService.resolveVariablesForReporting(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
            .thenReturn(Map.of("title", "Quarterly report"));
        InterfaceNode node = new InterfaceNode(
            "interface:form", "11111111-2222-3333-4444-555555555555", Map.of(), false);
        node.setVariableMapping(Map.of("title", "{{core:prepare.output.title}}"));
        com.apimarketplace.orchestrator.execution.v2.engine.ServiceRegistry registry =
            org.mockito.Mockito.mock(
                com.apimarketplace.orchestrator.execution.v2.engine.ServiceRegistry.class);
        org.mockito.Mockito.when(registry.getInterfaceRenderService()).thenReturn(renderService);
        node.acceptServices(registry);

        assertTrue(flatten(paramsOf(node.execute(context()))).contains("Quarterly report"));
    }

    /** A template adapter that resolves any expression to {@code value}. */
    private static com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter
            adapterResolving(Object value) {
        var adapter = org.mockito.Mockito.mock(
            com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter.class);
        org.mockito.Mockito.when(adapter.resolveTemplates(
                org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.any()))
            .thenAnswer(call -> {
                Map<String, Object> resolved = new java.util.LinkedHashMap<>();
                ((Map<?, ?>) call.getArgument(0)).forEach((k, ignored) ->
                    resolved.put(String.valueOf(k), value));
                return resolved;
            });
        return adapter;
    }

    @Test
    @DisplayName("convert_to_file keeps a credential-named column of the rows it converted out")
    void convertToFileMasksACredentialColumn() {
        // This node and `xml` below report a RESOLVED upstream value under `value`, and an
        // upstream row carries whatever its producer put in it. They built that value with a
        // bounds-only call and never passed their map through the gate, so a small export of
        // accounts published the column the author called `password` in full. (`compression`
        // shares the shape but not the test: its resolver coerces to String, so what it
        // reports is text, and masking arbitrary text by pattern would corrupt the one value
        // a reader compares against what they sent.)
        ConvertToFileNode node = ConvertToFileNode.builder()
            .nodeId("core:to_csv")
            .convertToFileConfig(new Core.ConvertToFileConfig(
                "csv", "{{core:fetch.output.rows}}", "accounts.csv", ",", "yes"))
            .build();
        node.setTemplateAdapter(adapterResolving(List.of(
            new java.util.LinkedHashMap<>(Map.of("user", "bob", "password", SECRET)))));

        Object reported = paramsOf(node.execute(context()));

        assertNoSecret(reported, "convert_to_file");
        assertTrue(flatten(reported).contains("bob"),
            "the rest of the row stays, or the panel stops being diagnosable: " + flatten(reported));
    }

    @Test
    @DisplayName("xml keeps a credential-named field of the document it built out")
    void xmlMasksACredentialField() {
        XmlNode node = XmlNode.builder()
            .nodeId("core:xml")
            .xmlConfig(new Core.XmlConfig(
                "jsonToXml", "{{core:fetch.output.doc}}", "root", true))
            .build();
        node.setTemplateAdapter(adapterResolving(
            new java.util.LinkedHashMap<>(Map.of("account", "svc", "apiKey", SECRET))));

        Object reported = paramsOf(node.execute(context()));

        assertNoSecret(reported, "xml");
        assertTrue(flatten(reported).contains("svc"),
            "and the rest of the document with it: " + flatten(reported));
    }

    @Test
    @DisplayName("an http request masks a credential inside a JSON ARRAY body, the bulk-POST shape")
    void httpRequestMasksASecretInAnArrayBody() {
        // The body test above uses a JSON object, which is the shape that always worked: an
        // object takes the Map branch. A bulk POST is a top-level ARRAY, which is neither of
        // the two shapes the reporter recognised, so it took the bounds-only path and the
        // whole list of records went onto the row with its `password` column intact.
        HttpRequestNode node = new HttpRequestNode(
            "core:http", "POST", "http://example.com/v1/users/bulk", "none", null,
            List.of(), List.of(),
            "json", "[{\"user\":\"bob\",\"password\":\"" + SECRET + "\"}]",
            "application/json", null);
        node.setRestTemplate(okRestTemplate());

        Object reported = paramsOf(node.execute(context()));

        assertNoSecret(reported, "http_request array body");
        assertTrue(flatten(reported).contains("bob"),
            "the rest of the record stays: " + flatten(reported));
    }

    @Test
    @DisplayName("an http request that fails BEFORE the enrichment still masks the url it was configured with")
    void httpRequestMasksTheConfiguredUrlOnAnEarlyFailure() {
        // resolved_params is built at the top of execute() so every exit path can report it,
        // and the masking used to live only on the line AFTER the request was prepared. Any
        // failure before that point - an SSRF rejection, a header or body that throws, a
        // missing url - published the configured url whole, and an author who pastes a
        // signed link into the url field has put the credential in the query string.
        // No RestTemplate here, which is one such failure: the node exits before preparing
        // anything.
        HttpRequestNode node = new HttpRequestNode(
            "core:http", "GET", "https://files.invalid/report.pdf?token=" + SECRET + "&page=2",
            "none", null, List.of(), List.of(), null, null, null, null);

        Object reported = paramsOf(node.execute(context()));

        assertNoSecret(reported, "http_request early failure");
        String flat = flatten(reported);
        assertTrue(flat.contains("files.invalid/report.pdf"), "the url must stay readable: " + flat);
        assertTrue(flat.contains("page=2"), "and its ordinary parameters with it: " + flat);
    }

    /** A RestTemplate that answers every exchange with an empty 200. */
    private static org.springframework.web.client.RestTemplate okRestTemplate() {
        org.springframework.web.client.RestTemplate restTemplate =
            org.mockito.Mockito.mock(org.springframework.web.client.RestTemplate.class);
        org.mockito.Mockito.when(restTemplate.exchange(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(String.class)))
            .thenReturn(new org.springframework.http.ResponseEntity<>(
                "{}", org.springframework.http.HttpStatus.OK));
        return restTemplate;
    }

    @Test
    @DisplayName("the gate masks a credential inside an ARRAY, the shape flatten used to be blind to")
    void masksACredentialInsideAnArray() {
        // `redactValue` grew an explicit array walk because an Object[] was returned as
        // itself, unwalked and unmasked, and no test in this file could see it: `flatten`
        // rendered it as `[Ljava.lang.Object;@1b6d`. This is a gate test, and it says so -
        // it does not construct a node, so it cannot stand in for one.
        Map<String, Object> llm = new java.util.LinkedHashMap<>();
        llm.put("provider", "openai");
        llm.put("api_key", SECRET);
        Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("llm", llm);
        params.put("session", SECRET);
        params.put("steps", new Object[] { Map.of("action", "click", "authToken", SECRET) });

        Object reported = ReportedParams.forReport(params);

        assertNoSecret(reported, "browser agent");
        String flat = flatten(reported);
        assertTrue(flat.contains("openai"),
            "which provider ran is not a secret and is what a failure is read with: " + flat);
        assertTrue(flat.contains("click"),
            "nor is the step the agent was told to take: " + flat);
    }

    @Test
    @DisplayName("a browser agent keeps its llm api key and its saved session out of what it reports")
    void browserAgentMasksItsKeyAndSession() {
        // Named in ReportedParams' own javadoc as one of the leaks this gate exists to close,
        // and the only node on that list with no test that runs it. An earlier version of this
        // test called ReportedParams.forReport directly and never constructed the node, so
        // both gate calls could be deleted with it still green - a useful gate test wearing a
        // node's name.
        Map<String, Object> llm = new java.util.LinkedHashMap<>();
        llm.put("provider", "openai");
        llm.put("api_key", SECRET);
        Map<String, Object> nodeConfig = new java.util.LinkedHashMap<>();
        nodeConfig.put("task", "book a table");
        nodeConfig.put("llm", llm);
        nodeConfig.put("session", SECRET);
        BrowserAgentNode node = new BrowserAgentNode("agent:browser", nodeConfig);
        // A module that declines the call, which is one of the node's real failure exits: it
        // reports the same map there as on success, and a failure is when this panel is read.
        com.apimarketplace.orchestrator.tools.websearch.BrowserAgentModule module =
            org.mockito.Mockito.mock(
                com.apimarketplace.orchestrator.tools.websearch.BrowserAgentModule.class);
        org.mockito.Mockito.when(module.execute(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenReturn(java.util.Optional.empty());
        com.apimarketplace.orchestrator.execution.v2.engine.ServiceRegistry registry =
            org.mockito.Mockito.mock(
                com.apimarketplace.orchestrator.execution.v2.engine.ServiceRegistry.class);
        org.mockito.Mockito.when(registry.getBrowserAgentModule()).thenReturn(module);
        // The node refuses to wire without one: it records an observability row locally.
        org.mockito.Mockito.when(registry.getAgentClient()).thenReturn(
            org.mockito.Mockito.mock(com.apimarketplace.agent.client.AgentClient.class));
        node.acceptServices(registry);

        Object reported = paramsOf(node.execute(context()));

        assertNoSecret(reported, "browser agent");
        assertTrue(flatten(reported).contains("openai"),
            "which provider ran is not a secret and is what a failure is read with: " + flatten(reported));
    }

    @Test
    @DisplayName("the LOG line is masked too: a log is a sink like the row, and it outlives the run")
    void masksTheValueItPrintsToTheLog() {
        // The half of the SetNode change with no test, and the half the argument rests on:
        // `resolved_params` said `<withheld: ...>` while the INFO line above it printed the
        // value in clear into logs/orchestrator-service.log, on every execution. Captured
        // through logback rather than asserted indirectly, because "the row is masked" is
        // exactly what stayed true while the log was not.
        ch.qos.logback.classic.Logger nodeLogger =
            (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(SetNode.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> captured =
            new ch.qos.logback.core.read.ListAppender<>();
        captured.start();
        nodeLogger.addAppender(captured);
        try {
            Core.SetConfig config = new Core.SetConfig(
                List.of(new Core.SetFieldAssignment("greeting", "{{$vars.welcome_message}}", "string")),
                true, null);
            SetNode node = new SetNode("core:set", config);
            com.apimarketplace.orchestrator.services.TemplateEngine engine =
                org.mockito.Mockito.mock(com.apimarketplace.orchestrator.services.TemplateEngine.class);
            org.mockito.Mockito.when(engine.evaluateTemplate(
                    org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.any(
                        com.apimarketplace.orchestrator.domain.WorkflowExecutionContext.class)))
                .thenReturn(SECRET);
            node.setTemplateAdapter(
                new com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter(engine));

            node.execute(context());

            String lines = captured.list.stream()
                .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .reduce("", (a, b) -> a + "\n" + b);
            assertFalse(lines.contains(SECRET),
                "the service log must not carry what the row withholds: " + lines);
            assertTrue(lines.contains("greeting"),
                "and the field NAME stays, or the line stops being a diagnosis: " + lines);
        } finally {
            nodeLogger.detachAppender(captured);
        }
    }
}
