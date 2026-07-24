package com.apimarketplace.orchestrator.tools.workflow.builder.creators;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.service.NodeLibraryService;
import com.apimarketplace.orchestrator.tools.workflow.builder.ResponseOptimizer;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSession;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSessionStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

/**
 * CONTRACT TEST closing the add_node allow-list bug class.
 *
 * <p><b>The class:</b> {@code add_node} maps agent params through per-creator ALLOW-LISTS
 * (each {@code executeAdd*} method hand-reads the params it knows and hand-builds the nested
 * config map). Every time a field is added to a Core config record, the allow-list is
 * forgotten and the param is SILENTLY dropped on the agent's primary create path, while
 * set_plan (Jackson deserialization of the whole config) and modify (generic nested merge in
 * WorkflowBuilderModifier) keep working. Confirmed instances before this test existed:
 * extract_from_file {@code chunkUnit}
 * ({@link UtilityNodeCreatorExtractFromFileChunkUnitTest}), email_inbox
 * {@code createTargetIfMissing} + send_email {@code replyTo}/{@code fromEmail}
 * ({@link UtilityNodeCreatorMailParamsTest}), approval {@code continuationMode}
 * ({@link DecisionNodeCreatorApprovalContinuationModeTest}).
 *
 * <p><b>How it closes the class for the covered creators:</b> for each covered creator,
 * reflection enumerates EVERY component of the corresponding Core config record, builds an
 * add_node parameter map that sets all of them (camelCase, values of the right shape), invokes
 * the creator, and asserts every component key is present in the persisted nested config AND
 * holds the value that was sent (coercion-aware, so a right-key/wrong-value mis-wire is caught
 * too). A NEW record component therefore lands in the assertion BY DEFAULT: the moment someone
 * adds a field to a COVERED record without extending the creator's allow-list, the matching
 * dynamic test fails naming the dropped key. Opting a component OUT requires consciously adding
 * it to the creator's {@code excludedComponents} set below, with a justification comment.
 *
 * <p><b>Covered here:</b> approval (ApprovalConfig), email_inbox (EmailInboxConfig), send_email
 * (SendEmailConfig), ssh (SshConfig), sftp (SftpConfig), database (DatabaseConfig). Other
 * add_node creators are NOT yet under this guard - the class is closed only for the records
 * listed. <b>Adding a node type</b> = one {@link CoveredCreator} entry in
 * {@link #coveredCreators()}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("add_node <-> Core config record contract (no silently dropped params)")
class AddNodeConfigRecordContractTest {

    @Mock private WorkflowBuilderSessionStore sessionStore;
    @Mock private ResponseOptimizer responseOptimizer;
    @Mock private NodeLibraryService nodeLibraryService;
    @Mock private WorkflowRepository workflowRepository;

    private UtilityNodeCreator utilityCreator;
    private DecisionNodeCreator decisionCreator;

    @BeforeEach
    void setUp() {
        utilityCreator = new UtilityNodeCreator(sessionStore, responseOptimizer, nodeLibraryService, workflowRepository);
        decisionCreator = new DecisionNodeCreator(sessionStore, responseOptimizer);
        lenient().when(nodeLibraryService.findByType(anyString())).thenReturn(Optional.empty());
    }

    /**
     * One covered add_node creator method.
     *
     * @param nodeType           add_node {@code type} (display only)
     * @param configRecord       the Core config record the runtime deserializes the nested config into
     * @param nestedConfigKey    the key the creator nests the config map under on the node
     * @param valueOverrides     per-component sample values where the generic factory's shape is
     *                           wrong (complex types, enum-like strings that must round-trip the
     *                           record's validation)
     * @param excludedComponents components CONSCIOUSLY not settable via add_node - every entry
     *                           needs a justification comment at the declaration site
     * @param invoke             the creator method under contract
     */
    private record CoveredCreator(
        String nodeType,
        Class<? extends Record> configRecord,
        String nestedConfigKey,
        Map<String, Object> valueOverrides,
        Set<String> excludedComponents,
        BiFunction<WorkflowBuilderSession, Map<String, Object>, ToolExecutionResult> invoke
    ) {}

    private List<CoveredCreator> coveredCreators() {
        return List.of(
            new CoveredCreator(
                "approval", Core.ApprovalConfig.class, "approval",
                Map.of(
                    // Only the two documented values exist; anything else is normalized to
                    // all_items at creation, so use the non-default one to prove passthrough.
                    "continuationMode", "per_item",
                    // ApprovalDelegation is a nested record; add_node takes it as a map.
                    "delegation", Map.of("channel", "telegram", "credentialId", 40, "chatId", "123")
                ),
                // No exclusions: every ApprovalConfig component is agent-settable.
                Set.of(),
                (s, p) -> decisionCreator.executeAddApproval(s, p)),

            new CoveredCreator(
                "email_inbox", Core.EmailInboxConfig.class, "emailInbox",
                Map.of(
                    // The record's compact constructor rejects unknown actions, so the
                    // round-trip check needs a real one; 'move' also exercises
                    // targetFolder/createTargetIfMissing meaningfully.
                    "action", "move"
                ),
                // No exclusions: every EmailInboxConfig component is agent-settable
                // (credentialId pins a specific IMAP credential; connection secrets
                // themselves live in the credential system, not in the record).
                Set.of(),
                (s, p) -> utilityCreator.executeAddEmailInbox(s, p)),

            new CoveredCreator(
                "send_email", Core.SendEmailConfig.class, "sendEmail",
                Map.of(),
                // EXCLUSIONS - each entry is a conscious decision, not a forgotten mapping:
                // smtpHost/smtpPort/smtpUsername/smtpPassword/smtpUseTls are LEGACY record
                // components, documented in Core.SendEmailConfig as ignored at run time
                // (SendEmailNode always reads the connection from the SMTP credential,
                // selected by credentialId or the default). They exist only so older stored
                // plans still deserialize; letting add_node write them would let an agent
                // embed connection secrets in a plan that the runtime then ignores.
                Set.of("smtpHost", "smtpPort", "smtpUsername", "smtpPassword", "smtpUseTls"),
                (s, p) -> utilityCreator.executeAddSendEmail(s, p)),

            new CoveredCreator(
                "ssh", Core.SshConfig.class, "ssh",
                Map.of(
                    // host + command are required by the creator (blank rejected); the generic
                    // string sample is non-blank so no override is strictly needed, but pin
                    // realistic values for clarity.
                    "host", "server.example.com",
                    "command", "ls -la"
                ),
                // No exclusions: every SshConfig component is agent-settable (credentialId
                // pins a stored SSH credential; host/port/username/authMethod/password/
                // privateKey/command/timeout are all real inputs).
                Set.of(),
                (s, p) -> utilityCreator.executeAddSsh(s, p)),

            new CoveredCreator(
                "sftp", Core.SftpConfig.class, "sftp",
                Map.of(
                    "host", "server.example.com",
                    // operation is validated against an allow-list; use a real one.
                    "operation", "list"
                ),
                // No exclusions: every SftpConfig component is agent-settable.
                Set.of(),
                (s, p) -> utilityCreator.executeAddSftp(s, p)),

            new CoveredCreator(
                "database", Core.DatabaseConfig.class, "database",
                Map.of(
                    "host", "db.example.com",
                    // databaseName + query are required (blank rejected); operation is validated.
                    "databaseName", "mydb",
                    "query", "SELECT 1",
                    "operation", "select"
                ),
                // No exclusions: every DatabaseConfig component is agent-settable.
                Set.of(),
                (s, p) -> utilityCreator.executeAddDatabase(s, p))
        );
    }

    // ==================== The contract ====================

    @TestFactory
    @DisplayName("every non-excluded record component set on add_node is persisted in the nested config")
    List<DynamicNode> everyRecordComponentIsPersisted() {
        List<DynamicNode> containers = new ArrayList<>();
        for (CoveredCreator covered : coveredCreators()) {
            containers.add(DynamicContainer.dynamicContainer(
                covered.nodeType() + " <-> " + covered.configRecord().getSimpleName(),
                creatorContract(covered)));
        }
        return containers;
    }

    private List<DynamicNode> creatorContract(CoveredCreator covered) {
        RecordComponent[] components = covered.configRecord().getRecordComponents();
        Set<String> componentNames = Arrays.stream(components)
            .map(RecordComponent::getName)
            .collect(Collectors.toCollection(LinkedHashSet::new));

        List<DynamicNode> tests = new ArrayList<>();

        // Guard the test's own inputs: a stale exclusion or override (component renamed or
        // removed from the record) must fail loudly instead of silently guarding nothing.
        tests.add(DynamicTest.dynamicTest("exclusion list only names real record components", () ->
            assertThat(covered.excludedComponents())
                .as("stale exclusion for %s: entries must match current record components %s",
                    covered.configRecord().getSimpleName(), componentNames)
                .isSubsetOf(componentNames)));
        tests.add(DynamicTest.dynamicTest("value overrides only name real record components", () ->
            assertThat(covered.valueOverrides().keySet())
                .as("stale value override for %s: keys must match current record components %s",
                    covered.configRecord().getSimpleName(), componentNames)
                .isSubsetOf(componentNames)));

        // Build the exhaustive param map, invoke the creator ONCE, then assert per component.
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("label", "Contract " + covered.nodeType());
        params.put("connect_after", "Start");
        List<RecordComponent> included = new ArrayList<>();
        for (RecordComponent component : components) {
            if (covered.excludedComponents().contains(component.getName())) continue;
            included.add(component);
            // Not getOrDefault: sampleValue must only run when there is no override, because
            // it deliberately throws for complex types that require a conscious override.
            params.put(component.getName(),
                covered.valueOverrides().containsKey(component.getName())
                    ? covered.valueOverrides().get(component.getName())
                    : sampleValue(component));
        }

        WorkflowBuilderSession session = newSession();
        ToolExecutionResult result = covered.invoke().apply(session, params);

        tests.add(DynamicTest.dynamicTest("creator accepts the exhaustive param map", () ->
            assertThat(result.success())
                .as("add_node type='%s' rejected a param map that sets every %s component: %s",
                    covered.nodeType(), covered.configRecord().getSimpleName(), result.error())
                .isTrue()));

        Map<String, Object> persisted = persistedConfig(session, covered.nestedConfigKey());

        for (RecordComponent component : included) {
            String name = component.getName();
            Object sent = params.get(name);
            tests.add(DynamicTest.dynamicTest("persists '" + name + "'", () -> {
                // 1) PRESENCE: the key must survive the creator's allow-list.
                assertThat(persisted)
                    .as("add_node type='%s' SILENTLY DROPPED '%s': a component of %s was not wired "
                        + "through the creator's param allow-list. Extend the creator to read the "
                        + "param (camelCase + snake_case alias) and persist it under this exact key, "
                        + "or consciously add it to excludedComponents in this test with a "
                        + "justification comment.",
                        covered.nodeType(), name, covered.configRecord().getSimpleName())
                    .containsKey(name);
                // 2) VALUE: the persisted value must equal what was sent (coercion-aware), so a
                // creator that persists the right KEY with a WRONG/hardcoded VALUE is caught too,
                // not just a missing key. Numeric boxing (Integer<->Long) is tolerated because the
                // creator legitimately re-types via getInt/getLong; anything else must round-trip
                // by value.
                assertValueMatches(covered.nodeType(), name, persisted.get(name), sent);
            }));
        }

        tests.add(DynamicTest.dynamicTest("persisted config round-trips into " + covered.configRecord().getSimpleName(), () -> {
            // The plan parser / runtime reads the nested map back through the record: the keys
            // the creator writes must be exactly the keys the record deserializes.
            Object parsed = new ObjectMapper().convertValue(persisted, covered.configRecord());
            assertThat(parsed).isNotNull();
        }));

        return tests;
    }

    /**
     * Assert the persisted value equals the sent value, tolerating only integral numeric
     * boxing (Integer<->Long, the creators' getInt/getLong re-typing). A mismatch means the
     * creator wired the key but stored the wrong value (e.g. a hardcoded literal or the wrong
     * param), which a presence-only check would false-pass.
     */
    private static void assertValueMatches(String nodeType, String name, Object persisted, Object sent) {
        if (persisted instanceof Number pn && sent instanceof Number sn) {
            assertThat(pn.longValue())
                .as("add_node type='%s' persisted '%s' with a WRONG numeric value", nodeType, name)
                .isEqualTo(sn.longValue());
            return;
        }
        assertThat(persisted)
            .as("add_node type='%s' persisted '%s' with a WRONG value (key present but value not "
                + "the one sent) - the param was mis-wired, not read from the caller's value.",
                nodeType, name)
            .isEqualTo(sent);
    }

    // ==================== Sample values ====================

    /**
     * Type-shaped sample value for a record component. Deliberately throws for shapes it
     * does not know: a new component with a complex type must get a conscious entry in the
     * covered creator's {@code valueOverrides}, mirroring how excludedComponents forces a
     * conscious opt-out.
     */
    private static Object sampleValue(RecordComponent component) {
        Class<?> type = component.getType();
        if (type == String.class) return "sample_" + component.getName();
        if (type == boolean.class || type == Boolean.class) return true;
        if (type == int.class || type == Integer.class) return 3;
        if (type == long.class || type == Long.class) return 3600000L;
        if (type == double.class || type == Double.class) return 1.5d;
        if (List.class.isAssignableFrom(type)) return List.of("sample");
        if (Map.class.isAssignableFrom(type)) return Map.of("k", "v");
        throw new AssertionError("No generic sample value for component '" + component.getName()
            + "' of type " + type.getName() + " - add a valueOverrides entry for it in the covered creator.");
    }

    // ==================== Session plumbing ====================

    private static WorkflowBuilderSession newSession() {
        WorkflowBuilderSession session = WorkflowBuilderSession.builder()
            .sessionId("s")
            .tenantId("t")
            .workflowName("w")
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();
        Map<String, Object> trig = new LinkedHashMap<>();
        trig.put("label", "Start");
        trig.put("id", "trigger:start");
        trig.put("type", "webhook");
        session.getTriggers().add(trig);
        return session;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> persistedConfig(WorkflowBuilderSession session, String nestedKey) {
        assertThat(session.getCores())
            .as("the creator must have added the node to the session")
            .isNotEmpty();
        Map<String, Object> node = session.getCores().get(session.getCores().size() - 1);
        Map<String, Object> config = (Map<String, Object>) node.get(nestedKey);
        assertThat(config)
            .as("the node must nest its config under '%s' (the key the plan parser reads)", nestedKey)
            .isNotNull();
        return config;
    }
}
