package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for the approval node's EDIT path.
 *
 * <p>The approval node keeps its configuration in a nested {@code approval} object, and
 * {@code WorkflowPlanParser.parseApprovalConfig} reads that object in camelCase only
 * ({@code timeoutMs}, {@code approverRoles}, {@code requiredApprovals}, {@code contextTemplate},
 * {@code continuationMode}). Modify deposits flat params into the nested object VERBATIM, so a
 * snake_case patch used to be written under a key nothing reads: the call reported success and
 * the run kept the old value, with no error anywhere.
 *
 * <p>That was reachable from the documentation itself: {@code node_type_documentation} advertises
 * {@code timeout_ms} / {@code approver_roles} / {@code required_approvals}, so an agent following
 * the node help hit exactly this. add_node never had the problem, because the creator normalizes
 * on the way in, which is why this half stayed invisible.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowBuilderModifier - approval snake_case params reach the keys the parser reads")
class WorkflowBuilderModifierNestedAliasTest {

    @Mock
    private WorkflowBuilderSessionStore sessionStore;

    private WorkflowBuilderModifier modifier;
    private WorkflowBuilderSession session;

    @BeforeEach
    void setUp() {
        modifier = new WorkflowBuilderModifier(sessionStore);
        session = WorkflowBuilderSession.builder()
                .sessionId("test-session")
                .tenantId("test-tenant")
                .workflowName("Test Workflow")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        Map<String, Object> approval = new LinkedHashMap<>();
        approval.put("approverRoles", List.of());
        approval.put("requiredApprovals", 1);
        approval.put("timeoutMs", 86400000L);
        // A node edited before the rewrite existed carries the alias spelling too, holding a
        // stale value nothing reads. The assertions below check it is cleaned up, not merely
        // that it was never written.
        approval.put("timeout_ms", 111);
        approval.put("approver_roles", List.of("stale"));
        approval.put("required_approvals", 9);
        approval.put("context_template", "stale");
        approval.put("continuation_mode", "all_items");
        approval.put("delegation", Map.of("channel", "telegram", "chatId", "1"));
        approval.put("contextTemplate", "Approve?");

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", "core:manager_review");
        node.put("label", "Manager Review");
        node.put("type", "approval");
        node.put("approval", approval);
        session.getCores().add(node);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> approvalConfig() {
        return (Map<String, Object>) session.getCores().get(0).get("approval");
    }

    private ToolExecutionResult modify(String key, Object value) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("node", "Manager Review");
        params.put("params", Map.of(key, value));
        return modifier.executeModifyNode(session, params);
    }

    @Test
    @DisplayName("regression: modify(timeout_ms) changes the timeout instead of silently doing nothing")
    void snakeCaseTimeoutReachesTheParsedKey() {
        ToolExecutionResult result = modify("timeout_ms", 3600000);

        assertThat(result.success()).isTrue();
        assertThat(approvalConfig().get("timeoutMs"))
                .as("parseApprovalConfig reads timeoutMs; a value parked under timeout_ms is invisible to it")
                .isEqualTo(3600000);
        assertThat(approvalConfig())
                .as("the unread spelling must not be left behind as a second, contradictory source")
                .doesNotContainKey("timeout_ms");
    }

    @ParameterizedTest(name = "modify({0}) writes {1}")
    @CsvSource({
            "timeout,          timeoutMs",
            "context_template, contextTemplate",
            "continuation_mode,continuationMode"
    })
    @DisplayName("every snake_case approval spelling is written to the camelCase key the parser reads")
    void snakeCaseSpellingsAreNormalized(String sent, String parsed) {
        Object value = "timeout".equals(sent) ? 7200000 : "per_item";

        assertThat(modify(sent, value).success()).isTrue();
        // Pin the VALUE, not just the key: timeoutMs already exists from setUp, so a regression
        // that renamed the key but dropped the value would slip past a containsKey assertion.
        assertThat(approvalConfig()).containsEntry(parsed, value).doesNotContainKey(sent);
    }

