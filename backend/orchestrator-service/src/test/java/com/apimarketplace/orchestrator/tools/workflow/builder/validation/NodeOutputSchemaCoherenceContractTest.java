package com.apimarketplace.orchestrator.tools.workflow.builder.validation;

import com.apimarketplace.agent.domain.NodeDefinition;
import com.apimarketplace.agent.domain.NodeSpec;
import com.apimarketplace.agent.domain.OutputFieldDef;
import com.apimarketplace.orchestrator.controllers.NodeDefinitionController;
import com.apimarketplace.orchestrator.services.persistence.schema.NodeDefinitionRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Contract tests for the 3-way output schema alignment:
 * NodeSpec/mapper source, node_type_documentation.outputs for agent help, and
 * GET /api/node-definitions for the frontend inspector.
 *
 * <p>The SQL parser intentionally handles only the node_type_documentation
 * output-update patterns used by the migration corpus. If a new migration uses
 * a new expression style, extend this parser instead of weakening the checks.
 */
@DisplayName("Node output schema coherence: agent docs, backend specs, frontend endpoint")
class NodeOutputSchemaCoherenceContractTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, Object>> JSON_OBJECT_TYPE = new TypeReference<>() {};

    private static final Path MIGRATION_DIR = Paths.get(
        "..", "migration-service", "src", "main", "resources", "db", "migration");

    /**
     * These nodes have legitimate dynamic top-level outputs that cannot be
     * represented as a fixed NodeSpec key set. The test still checks that they
     * have an agent documentation row and that documented fields are typed.
     */
    private static final Set<String> DYNAMIC_OUTPUT_NODE_TYPES = Set.of(
        "AGGREGATE",
        "FORM_TRIGGER",
        "TABLE_TRIGGER",
        "WORKFLOW_TRIGGER"
    );

    /**
     * Node types whose agent documentation is allowed to disagree with their NodeSpec.
     *
     * <p>It used to hold twelve types (AGENT, APPROVAL, CLASSIFY, DECISION, EXTRACT_FROM_FILE,
     * GUARDRAIL, INTERFACE, MERGE, OPTION, STOP_ON_ERROR, SUMMARIZE, SWITCH), which is to say
     * this guard was suppressing the exact problem it exists to catch. V422 realigned nine of
     * them with an explicit UPDATE, and OPTION needed none: dropping it from this set and
     * watching this test stay green is the proof its docs already matched its spec.
     * The two that remain are here for a structural reason, not as a backlog: see below.
     *
     * <p>Adding an entry for any OTHER reason does not defer a cosmetic mismatch, it ships a
     * real defect: the
     * agent writes a template against a documented field the engine never persists, and the
     * parameter silently loses its content. Mixed text resolves the expression to an EMPTY
     * STRING (TemplateEngine.resolveExpressions, plus a WARN nobody reads); a pure
     * {@code {{...}}} resolves to NULL. The run still reports COMPLETED, and an empty value is
     * indistinguishable from a legitimately empty one. Fix the documentation (or the spec).
     */
    private static final Set<String> KNOWN_SCHEMA_DRIFT_NODE_TYPES = Set.of(
        // Signal-yielding nodes. For these the NodeSpec is the WRONG source of truth, so this
        // test's premise (docs == NodeSpec) does not hold and enforcing it produces the very
        // defect the test exists to prevent.
        //
        // When a node yields AWAITING_SIGNAL its output is NOT persisted (SignalResumeService
        // :899 says so verbatim). What an agent can actually read is written later by
        // SignalResumeService on resume: signal_type, resolution, resolved_at, resolved_by
        // (:1283-1287). ApprovalNodeSpec's approver_roles / required_approvals / expires_at are
        // yield-time only and never reach storage.
        //
        // An earlier revision of V422 "aligned" approval to the NodeSpec: it documented the three
        // fields no template can ever read and deleted the four that work. Those UPDATEs were
        // removed. Re-enabling this check for these two types requires teaching it to read the
        // resume path, not just the spec.
        "APPROVAL",
        "INTERFACE"
    );

    /**
     * Agent docs that do not use the standard
     * {"field": {"type": "...", "description": "..."}} shape.
     *
     * <p>interface stays here: V422 no longer rewrites its docs (its outputs sit on the
     * signal-yield path, see KNOWN_SCHEMA_DRIFT_NODE_TYPES above), so its historic
     * non-standard shape is unchanged.
     */
    private static final Set<String> KNOWN_NON_STANDARD_AGENT_DOC_TYPES = Set.of("interface");

    private static final Pattern WHERE_TYPE_PATTERN =
        Pattern.compile("(?is)\\bWHERE\\s+type\\s*=\\s*'([^']+)'");

    private static final Pattern WHERE_TYPE_IN_PATTERN =
        Pattern.compile("(?is)\\bWHERE\\s+type\\s+IN\\s*\\(([^)]*)\\)");

    @Test
    @DisplayName("Every registered NodeSpec has an agent documentation output row")
    void everyNodeSpecHasAgentDocumentationOutputs() throws Exception {
        Map<String, Map<String, Object>> agentDocs = loadFinalAgentOutputDocs();
        StringBuilder failures = new StringBuilder();

        for (NodeSpec spec : discoverNodeSpecs()) {
            NodeDefinition definition = spec.definition();
            String docType = docTypeFor(definition.nodeType());
            Map<String, Object> docOutputs = agentDocs.get(docType);
            if (docOutputs == null || docOutputs.isEmpty()) {
                failures.append("  - ")
                    .append(definition.nodeType())
                    .append(" expects node_type_documentation type '")
                    .append(docType)
                    .append("' but no outputs were discovered in migrations\n");
            }
        }

        if (failures.length() > 0) {
            fail("Missing agent-facing node_type_documentation.outputs rows:\n" + failures);
        }
    }

    @Test
    @DisplayName("Static agent documentation output keys match NodeSpec output keys")
    void staticAgentDocumentationKeysMatchNodeSpecKeys() throws Exception {
        Map<String, Map<String, Object>> agentDocs = loadFinalAgentOutputDocs();
        StringBuilder failures = new StringBuilder();

        for (NodeSpec spec : discoverNodeSpecs()) {
            NodeDefinition definition = spec.definition();
            String nodeType = definition.nodeType();
            if (DYNAMIC_OUTPUT_NODE_TYPES.contains(nodeType) || KNOWN_SCHEMA_DRIFT_NODE_TYPES.contains(nodeType)) {
                continue;
            }

            String docType = docTypeFor(nodeType);
            Map<String, Object> docOutputs = agentDocs.get(docType);
            if (docOutputs == null) {
                failures.append("  - ").append(nodeType).append(": no agent docs loaded for type '")
                    .append(docType).append("'\n");
                continue;
            }

            Set<String> specKeys = outputKeys(definition.outputs());
            Set<String> docKeys = concreteDocKeys(docOutputs);

            Set<String> docOnly = new TreeSet<>(docKeys);
            docOnly.removeAll(specKeys);
            Set<String> specOnly = new TreeSet<>(specKeys);
            specOnly.removeAll(docKeys);

            if (!docOnly.isEmpty() || !specOnly.isEmpty()) {
                failures.append("  - ").append(nodeType)
                    .append(" / node_type_documentation '").append(docType).append("'\n")
                    .append("    documented-only: ").append(docOnly).append("\n")
                    .append("    spec-only: ").append(specOnly).append("\n");
            }
        }

        if (failures.length() > 0) {
            fail("Agent docs and NodeSpec output key drift:\n" + failures);
        }
    }

    @Test
    @DisplayName("Agent-facing output docs declare type and description for each field")
    void agentOutputDocsHaveTypeAndDescription() throws Exception {
        Map<String, Map<String, Object>> agentDocs = loadFinalAgentOutputDocs();
        StringBuilder failures = new StringBuilder();

        for (Map.Entry<String, Map<String, Object>> docEntry : new TreeMap<>(agentDocs).entrySet()) {
            String docType = docEntry.getKey();
            if (KNOWN_NON_STANDARD_AGENT_DOC_TYPES.contains(docType)) {
                continue;
            }
            for (Map.Entry<String, Object> fieldEntry : docEntry.getValue().entrySet()) {
                if (!(fieldEntry.getValue() instanceof Map<?, ?> fieldDoc)) {
                    failures.append("  - ").append(docType).append(".").append(fieldEntry.getKey())
                        .append(": field doc is not an object\n");
                    continue;
                }
                Object type = fieldDoc.get("type");
                Object description = fieldDoc.get("description");
                if (!(type instanceof String typeText) || typeText.isBlank()) {
                    failures.append("  - ").append(docType).append(".").append(fieldEntry.getKey())
                        .append(": missing non-empty type\n");
                }
                if (!(description instanceof String descriptionText) || descriptionText.isBlank()) {
                    failures.append("  - ").append(docType).append(".").append(fieldEntry.getKey())
                        .append(": missing non-empty description\n");
                }
            }
        }

        if (failures.length() > 0) {
            fail("Malformed agent-facing output docs:\n" + failures);
        }
    }

    @Test
    @DisplayName("/api/node-definitions serializes registry output schemas without renaming")
    @SuppressWarnings("unchecked")
    void nodeDefinitionsEndpointPreservesRegistryOutputSchemas() throws Exception {
        List<NodeSpec> specs = discoverNodeSpecs();
        NodeDefinitionRegistry registry = new NodeDefinitionRegistry(specs);
        registry.init();
        NodeDefinitionController controller = new NodeDefinitionController(registry);

        List<Map<String, Object>> response = controller.getAll().getBody();
        assertNotNull(response, "Node definition response body must not be null");

        Map<String, Map<String, Object>> responseByType = response.stream()
            .collect(Collectors.toMap(
                item -> (String) item.get("nodeType"),
                item -> item,
                (left, right) -> left,
                TreeMap::new
            ));

        for (NodeSpec spec : specs) {
            NodeDefinition definition = spec.definition();
            Map<String, Object> dto = responseByType.get(definition.nodeType());
            assertNotNull(dto, "Missing /api/node-definitions DTO for " + definition.nodeType());

            List<Map<String, Object>> outputs = (List<Map<String, Object>>) dto.get("outputs");
            assertEquals(
                outputKeys(definition.outputs()),
                responseOutputKeys(outputs),
                "Endpoint output keys must match NodeSpec for " + definition.nodeType()
            );
            assertNestedOutputKeys(definition.outputs(), outputs, definition.nodeType());
        }
    }

    private static List<NodeSpec> discoverNodeSpecs() throws ReflectiveOperationException {
        ClassPathScanningCandidateComponentProvider scanner =
            new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(NodeSpec.class));

        List<NodeSpec> specs = new ArrayList<>();
        for (var beanDefinition : scanner.findCandidateComponents(
            "com.apimarketplace.orchestrator.execution.v2.nodes")) {
            Class<?> candidateClass = Class.forName(beanDefinition.getBeanClassName());
            if (candidateClass.isInterface() || Modifier.isAbstract(candidateClass.getModifiers())) {
                continue;
            }
            specs.add((NodeSpec) candidateClass.getDeclaredConstructor().newInstance());
        }
        specs.sort(Comparator.comparing(spec -> spec.definition().nodeType()));
        assertTrue(specs.size() >= 50, "NodeSpec discovery should cover the local node catalog");
        return specs;
    }

    private static Set<String> outputKeys(List<OutputFieldDef> fields) {
        return fields.stream()
            .map(OutputFieldDef::key)
            .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> responseOutputKeys(List<Map<String, Object>> fields) {
        return fields.stream()
            .map(field -> (String) field.get("key"))
            .collect(Collectors.toCollection(TreeSet::new));
    }

    @SuppressWarnings("unchecked")
    private static void assertNestedOutputKeys(
        List<OutputFieldDef> specFields,
        List<Map<String, Object>> responseFields,
        String path
    ) {
        Map<String, OutputFieldDef> specByKey = specFields.stream()
            .collect(Collectors.toMap(OutputFieldDef::key, field -> field));
        for (Map<String, Object> responseField : responseFields) {
            String key = (String) responseField.get("key");
            OutputFieldDef specField = specByKey.get(key);
            assertNotNull(specField, "Unexpected endpoint output field " + path + "." + key);
            assertEquals(
                Boolean.TRUE.equals(specField.runtimeOnly()),
                Boolean.TRUE.equals(responseField.get("runtimeOnly")),
                "Endpoint runtimeOnly flag must match NodeSpec for " + path + "." + key
            );

            List<Map<String, Object>> children =
                (List<Map<String, Object>>) responseField.getOrDefault("children", List.of());
            assertEquals(
                outputKeys(specField.children()),
                responseOutputKeys(children),
                "Endpoint nested output keys must match NodeSpec for " + path + "." + key
            );
            assertNestedOutputKeys(specField.children(), children, path + "." + key);
        }
    }

    private static String docTypeFor(String nodeType) {
        return switch (nodeType) {
            case "CHAT_TRIGGER" -> "chat";
            case "ERROR_TRIGGER" -> "error";
            case "FORM_TRIGGER" -> "form";
            case "MANUAL_TRIGGER" -> "manual";
            case "SCHEDULE_TRIGGER" -> "schedule";
            case "TABLE_TRIGGER" -> "table";
            case "WEBHOOK_TRIGGER" -> "webhook";
            case "WORKFLOW_TRIGGER" -> "workflow";
            case "FIND" -> "find_rows";
            default -> nodeType.toLowerCase(Locale.ROOT);
        };
    }

    private static Set<String> concreteDocKeys(Map<String, Object> docOutputs) {
        return docOutputs.keySet().stream()
            .filter(key -> !(key.startsWith("<") && key.endsWith(">")))
            .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Map<String, Map<String, Object>> loadFinalAgentOutputDocs() throws IOException {
        Path migrationDir = resolveMigrationDir();
        Map<String, Map<String, Object>> outputDocs = new TreeMap<>();

        try (var stream = Files.list(migrationDir)) {
            List<Path> migrations = stream
                .filter(path -> path.getFileName().toString().endsWith(".sql"))
                .sorted(Comparator.comparingInt(NodeOutputSchemaCoherenceContractTest::migrationNumber))
                .toList();

            for (Path migration : migrations) {
                String sql = stripLineComments(Files.readString(migration));
                for (String statement : splitSqlStatements(sql)) {
                    String lower = statement.toLowerCase(Locale.ROOT);
                    if (!lower.contains("node_type_documentation") || !lower.contains("outputs")) {
                        continue;
                    }
                    if (lower.contains("insert into")) {
                        applyInsertStatement(statement, outputDocs);
                    } else if (lower.contains("update") && lower.contains("set")) {
                        applyUpdateStatement(statement, outputDocs);
                    }
                }
            }
        }

        assertFalse(outputDocs.isEmpty(), "Migration parser should discover node_type_documentation outputs");
        return outputDocs;
    }

    private static Path resolveMigrationDir() {
        if (Files.exists(MIGRATION_DIR)) {
            return MIGRATION_DIR;
        }
        Path fromRepoRoot = Paths.get("backend", "migration-service", "src", "main", "resources", "db", "migration");
        assertTrue(Files.exists(fromRepoRoot), "Could not locate migration directory");
        return fromRepoRoot;
    }

    private static int migrationNumber(Path path) {
        Matcher matcher = Pattern.compile("V(\\d+)__.*\\.sql").matcher(path.getFileName().toString());
        if (!matcher.matches()) {
            return Integer.MAX_VALUE;
        }
        return Integer.parseInt(matcher.group(1));
    }

    private static void applyInsertStatement(
        String statement,
        Map<String, Map<String, Object>> outputDocs
    ) throws IOException {
        String lower = statement.toLowerCase(Locale.ROOT);
        int insertIdx = lower.indexOf("insert into");
        int columnsStart = statement.indexOf('(', insertIdx);
        if (columnsStart < 0) return;
        int columnsEnd = findMatchingParen(statement, columnsStart);
        int valuesIdx = lower.indexOf("values", columnsEnd);
        if (valuesIdx < 0) return;
        int valuesStart = statement.indexOf('(', valuesIdx);
        if (valuesStart < 0) return;
        int valuesEnd = findMatchingParen(statement, valuesStart);

        List<String> columns = splitTopLevel(statement.substring(columnsStart + 1, columnsEnd)).stream()
            .map(String::trim)
            .map(column -> column.replace("\"", ""))
            .toList();
        List<String> values = splitTopLevel(statement.substring(valuesStart + 1, valuesEnd));

        int typeIndex = columns.indexOf("type");
        int outputsIndex = columns.indexOf("outputs");
        if (typeIndex < 0 || outputsIndex < 0 || typeIndex >= values.size() || outputsIndex >= values.size()) {
            return;
        }

        Optional<String> type = parseSqlString(values.get(typeIndex));
        Optional<Map<String, Object>> outputs = parseOutputExpression(values.get(outputsIndex), Map.of());
        if (type.isPresent() && outputs.isPresent()) {
            outputDocs.put(type.get(), outputs.get());
        }
    }

    private static void applyUpdateStatement(
        String statement,
        Map<String, Map<String, Object>> outputDocs
    ) throws IOException {
        List<String> whereTypes = new ArrayList<>();
        int whereIdx;
        Matcher matcher = WHERE_TYPE_PATTERN.matcher(statement);
        Matcher inMatcher = WHERE_TYPE_IN_PATTERN.matcher(statement);
        if (matcher.find()) {
            whereTypes.add(matcher.group(1));
            whereIdx = matcher.start();
        } else if (inMatcher.find()) {
            for (String item : splitTopLevel(inMatcher.group(1))) {
                parseSqlString(item).ifPresent(whereTypes::add);
            }
            whereIdx = inMatcher.start();
        } else {
            return;
        }

        String lower = statement.toLowerCase(Locale.ROOT);
        int setIdx = lower.indexOf("set");
        if (setIdx < 0 || whereIdx < setIdx) {
            return;
        }

        String setPart = statement.substring(setIdx + 3, whereIdx);
        Optional<String> outputExpression = extractAssignmentExpression(setPart, "outputs");
        if (outputExpression.isEmpty()) {
            return;
        }
        Optional<String> renamedType = extractAssignmentExpression(setPart, "type")
            .flatMap(NodeOutputSchemaCoherenceContractTest::parseSqlString);
        List<String> types = renamedType.map(List::of).orElse(whereTypes);

        for (String type : types) {
            Map<String, Object> current = outputDocs.getOrDefault(type, new LinkedHashMap<>());
            Optional<Map<String, Object>> parsed = parseOutputExpression(outputExpression.get(), current);
            if (parsed.isEmpty()) {
                // Skipping an UPDATE we cannot read is how V481 (warnings) and V486 (billed_credits)
                // went unseen: their fields were documented, this replay dropped them, and the check
                // reported a drift that did not exist in the database. Fail on the statement instead.
                throw new IllegalArgumentException(
                    "Unsupported node_type_documentation.outputs expression for type '" + type
                        + "'; extend this parser: " + abbreviate(outputExpression.get()));
            }
            outputDocs.put(type, parsed.get());
        }
    }

    private static String abbreviate(String text) {
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= 200 ? flat : flat.substring(0, 200) + "...";
    }

    /**
     * Replays {@code jsonb_set(target, '{a,b}', value [, create_missing])} with PostgreSQL's
     * semantics: an intermediate key that is missing or not an object makes it a no-op, and the
     * last key is only created when create_missing is true (the default). The target may itself be
     * {@code outputs}, {@code COALESCE(outputs, ...)} or another jsonb_set.
     */
    private static Map<String, Object> applyJsonbSet(String expression, Map<String, Object> current)
        throws IOException {
        int argsStart = expression.indexOf('(');
        int argsEnd = findMatchingParen(expression, argsStart);
        requireNothingAfter(expression, argsEnd, "jsonb_set");
        List<String> args = splitTopLevel(expression.substring(argsStart + 1, argsEnd));
        if (args.size() < 3) {
            throw new IllegalArgumentException("jsonb_set needs 3 arguments: " + abbreviate(expression));
        }

        Map<String, Object> base = parseOutputExpression(args.get(0), current)
            .orElseThrow(() -> new IllegalArgumentException(
                "Unsupported jsonb_set target: " + abbreviate(args.get(0))));
        List<String> path = parseSqlString(args.get(1))
            .map(p -> p.replaceAll("^\\{|}$", ""))
            .map(p -> List.of(p.split(",")).stream().map(String::trim).toList())
            .orElseThrow(() -> new IllegalArgumentException(
                "Unsupported jsonb_set path: " + abbreviate(args.get(1))));
        boolean createMissing = args.size() < 4 || !args.get(3).trim().equalsIgnoreCase("false");

        String valueExpression = args.get(2).trim().replaceAll("(?i)::jsonb$", "").trim();
        Object value = null;
        boolean valueParsed = false;
        if (startsWithSqlString(valueExpression)) {
            Optional<String> literal = parseSqlString(valueExpression);
            if (literal.isPresent()) {
                value = OBJECT_MAPPER.readValue(literal.get(), Object.class);
                valueParsed = true;
            }
        }
        if (!valueParsed && path.size() == 1) {
            // A top-level key whose value we cannot read would still be a key the agent sees.
            throw new IllegalArgumentException(
                "Unsupported jsonb_set value for top-level key '" + path.get(0) + "': "
                    + abbreviate(args.get(2)));
        }

        Map<String, Object> result = new LinkedHashMap<>(base);
        if (!valueParsed) {
            // A computed value below a field (e.g. to_jsonb(...) on a description) leaves keys unchanged.
            return result;
        }
        Map<String, Object> node = result;
        for (int i = 0; i < path.size() - 1; i++) {
            if (!(node.get(path.get(i)) instanceof Map<?, ?> child)) {
                return result;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) child);
            node.put(path.get(i), copy);
            node = copy;
        }
        String last = path.get(path.size() - 1);
        if (createMissing || node.containsKey(last)) {
            node.put(last, value);
        }
        return result;
    }

    private static Optional<String> extractAssignmentExpression(String setPart, String columnName) {
        Pattern assignmentPattern = Pattern.compile(
            "(?is)^\\s*(?:[a-zA-Z_][a-zA-Z0-9_]*\\.)?"
                + Pattern.quote(columnName)
                + "\\s*="
        );
        for (String assignment : splitTopLevel(setPart)) {
            Matcher matcher = assignmentPattern.matcher(assignment);
            if (matcher.find()) {
                return Optional.of(assignment.substring(matcher.end()).trim());
            }
        }
        return Optional.empty();
    }

    /**
     * Fails when anything but a {@code ::jsonb} cast follows the call that closes at
     * {@code closeIdx}. Both callers replay ONLY the call itself, so a trailing
     * {@code || '{"new_key": ...}'} (or {@code - 'old_key'}) would be dropped in silence and the
     * replayed document would lack keys the database has, or keep keys it removed.
     */
    static void requireNothingAfter(String expression, int closeIdx, String function) {
        String rest = expression.substring(closeIdx + 1).trim();
        if (!TRAILING_CAST.matcher(rest).matches()) {
            throw new IllegalArgumentException(
                "Unconsumed content after " + function + "(...): " + abbreviate(rest)
                    + "; extend this parser rather than drop it");
        }
    }

    private static final Pattern TRAILING_CAST = Pattern.compile("(?i)(::\\s*jsonb)?");

    static Optional<Map<String, Object>> parseOutputExpression(
        String expression,
        Map<String, Object> current
    ) throws IOException {
        String trimmed = expression.trim();
        if (trimmed.equalsIgnoreCase("NULL")) {
            return Optional.of(new LinkedHashMap<>());
        }
        if (trimmed.equalsIgnoreCase("EXCLUDED.outputs")) {
            return Optional.empty();
        }

        if (startsWithSqlString(trimmed)) {
            return Optional.of(parseJsonObjectFromSqlLiteral(trimmed));
        }

        String lowerTrimmed = trimmed.toLowerCase(Locale.ROOT);
        if (lowerTrimmed.startsWith("translate(outputs::text")) {
            // Character-level rewrite of the whole document (V349 em-dash strip): keys are unchanged.
            requireNothingAfter(trimmed, findMatchingParen(trimmed, trimmed.indexOf('(')), "translate");
            return Optional.of(new LinkedHashMap<>(current));
        }

        if (lowerTrimmed.startsWith("coalesce")) {
            int argsStart = trimmed.indexOf('(');
            int argsEnd = findMatchingParen(trimmed, argsStart);
            List<String> args = splitTopLevel(trimmed.substring(argsStart + 1, argsEnd));
            if (!args.isEmpty() && args.get(0).trim().equalsIgnoreCase("outputs")) {
                // COALESCE(outputs, '{}'::jsonb) || ... reads as outputs || ... on an existing row.
                return parseOutputExpression("outputs" + trimmed.substring(argsEnd + 1), current);
            }
            return Optional.empty();
        }

        if (lowerTrimmed.startsWith("jsonb_set")) {
            return Optional.of(applyJsonbSet(trimmed, current));
        }

        if (trimmed.toLowerCase(Locale.ROOT).startsWith("jsonb_build_object")) {
            return Optional.of(parseJsonbBuildObject(trimmed));
        }

        if (trimmed.toLowerCase(Locale.ROOT).startsWith("outputs")) {
            Map<String, Object> merged = new LinkedHashMap<>(current);
            Matcher removeMatcher = Pattern.compile("(?is)outputs\\s*-\\s*'([^']+)'").matcher(trimmed);
            while (removeMatcher.find()) {
                merged.remove(removeMatcher.group(1));
            }

            int literalIdx = indexOfSqlJsonLiteral(trimmed);
            if (literalIdx >= 0) {
                merged.putAll(parseJsonObjectFromSqlLiteral(trimmed.substring(literalIdx)));
            }

            int buildIdx = trimmed.toLowerCase(Locale.ROOT).indexOf("jsonb_build_object");
            if (buildIdx >= 0) {
                merged.putAll(parseJsonbBuildObject(trimmed.substring(buildIdx)));
            }
            return Optional.of(merged);
        }

        return Optional.empty();
    }

    private static boolean startsWithSqlString(String value) {
        String trimmed = value.trim();
        return trimmed.startsWith("'") || trimmed.startsWith("E'");
    }

    private static int indexOfSqlJsonLiteral(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == '\'' && i + 1 < value.length() && value.charAt(i + 1) == '{') {
                return i;
            }
        }
        return -1;
    }

    private static Map<String, Object> parseJsonObjectFromSqlLiteral(String expression) throws IOException {
        String literal = parseSqlString(expression)
            .orElseThrow(() -> new IllegalArgumentException("Expected SQL string literal: " + expression));
        return OBJECT_MAPPER.readValue(literal, JSON_OBJECT_TYPE);
    }

    private static Map<String, Object> parseJsonbBuildObject(String expression) {
        int functionIdx = expression.toLowerCase(Locale.ROOT).indexOf("jsonb_build_object");
        int argsStart = expression.indexOf('(', functionIdx);
        int argsEnd = findMatchingParen(expression, argsStart);
        List<String> args = splitTopLevel(expression.substring(argsStart + 1, argsEnd));

        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i + 1 < args.size(); i += 2) {
            Optional<String> key = parseSqlString(args.get(i));
            if (key.isEmpty()) {
                continue;
            }
            String value = args.get(i + 1).trim();
            if (value.toLowerCase(Locale.ROOT).startsWith("jsonb_build_object")) {
                result.put(key.get(), parseJsonbBuildObject(value));
            } else {
                result.put(key.get(), parseSqlString(value).orElse(value));
            }
        }
        return result;
    }

    private static Optional<String> parseSqlString(String expression) {
        String trimmed = expression.trim();
        int idx = trimmed.startsWith("E'") ? 1 : 0;
        if (idx >= trimmed.length() || trimmed.charAt(idx) != '\'') {
            return Optional.empty();
        }
        StringBuilder value = new StringBuilder();
        for (int i = idx + 1; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '\'') {
                if (i + 1 < trimmed.length() && trimmed.charAt(i + 1) == '\'') {
                    value.append('\'');
                    i++;
                } else {
                    return Optional.of(value.toString());
                }
            } else {
                value.append(c);
            }
        }
        return Optional.empty();
    }

    private static List<String> splitSqlStatements(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inString = false;

        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            current.append(c);
            if (c == '\'') {
                if (inString && i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                    current.append(sql.charAt(++i));
                } else {
                    inString = !inString;
                }
            } else if (c == ';' && !inString) {
                statements.add(current.toString());
                current.setLength(0);
            }
        }
        if (current.length() > 0) {
            statements.add(current.toString());
        }
        return statements;
    }

    private static String stripLineComments(String sql) {
        StringBuilder result = new StringBuilder();
        boolean inString = false;

        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'') {
                result.append(c);
                if (inString && i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                    result.append(sql.charAt(++i));
                } else {
                    inString = !inString;
                }
            } else if (!inString && c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                while (i < sql.length() && sql.charAt(i) != '\n') {
                    i++;
                }
                if (i < sql.length()) {
                    result.append('\n');
                }
            } else {
                result.append(c);
            }
        }

        return result.toString();
    }

    private static List<String> splitTopLevel(String text) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        boolean inString = false;
        int depth = 0;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'') {
                if (inString && i + 1 < text.length() && text.charAt(i + 1) == '\'') {
                    i++;
                } else {
                    inString = !inString;
                }
            } else if (!inString) {
                if (c == '(') depth++;
                else if (c == ')') depth--;
                else if (c == ',' && depth == 0) {
                    parts.add(text.substring(start, i).trim());
                    start = i + 1;
                }
            }
        }
        parts.add(text.substring(start).trim());
        return parts;
    }

    private static int findTopLevelComma(String text, int start) {
        boolean inString = false;
        int depth = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'') {
                if (inString && i + 1 < text.length() && text.charAt(i + 1) == '\'') {
                    i++;
                } else {
                    inString = !inString;
                }
            } else if (!inString) {
                if (c == '(') depth++;
                else if (c == ')') depth--;
                else if (c == ',' && depth == 0) return i;
            }
        }
        return -1;
    }

    private static int findMatchingParen(String text, int openIdx) {
        boolean inString = false;
        int depth = 0;
        for (int i = openIdx; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'') {
                if (inString && i + 1 < text.length() && text.charAt(i + 1) == '\'') {
                    i++;
                } else {
                    inString = !inString;
                }
            } else if (!inString) {
                if (c == '(') depth++;
                else if (c == ')') {
                    depth--;
                    if (depth == 0) return i;
                }
            }
        }
        throw new IllegalArgumentException("Unmatched parenthesis in SQL fragment: " + text.substring(openIdx));
    }
    @Nested
    @DisplayName("migration replay parser: content after jsonb_set(...) or translate(...)")
    class TrailingContentAfterACall {

        private final Map<String, Object> current = Map.of("result", "x", "status", "y");

        @Test
        @DisplayName("jsonb_set followed by || '{...}' fails instead of dropping the merged keys")
        void jsonbSetFollowedByAMergeFails() {
            String expr = "jsonb_set(outputs, '{result}', '{\"type\":\"object\"}'::jsonb) || '{\"extra\":1}'::jsonb";

            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> parseOutputExpression(expr, current));

            assertTrue(error.getMessage().contains("Unconsumed content after jsonb_set(...)"), error.getMessage());
        }

        @Test
        @DisplayName("jsonb_set followed by - 'key' fails instead of keeping the removed key")
        void jsonbSetFollowedByARemovalFails() {
            String expr = "jsonb_set(outputs, '{result}', '\"s\"'::jsonb) - 'status'";

            assertThrows(IllegalArgumentException.class, () -> parseOutputExpression(expr, current));
        }

        @Test
        @DisplayName("a nested jsonb_set target is checked too: its own trailing merge fails")
        void nestedJsonbSetTargetWithTrailingContentFails() {
            String expr = "jsonb_set(jsonb_set(outputs, '{a}', '1'::jsonb) || '{\"b\":2}', '{c}', '3'::jsonb)";

            assertThrows(IllegalArgumentException.class, () -> parseOutputExpression(expr, current));
        }

        @Test
        @DisplayName("translate(outputs::text ...) followed by || '{...}' fails")
        void translateFollowedByAMergeFails() {
            String expr = "translate(outputs::text, 'a', 'b')::jsonb || '{\"extra\":1}'::jsonb";

            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> parseOutputExpression(expr, current));

            assertTrue(error.getMessage().contains("Unconsumed content after translate(...)"), error.getMessage());
        }

        @Test
        @DisplayName("a trailing ::jsonb cast (any case, any spacing) is consumed, not an error")
        void trailingJsonbCastIsAccepted() throws IOException {
            assertEquals(Set.of("result", "status"),
                parseOutputExpression("translate(outputs::text, 'a', 'b')::JSONB", current).orElseThrow().keySet());
            assertEquals(Set.of("result", "status", "added"),
                parseOutputExpression("jsonb_set(outputs, '{added}', '1'::jsonb) :: jsonb", current)
                    .orElseThrow().keySet());
        }

        @Test
        @DisplayName("nothing after the call is accepted")
        void nothingAfterTheCallIsAccepted() throws IOException {
            assertEquals(Set.of("result", "status", "added"),
                parseOutputExpression("jsonb_set(outputs, '{added}', '1')", current).orElseThrow().keySet());
            assertEquals(Set.of("result", "status"),
                parseOutputExpression("translate(outputs::text, 'a', 'b')", current).orElseThrow().keySet());
        }
    }
}
