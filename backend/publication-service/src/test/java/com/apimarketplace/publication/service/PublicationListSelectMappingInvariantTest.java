package com.apimarketplace.publication.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The marketplace SELECT list and the positional reader that consumes it, pinned to each other.
 *
 * <p><b>The gap this closes.</b> {@code SELECT_COLUMNS} is a text block and {@code mapRow} walks the
 * result with a bare {@code i++}. Nothing connects them: the only thing making column 44 the studio
 * flag is that both lists happen to be written in the same order. Reorder two columns, insert one in
 * the middle, or hardcode an index, and every publication in the marketplace gets a neighbouring
 * column's value - a title in the description, a credit price read out of a rating - with no error
 * anywhere, in a list served to anonymous visitors.
 *
 * <p><b>Why the existing service tests cannot see it.</b> They hand a mocked {@code EntityManager} a
 * hand-built {@code Object[]}, so the real SELECT string is never parsed and never executed: the
 * fixture is written to match whatever {@code mapRow} currently does, which means it AGREES with
 * every reordering. Mutation-testing that suite confirmed it - swapping two adjacent columns,
 * inserting one mid-list, and pointing {@code mapRow} at a hardcoded wrong index all left it green.
 *
 * <p><b>Why this is a source-reading test rather than a query.</b> The invariant is positional
 * correspondence between two lists in one file. A database would prove the SQL runs, which is a
 * different and weaker statement: a query with two columns swapped runs perfectly. Reading the two
 * lists is the only way to compare them, and it needs no Docker socket, so unlike an integration
 * test it actually runs in the default build.
 */
@DisplayName("PublicationListQueryService - the SELECT list and mapRow agree, position by position")
class PublicationListSelectMappingInvariantTest {

    private static final Path SOURCE = Path.of(
            "src/main/java/com/apimarketplace/publication/service/PublicationListQueryService.java");

    /**
     * Columns that are an expression rather than {@code p.<column>}, and the field each one feeds.
     *
     * <p>Kept explicit so that adding an expression column is a deliberate act: an unlisted one
     * fails below rather than being waved through by a looser rule.
     */
    private static final Map<String, String> EXPRESSION_COLUMNS = Map.of(
            "CAST(p.node_icons AS TEXT)", "nodeIcons",
            "CAST(p.node_types AS TEXT)", "nodeTypes",
            "CAST(p.ce_exclusive_features AS TEXT)", "ceExclusiveFeatures",
            "p.agent_snapshot->'agent'->>'avatarUrl'", "agentAvatarUrl",
            "p.agent_snapshot->'agent'->>'modelProvider'", "agentModelProvider",
            "p.agent_snapshot->'agent'->>'modelName'", "agentModelName");

    @Test
    @DisplayName("every selected column maps to the field named beside it, in the same order")
    void selectListAndMapRowLineUp() throws IOException {
        String source = Files.readString(SOURCE);
        List<String> columns = selectedColumns(source);
        List<String> fields = mappedFields(source);

        assertThat(columns)
                .as("the SELECT list and mapRow must read the same number of positions")
                .hasSameSizeAs(fields);

        for (int i = 0; i < columns.size(); i++) {
            String column = columns.get(i);
            String expected = EXPRESSION_COLUMNS.containsKey(column)
                    ? EXPRESSION_COLUMNS.get(column)
                    : camel(column.substring(column.indexOf('.') + 1));
            assertThat(fields.get(i))
                    .as("position %d selects %s, so mapRow must read it as %s", i, column, expected)
                    .isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("the studio axis is actually selected, or every marketplace read reports it false")
    void studioIsSelected() throws IOException {
        // Named on its own because its failure is silent in a way the ordering check is not: drop
        // the column and mapRow reads one position short, which the test above catches - but a
        // reader who "fixes" that by deleting the field instead ships a marketplace where every
        // publication is off the studio shelf, with no error and a Studio page that is simply empty.
        assertThat(selectedColumns(Files.readString(SOURCE))).contains("p.studio");
    }

    /** The column expressions of the SELECT_COLUMNS text block, in order. */
    private static List<String> selectedColumns(String source) {
        int start = source.indexOf("SELECT_COLUMNS = \"\"\"");
        assertThat(start).as("SELECT_COLUMNS is no longer a text block in this shape").isNotNegative();
        int bodyStart = source.indexOf('\n', start) + 1;
        int end = source.indexOf("\"\"\";", bodyStart);
        assertThat(end).as("unterminated SELECT_COLUMNS text block").isNotNegative();

        List<String> columns = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        for (char c : source.substring(bodyStart, end).toCharArray()) {
            if (c == '(') depth++;
            if (c == ')') depth--;
            // Commas inside CAST(...) are not column separators.
            if (c == ',' && depth == 0) {
                columns.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c == '\n' ? ' ' : c);
            }
        }
        if (!current.toString().isBlank()) columns.add(current.toString().trim());
        return columns;
    }

    /**
     * The field names mapRow reads, in order, taken from the comment beside each {@code row[i++]}.
     *
     * <p>The comment is the only place the intent is written down, which is exactly why it is worth
     * pinning: a comment that has drifted from its position is how a reader convinces themselves the
     * order is right.
     */
    private static List<String> mappedFields(String source) {
        int start = source.indexOf("private static PublicationListItem mapRow(Object[] row)");
        assertThat(start).as("mapRow is no longer declared in this shape").isNotNegative();
        String body = source.substring(start, source.indexOf("\n    }", start));

        List<String> fields = new ArrayList<>();
        Matcher m = Pattern.compile("row\\[i\\+\\+]\\)\\s*,?\\s*//\\s*(\\w+)").matcher(body);
        while (m.find()) fields.add(m.group(1));

        assertThat(fields).as("mapRow reads no positions - the pattern above has drifted").isNotEmpty();
        return fields;
    }

    private static String camel(String snake) {
        StringBuilder out = new StringBuilder();
        boolean up = false;
        for (char c : snake.toCharArray()) {
            if (c == '_') { up = true; continue; }
            out.append(up ? Character.toUpperCase(c) : Character.toLowerCase(c));
            up = false;
        }
        return out.toString().toLowerCase(Locale.ROOT).isEmpty() ? snake : out.toString();
    }
}