    @ParameterizedTest(name = "modify({0}) writes {1}")
    @CsvSource({
            "approver_roles,    approverRoles",
            "roles,             approverRoles"
    })
    @DisplayName("both approver-roles spellings are written to approverRoles")
    void roleSpellingsAreNormalized(String sent, String parsed) {
        assertThat(modify(sent, List.of("manager")).success()).isTrue();
        assertThat(approvalConfig().get(parsed)).isEqualTo(List.of("manager"));
        assertThat(approvalConfig()).doesNotContainKey(sent);
    }

    @Test
    @DisplayName("required_approvals is written to requiredApprovals")
    void requiredApprovalsIsNormalized() {
        assertThat(modify("required_approvals", 2).success()).isTrue();
        assertThat(approvalConfig().get("requiredApprovals")).isEqualTo(2);
        assertThat(approvalConfig()).doesNotContainKey("required_approvals");
    }

    @Test
    @DisplayName("the camelCase spelling still works and is not double-mapped")
    void camelCaseStillWorks() {
        assertThat(modify("timeoutMs", 1800000).success()).isTrue();
        assertThat(approvalConfig().get("timeoutMs")).isEqualTo(1800000);
    }

    /**
     * The per-type guard is load-bearing, not decorative: {@code timeout} means "timeout before
     * the approval expires" on an approval node and "HTTP request timeout" on an http_request
     * node, and both are nested-config core nodes reached through this same method. A flat alias
     * table would rewrite the http node's own canonical parameter into {@code timeoutMs}, which
     * {@code parseHttpRequestConfig} does not read: the request would silently revert to the
     * default timeout. Drop the node-type lookup and this test fails.
     */
    @Test
    @DisplayName("scope guard: timeout keeps its own meaning on an http_request node, it is not rewritten")
    void timeoutIsNotRewrittenOnHttpRequestNodes() {
        Map<String, Object> http = new LinkedHashMap<>();
        Map<String, Object> httpConfig = new LinkedHashMap<>();
        httpConfig.put("method", "GET");
        httpConfig.put("url", "https://example.com");
        http.put("id", "core:call_api");
        http.put("label", "Call Api");
        http.put("type", "http_request");
        http.put("httpRequest", httpConfig);
        session.getCores().add(http);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("node", "Call Api");
        params.put("params", Map.of("timeout", 5000));
        assertThat(modifier.executeModifyNode(session, params).success()).isTrue();

        assertThat(nestedConfigOf("Call Api", "httpRequest"))
                .as("timeout is http_request's OWN canonical parameter, it must survive untouched")
                .containsEntry("timeout", 5000)
                .doesNotContainKey("timeoutMs");
    }

    @ParameterizedTest(name = "download_file modify({0}) writes {1}")
    @CsvSource({
            "source,    url",
            "link,      url",
            "file_url,  url",
            "href,      url",
            "src,       url",
            "file_name, filename",
            "output,    filename"
    })
    @DisplayName("regression: download_file aliases the node help advertises reach the keys the parser reads")
    void downloadFileAliasesAreNormalized(String sent, String parsed) {
        Map<String, Object> dl = new LinkedHashMap<>();
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("url", "https://old.example/a.zip");
        dl.put("id", "core:get_file");
        dl.put("label", "Get File");
        dl.put("type", "download_file");
        dl.put("download", cfg);
        session.getCores().add(dl);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("node", "Get File");
        params.put("params", Map.of(sent, "https://new.example/b.zip"));
        assertThat(modifier.executeModifyNode(session, params).success()).isTrue();

        assertThat(nestedConfigOf("Get File", "download"))
                .as("parseDownloadConfig reads only url/filename/mimeType")
                .containsEntry(parsed, "https://new.example/b.zip")
                .doesNotContainKey(sent);
    }

    @ParameterizedTest(name = "http_request modify({0}) writes {1}")
    @CsvSource({
            "endpoint,  url",
            "uri,       url",
            "auth_type, authType",
            "body_type, bodyType"
    })
    @DisplayName("regression: http_request aliases the node help advertises reach the keys the parser reads")
    void httpRequestAliasesAreNormalized(String sent, String parsed) {
        Map<String, Object> http = new LinkedHashMap<>();
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("method", "GET");
        cfg.put("url", "https://old.example");
        http.put("id", "core:call_api");
        http.put("label", "Call Api");
        http.put("type", "http_request");
        http.put("httpRequest", cfg);
        session.getCores().add(http);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("node", "Call Api");
        params.put("params", Map.of(sent, "bearer"));
        assertThat(modifier.executeModifyNode(session, params).success()).isTrue();

        assertThat(nestedConfigOf("Call Api", "httpRequest"))
                .containsEntry(parsed, "bearer")
                .doesNotContainKey(sent);
    }

    @Test
    @DisplayName("object-valued auth_config reaches authConfig")
    void httpAuthConfigObjectAliasIsNormalized() {
        addHttpNode();

        Map<String, Object> authConfig = Map.of("username", "u", "password", "p");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("node", "Call Api");
        params.put("params", Map.of("auth_config", authConfig));
        assertThat(modifier.executeModifyNode(session, params).success()).isTrue();

        assertThat(nestedConfigOf("Call Api", "httpRequest"))
                .as("the object value must survive the rename intact")
                .containsEntry("authConfig", authConfig)
                .doesNotContainKey("auth_config");
    }

    @Test
    @DisplayName("array-valued query_params reaches queryParams")
    void httpQueryParamsArrayAliasIsNormalized() {
        addHttpNode();

        List<Map<String, Object>> queryParams = List.of(Map.of("key", "q", "value", "term"));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("node", "Call Api");
        params.put("params", Map.of("query_params", queryParams));
        assertThat(modifier.executeModifyNode(session, params).success()).isTrue();

        assertThat(nestedConfigOf("Call Api", "httpRequest"))
                .containsEntry("queryParams", queryParams)
                .doesNotContainKey("query_params");
    }

    /**
     * An MCP params map carries no order, so two spellings of one field have no defensible
     * winner: the same patch would produce two different nodes on two calls. The creators pick
     * by a declared read order, but that order differs per node type (transform reads
     * {@code fields} into {@code mappings}, aggregate reads {@code mappings} into
     * {@code fields}), so mirroring it here would be a second copy of a rule that is easy to
     * get backwards. Refusing the patch says what actually went wrong and leaves the choice
     * with the only party that knows it.
     */
    @Test
    @DisplayName("regression: two spellings of one field are refused, not silently resolved by map order")
    void ambiguousAliasPatchIsRefused() {
        Map<String, Object> both = new LinkedHashMap<>();
        both.put("timeout_ms", 111);
        both.put("timeoutMs", 222);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("node", "Manager Review");
        params.put("params", both);

        ToolExecutionResult result = modifier.executeModifyNode(session, params);

        assertThat(result.success()).isFalse();
        assertThat(result.error())
                .as("the message must name both spellings and the field, so the caller can pick")
                .contains("timeout_ms")
                .contains("timeoutMs")
                .contains("Send only one of them");
        assertThat(approvalConfig().get("timeoutMs"))
                .as("nothing is written when the patch is refused")
                .isEqualTo(86400000L);
    }

    @Test
    @DisplayName("two aliases of the same field are refused too, not just alias vs canonical")
    void twoAliasesOfTheSameFieldAreRefused() {
        Map<String, Object> both = new LinkedHashMap<>();
        both.put("timeout_ms", 111);
        both.put("timeout", 222);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("node", "Manager Review");
        params.put("params", both);

        assertThat(modifier.executeModifyNode(session, params).success()).isFalse();
    }

    @Test
    @DisplayName("two spellings of DIFFERENT fields in one patch are fine")
    void differentFieldsInOnePatchAreAccepted() {
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("timeout_ms", 3600000);
        patch.put("approver_roles", List.of("manager"));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("node", "Manager Review");
        params.put("params", patch);

        assertThat(modifier.executeModifyNode(session, params).success()).isTrue();
        assertThat(approvalConfig())
                .containsEntry("timeoutMs", 3600000)
                .containsEntry("approverRoles", List.of("manager"));
    }


    /**
     * The hazard that made this table's scope a reading job rather than a generated list.
     *
     * <p>{@code outputs} on a transform node is NOT a spelling of {@code mappings}: the creator
     * reads it as an object {@code {name: expression}} and converts it to a list. Renaming the
     * key alone would park an object where the parser demands a list, overwrite the node's real
     * mappings, and then fail the WHOLE plan on the next parse, not just this node. So transform
     * is deliberately absent from the table and the key stays where it was sent.
     */
    @Test
    @DisplayName("a shape-transforming alias is left alone: outputs on transform is not renamed to mappings")
    void shapeTransformingAliasIsNotRenamed() {
        Map<String, Object> node = new LinkedHashMap<>();
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("mappings", List.of(Map.of("label", "kept", "expression", "{{x}}")));
        node.put("id", "core:shape");
        node.put("label", "Shape");
        node.put("type", "transform");
        node.put("transform", cfg);
        session.getCores().add(node);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("node", "Shape");
        params.put("params", Map.of("outputs", Map.of("total", "{{sum}}")));
        assertThat(modifier.executeModifyNode(session, params).success()).isTrue();

        assertThat(nestedConfigOf("Shape", "transform").get("mappings"))
                .as("the existing list must survive: an object here makes the whole plan unparseable")
                .isEqualTo(List.of(Map.of("label", "kept", "expression", "{{x}}")));
    }


    /**
     * The regression this rewrite could have introduced. Before the alias rewrite, a quoted
     * number under an alias landed in an unread key and the node kept its real value. Renaming
     * it without coercing would have moved the string onto the key the parser reads with
     * {@code instanceof Number}, which drops it and falls back to the default: the edit would
     * have gone from "does nothing" to "silently resets the field".
     */
    @ParameterizedTest(name = "quoted number under ''{0}'' is stored as a number")
    @CsvSource({
            "timeout_ms,         timeoutMs,          3600000",
            "timeoutMs,          timeoutMs,          3600000",
            "required_approvals, requiredApprovals,  2",
            "requiredApprovals,  requiredApprovals,  2"
    })
    @DisplayName("regression: a quoted number is coerced, not written as a string the parser drops")
    void quotedNumbersAreCoerced(String sent, String stored, long expected) {
        assertThat(modify(sent, String.valueOf(expected)).success()).isTrue();

        assertThat(approvalConfig().get(stored))
                .as("parseApprovalConfig reads this with instanceof Number, so a String is lost")
                .isInstanceOf(Number.class)
                .satisfies(v -> assertThat(((Number) v).longValue()).isEqualTo(expected));
    }

    @Test
    @DisplayName("a value that is not a number at all is left untouched, not invented")
    void nonNumericValueIsNotCoerced() {
        assertThat(modify("timeout_ms", "later").success()).isTrue();

        assertThat(approvalConfig().get("timeoutMs")).isEqualTo("later");
    }

    @Test
    @DisplayName("a {{...}} reference on a numeric field is stored as written, for the node to resolve at run time")
    void templateIsStoredAsWritten() {
        assertThat(modify("timeout_ms", "{{core:settings.output.timeout}}").success()).isTrue();

        assertThat(approvalConfig().get("timeoutMs")).isEqualTo("{{core:settings.output.timeout}}");
    }

    @Test
    @DisplayName("the ambiguity check does not fire on a node type the table does not cover")
    void ambiguityCheckIgnoresUncoveredTypes() {
        Map<String, Object> node = new LinkedHashMap<>();
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("mappings", List.of(Map.of("label", "kept", "expression", "{{x}}")));
        node.put("id", "core:shape");
        node.put("label", "Shape");
        node.put("type", "transform");
        node.put("transform", cfg);
        session.getCores().add(node);

        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("outputs", Map.of("total", "{{sum}}"));
        patch.put("mappings", List.of(Map.of("label", "new", "expression", "{{y}}")));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("node", "Shape");
        params.put("params", patch);

        assertThat(modifier.executeModifyNode(session, params).success())
                .as("transform is outside the table, so these are not two names for one field here")
                .isTrue();
    }

    @Test
    @DisplayName("two spellings on a download_file node are refused too, not just on approval")
    void ambiguityIsRefusedOnEveryCoveredType() {
        Map<String, Object> dl = new LinkedHashMap<>();
        dl.put("id", "core:get_file");
        dl.put("label", "Get File");
        dl.put("type", "download_file");
        dl.put("download", new LinkedHashMap<>(Map.of("url", "https://old.example/a.zip")));
        session.getCores().add(dl);

        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("source", "https://a.example");
        patch.put("link", "https://b.example");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("node", "Get File");
        params.put("params", patch);

        assertThat(modifier.executeModifyNode(session, params).success()).isFalse();
    }

    @Test
    @DisplayName("a node with no type is left alone, since its params never reach a nested config")
    void typelessNodeIsUntouched() {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", "core:untyped");
        node.put("label", "Untyped");
        node.put("approval", new LinkedHashMap<>(Map.of("timeoutMs", 86400000L)));
        session.getCores().add(node);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("node", "Untyped");
        params.put("params", Map.of("timeout_ms", 3600000));
        assertThat(modifier.executeModifyNode(session, params).success()).isTrue();

        assertThat(session.getCores().get(1))
                .as("the nested routing is type-driven, so renaming keys here would fix nothing")
                .doesNotContainKey("timeoutMs");
    }


    @Test
    @DisplayName("regression: http_request's own numeric timeout is coerced too, not just approval's")
    void httpRequestTimeoutIsCoerced() {
        addHttpNode();

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("node", "Call Api");
        params.put("params", Map.of("timeout", "5000"));
        assertThat(modifier.executeModifyNode(session, params).success()).isTrue();

        assertThat(nestedConfigOf("Call Api", "httpRequest").get("timeout"))
                .as("parseHttpRequestConfig reads timeout with instanceof Number")
                .isInstanceOf(Number.class)
                .satisfies(v -> assertThat(((Number) v).longValue()).isEqualTo(5000L));
    }

    @Test
    @DisplayName("timeout stays an alias on approval and a numeric field on http_request")
    void timeoutIsNumericOnHttpAndAnAliasOnApproval() {
        assertThat(modify("timeout", "7200000").success()).isTrue();
        assertThat(approvalConfig().get("timeoutMs"))
                .as("on approval, timeout is a spelling of timeoutMs and must still be renamed")
                .isInstanceOf(Number.class);
        assertThat(approvalConfig()).doesNotContainKey("timeout");
    }

    @Test
    @DisplayName("the same field twice with the SAME value is accepted, there is nothing to disambiguate")
    void identicalValuesUnderTwoSpellingsAreAccepted() {
        Map<String, Object> both = new LinkedHashMap<>();
        both.put("timeout_ms", 3600000);
        both.put("timeoutMs", 3600000);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("node", "Manager Review");
        params.put("params", both);

        assertThat(modifier.executeModifyNode(session, params).success()).isTrue();
        assertThat(approvalConfig().get("timeoutMs")).isEqualTo(3600000);
    }

    @Test
    @DisplayName("two spellings on an http_request node are refused too")
    void ambiguityIsRefusedOnHttpRequest() {
        addHttpNode();

        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("endpoint", "https://a.example");
        patch.put("uri", "https://b.example");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("node", "Call Api");
        params.put("params", patch);

        assertThat(modifier.executeModifyNode(session, params).success()).isFalse();
    }

    /**
     * The one input shape the rewrite does not reach, pinned so the limitation is a documented
     * fact rather than a surprise. Sending the whole config object is merged verbatim, so an
     * alias inside it still lands under a key nothing reads.
     */
    @Test
    @DisplayName("known gap: an alias inside a whole-config patch is not rewritten")
    void aliasInsideWholeConfigPatchIsNotRewritten() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("node", "Manager Review");
        params.put("params", Map.of("approval", Map.of("timeout_ms", 3600000)));

        assertThat(modifier.executeModifyNode(session, params).success()).isTrue();
        assertThat(approvalConfig().get("timeoutMs"))
                .as("flat params get the rewrite; the whole-object form does not")
                .isEqualTo(86400000L);
    }


    @Test
    @DisplayName("regression: sending the CANONICAL spelling also clears a stale alias left by an older edit")
    void canonicalSpellingAlsoClearsTheStaleAlias() {
        assertThat(modify("timeoutMs", 3600000).success()).isTrue();

        assertThat(approvalConfig())
                .as("otherwise the stale value survives under the spelling the node docs recommend")
                .containsEntry("timeoutMs", 3600000)
                .doesNotContainKey("timeout_ms");
    }

    @Test
    @DisplayName("a refused modify leaves the stored config untouched, including the stale alias")
    void refusedModifyDoesNotScrub() {
        Map<String, Object> both = new LinkedHashMap<>();
        both.put("timeout_ms", 111);
        both.put("timeoutMs", 222);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("node", "Manager Review");
        params.put("params", both);

        assertThat(modifier.executeModifyNode(session, params).success()).isFalse();
        assertThat(approvalConfig())
                .as("nothing may be mutated on a path that failed")
                .containsEntry("timeout_ms", 111)
                .containsEntry("timeoutMs", 86400000L);
    }

    @Test
    @DisplayName("two spellings holding equal collections are accepted, not read as a conflict")
    void equalCollectionValuesAreAccepted() {
        Map<String, Object> both = new LinkedHashMap<>();
        both.put("approver_roles", List.of("manager"));
        both.put("approverRoles", List.of("manager"));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("node", "Manager Review");
        params.put("params", both);

        assertThat(modifier.executeModifyNode(session, params).success()).isTrue();
        assertThat(approvalConfig().get("approverRoles")).isEqualTo(List.of("manager"));
    }

    @Test
    @DisplayName("two spellings holding different collections are refused")
    void differentCollectionValuesAreRefused() {
        Map<String, Object> both = new LinkedHashMap<>();
        both.put("approver_roles", List.of("manager"));
        both.put("approverRoles", List.of("admin"));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("node", "Manager Review");
        params.put("params", both);

        assertThat(modifier.executeModifyNode(session, params).success()).isFalse();
    }


    /**
     * The assertion that matters most here, because everything this rewrite does is delete keys:
     * a patch touching one field must leave every other field of the config alone. Without it, a
     * stray {@code config.remove(...)} in the scrub would pass the whole suite.
     */
    @Test
    @DisplayName("a patch on one field leaves every other field of the config untouched")
    void unrelatedFieldsSurviveTheScrub() {
        assertThat(modify("timeout_ms", 3600000).success()).isTrue();

        assertThat(approvalConfig())
                .as("only the field being set, and its stale spellings, may change")
                .containsEntry("delegation", Map.of("channel", "telegram", "chatId", "1"))
                .containsEntry("contextTemplate", "Approve?")
                .containsEntry("approverRoles", List.of())
                .containsEntry("requiredApprovals", 1);
    }

    @Test
    @DisplayName("a number and its quoted form under two spellings are one value, not a conflict")
    void quotedAndUnquotedSameNumberIsNotAConflict() {
        Map<String, Object> both = new LinkedHashMap<>();
        both.put("timeout_ms", 3600000);
        both.put("timeoutMs", "3600000");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("node", "Manager Review");
        params.put("params", both);

        assertThat(modifier.executeModifyNode(session, params).success()).isTrue();
        assertThat(approvalConfig().get("timeoutMs"))
                .isInstanceOf(Number.class)
                .satisfies(v -> assertThat(((Number) v).longValue()).isEqualTo(3600000L));
    }

    @Test
    @DisplayName("an out-of-int-range value on an Integer field is left alone, not wrapped negative")
    void outOfRangeIntegerFieldIsNotCoerced() {
        addHttpNode();

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("node", "Call Api");
        params.put("params", Map.of("timeout", "3000000000"));
        assertThat(modifier.executeModifyNode(session, params).success()).isTrue();

        assertThat(nestedConfigOf("Call Api", "httpRequest").get("timeout"))
                .as("intValue() wraps, so coercing here would produce a negative timeout")
                .isEqualTo("3000000000");
    }

    private void addHttpNode() {
        Map<String, Object> http = new LinkedHashMap<>();
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("method", "GET");
        cfg.put("url", "https://old.example");
        http.put("id", "core:call_api");
        http.put("label", "Call Api");
        http.put("type", "http_request");
        http.put("httpRequest", cfg);
        session.getCores().add(http);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> nestedConfigOf(String label, String nestedKey) {
        String nodeId = "core:" + WorkflowBuilderSession.normalizeLabel(label);
        return (Map<String, Object>) session.getCores().stream()
                .filter(n -> nodeId.equals(n.get("id")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no core node " + nodeId))
                .get(nestedKey);
    }
}
