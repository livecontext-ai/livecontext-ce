package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.dto.CustomApiRefDTO;
import com.apimarketplace.catalog.repository.ApiRepository;
import com.apimarketplace.catalog.repository.ApiToolParameterRepository;
import com.apimarketplace.catalog.repository.ApiToolRepository;
import com.apimarketplace.catalog.repository.ToolNameRepository;
import com.apimarketplace.credential.client.CredentialClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The lookup that tells the publish surfaces and publication-service which tool references are
 * backed by a CUSTOM (tenant-private) API, and therefore cannot be shared.
 *
 * <p>This suite pins the SQL as a STRING and, above all, the BIND ORDER: which predicate each
 * identifier shape produces and with which parameters, which a real-database test cannot show
 * as precisely. What the query RETURNS is the other half, and it lives in
 * {@link CustomApiRefsSqlIntegrationTest} - the two are complementary on purpose, because a
 * string assertion alone once let a semantically wrong exemption through.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowInspectorService custom-API reference lookup (SQL shape + bind order)")
class WorkflowInspectorServiceCustomApiRefsTest {

    private static final String PUBLISHER = "tenant-1";
    private static final String PUBLISHER_ORG = "org-7";

    @Mock private ApiRepository apiRepository;
    @Mock private ApiToolRepository apiToolRepository;
    @Mock private ApiToolParameterRepository apiToolParameterRepository;
    @Mock private ToolNameRepository toolNameRepository;
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private CredentialClient credentialClient;

    private WorkflowInspectorService service;

    @BeforeEach
    void setUp() {
        service = new WorkflowInspectorService(
                apiRepository, apiToolRepository, apiToolParameterRepository,
                toolNameRepository, jdbcTemplate, credentialClient);
    }

    /** One result row. Rows of the same API share an api_id, which is the grouping key. */
    private static Map<String, Object> row(String apiSlug, String apiName, String toolSlug, UUID toolId) {
        return row(UUID.nameUUIDFromBytes(apiSlug.getBytes()), apiSlug, apiName, toolSlug, toolId);
    }

    private static Map<String, Object> row(UUID apiId, String apiSlug, String apiName,
                                           String toolSlug, UUID toolId) {
        Map<String, Object> r = new java.util.HashMap<>();
        r.put("api_id", apiId);
        r.put("api_slug", apiSlug);
        r.put("api_name", apiName);
        r.put("tool_slug", toolSlug);
        r.put("tool_id", toolId);
        return r;
    }

    private void answerNoRows() {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
    }

    @SafeVarargs
    private void answerRows(Map<String, Object>... rows) {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(rows));
    }

    private String capturedSql() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForList(sql.capture(), any(Object[].class));
        return sql.getValue();
    }

    private List<Object> capturedParams() {
        ArgumentCaptor<Object[]> params = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).queryForList(anyString(), params.capture());
        return List.of(params.getValue());
    }

    // ==================== nothing to ask ====================

    @Test
    @DisplayName("a null identifier list queries nothing")
    void nullIdentifiersSkipTheQuery() {
        assertTrue(service.findCustomApiRefs(null, PUBLISHER, null).isEmpty());
        verify(jdbcTemplate, never()).queryForList(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("an empty identifier list queries nothing")
    void emptyIdentifiersSkipTheQuery() {
        assertTrue(service.findCustomApiRefs(List.of(), PUBLISHER, null).isEmpty());
        verify(jdbcTemplate, never()).queryForList(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("blank-only identifiers never reach the database")
    void blankIdentifiersSkipTheQuery() {
        assertTrue(service.findCustomApiRefs(List.of("", "   "), PUBLISHER, null).isEmpty());
        verify(jdbcTemplate, never()).queryForList(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("an identifier that is only a separator carries no slug and queries nothing")
    void separatorOnlyIdentifierSkipsTheQuery() {
        assertTrue(service.findCustomApiRefs(List.of("/do-thing", "my-api:"), PUBLISHER, null).isEmpty());
        verify(jdbcTemplate, never()).queryForList(anyString(), any(Object[].class));
    }

    // ==================== the shape of the query ====================

    @Test
    @DisplayName("only source='custom' rows are ever reported, and the match group is parenthesised under it")
    void queryIsRestrictedToCustomApis() {
        answerNoRows();

        assertTrue(service.findCustomApiRefs(List.of("github/get-user"), PUBLISHER, null).isEmpty());

        // The whole OR-group must stay inside the parentheses: without them a single matching
        // id predicate would return SHIPPED rows too.
        assertTrue(capturedSql().contains("WHERE a.source = 'custom' AND ("));
    }

    @Test
    @DisplayName("no is_active filter: a DEACTIVATED custom API still blocks the share")
    void deactivatedCustomApiStillCounts() {
        answerNoRows();

        service.findCustomApiRefs(List.of("my-api/do-thing"), PUBLISHER, null);

        assertFalse(capturedSql().contains("is_active"),
                "filtering on is_active would let a node on a deactivated custom API be published");
    }

    /**
     * The exemption is keyed on the REFERENCE ("does a shipped tool answer this exact
     * apiSlug + toolSlug?"), never on the candidate row's slug alone: keying it on the row
     * ungated a publisher's own custom API that merely shared a slug with a shipped
     * integration. {@code CustomApiRefsSqlIntegrationTest} pins the resulting behaviour.
     */
    @Test
    @DisplayName("a slug-based match is exempted only when a SHIPPED TOOL answers the same reference")
    void shippedToolExemptionIsKeyedOnTheReference() {
        answerNoRows();

        service.findCustomApiRefs(List.of("slack/slack-send-message"), PUBLISHER, null);

        String sql = capturedSql();
        assertTrue(sql.contains("NOT EXISTS (SELECT 1 FROM apis shipped"));
        assertTrue(sql.contains("JOIN api_tools shipped_tool ON shipped_tool.api_id = shipped.id"));
        assertTrue(sql.contains("shipped.source IS DISTINCT FROM 'custom'"));
        assertTrue(sql.contains("AND shipped.api_slug = ? AND shipped_tool.tool_slug = ?"),
                "both halves of the reference are bound, so the exemption cannot fire on the slug alone");
    }

    @Test
    @DisplayName("a prefixed reference binds: exemption pair, the api slug, then the tool slug and the owner")
    void prefixedReferenceBindsInOrder() {
        answerRows(row("my-api", "My API", "do-thing", UUID.randomUUID()));

        List<CustomApiRefDTO> refs =
                service.findCustomApiRefs(List.of("my-api/do-thing"), PUBLISHER, null);

        assertEquals(1, refs.size());
        assertEquals("My API", refs.get(0).apiName());
        assertEquals(List.of("my-api/do-thing"), refs.get(0).toolIdentifiers());

        String sql = capturedSql();
        assertTrue(sql.contains("a.api_slug = ?"));
        assertTrue(sql.contains("(at.tool_slug = ? OR (a.created_by = ? AND a.organization_id IS NULL))"),
                "the publisher's own API matches on the slug alone; anyone else's needs the exact row");
        assertEquals(List.of("my-api", "do-thing", "my-api", "do-thing", PUBLISHER), capturedParams());
    }

    @Test
    @DisplayName("an agent grant's apiSlug:toolSlug produces exactly the same clause as the slash form")
    void colonFormIsRoutedLikeTheSlashForm() {
        answerRows(row("my-api", "My API", "do-thing", UUID.randomUUID()));

        List<CustomApiRefDTO> refs =
                service.findCustomApiRefs(List.of("my-api:do-thing"), PUBLISHER, null);

        assertEquals(List.of("my-api:do-thing"), refs.get(0).toolIdentifiers(),
                "the colon form is what the agent tool picker writes, so it MUST resolve");
        assertEquals(List.of("my-api", "do-thing", "my-api", "do-thing", PUBLISHER), capturedParams());
    }

    @Test
    @DisplayName("without a publisher scope a prefixed reference needs the exact row: no owner disjunct at all")
    void noPublisherScopeMeansExactRowOnly() {
        answerNoRows();

        service.findCustomApiRefs(List.of("their-api/do-thing"), null, null);

        String sql = capturedSql();
        assertTrue(sql.contains("AND at.tool_slug = ?"));
        assertFalse(sql.contains("a.created_by"),
                "there is no publisher to widen the match for");
        assertEquals(List.of("their-api", "do-thing", "their-api", "do-thing"), capturedParams());
    }

    @Test
    @DisplayName("an org-scoped publish widens the match for the workspace's own APIs")
    void orgScopedPublisherUsesOrganizationId() {
        answerNoRows();

        service.findCustomApiRefs(List.of("my-api/do-thing"), PUBLISHER, PUBLISHER_ORG);

        assertTrue(capturedSql().contains("(at.tool_slug = ? OR a.organization_id = ?)"));
        assertEquals(List.of("my-api", "do-thing", "my-api", "do-thing", PUBLISHER_ORG), capturedParams());
    }

    @Test
    @DisplayName("a bare tool slug matches api_tools.tool_slug, under the same shipped-tool exemption")
    void bareSlugMatchesToolSlugOnly() {
        answerRows(row("my-api", "My API", "do-thing", UUID.randomUUID()));

        List<CustomApiRefDTO> refs = service.findCustomApiRefs(List.of("do-thing"), PUBLISHER, null);

        assertEquals(List.of("do-thing"), refs.get(0).toolIdentifiers());
        String sql = capturedSql();
        assertTrue(sql.contains("AND shipped_tool.tool_slug = ?) AND at.tool_slug = ?)"));
        assertFalse(sql.contains("a.api_slug = ?"), "a bare slug carries no api slug");
        assertEquals(List.of("do-thing", "do-thing"), capturedParams());
    }

    @Test
    @DisplayName("a UUID identifier (the legacy grant shape) matches api_tools.id with NO exemption")
    void uuidFormMatchesToolId() {
        UUID toolId = UUID.fromString("11111111-2222-3333-4444-555555555555");
        answerRows(row("my-api", "My API", "do-thing", toolId));

        List<CustomApiRefDTO> refs =
                service.findCustomApiRefs(List.of(toolId.toString()), PUBLISHER, null);

        assertEquals(List.of(toolId.toString()), refs.get(0).toolIdentifiers());
        String sql = capturedSql();
        assertTrue(sql.contains("at.id IN"));
        assertFalse(sql.contains("NOT EXISTS"),
                "an id names exactly one row, so it is never ambiguous");
        assertEquals(List.of(toolId), capturedParams());
    }

    @Test
    @DisplayName("mixed shapes bind clause by clause, and the id predicate stays OUTSIDE the exempted groups")
    void mixedShapesBindInPredicateOrder() {
        UUID toolId = UUID.fromString("11111111-2222-3333-4444-555555555555");
        answerNoRows();

        service.findCustomApiRefs(
                List.of("my-api/do-thing", "bare-slug", toolId.toString()), PUBLISHER, null);

        assertEquals(
                List.of("my-api", "do-thing", "my-api", "do-thing", PUBLISHER,
                        "bare-slug", "bare-slug", toolId),
                capturedParams());
        String sql = capturedSql();
        assertTrue(sql.lastIndexOf("NOT EXISTS") < sql.indexOf("at.id IN"),
                "the id predicate must not sit inside an exempted group: " + sql);
    }

    @Test
    @DisplayName("two prefixed references produce one clause each")
    void severalPrefixedReferencesProduceOneClauseEach() {
        answerNoRows();

        service.findCustomApiRefs(List.of("api-one/a", "api-two/b"), PUBLISHER, null);

        assertEquals(List.of("api-one", "a", "api-one", "a", PUBLISHER,
                        "api-two", "b", "api-two", "b", PUBLISHER),
                capturedParams());
    }

    // ==================== how the answer is grouped ====================

    @Test
    @DisplayName("an API matched with no surviving tool row is still reported (renamed tool)")
    void apiMatchWithoutToolRowStillReports() {
        answerRows(row("my-api", "My API", null, null));

        List<CustomApiRefDTO> refs =
                service.findCustomApiRefs(List.of("my-api/renamed-since"), PUBLISHER, null);

        assertEquals(1, refs.size());
        assertEquals(List.of("my-api/renamed-since"), refs.get(0).toolIdentifiers());
    }

    @Test
    @DisplayName("several identifiers of the same API collapse into ONE entry carrying all of them")
    void identifiersOfTheSameApiAreGrouped() {
        answerRows(
                row("my-api", "My API", "do-thing", UUID.randomUUID()),
                row("my-api", "My API", "do-other", UUID.randomUUID()));

        List<CustomApiRefDTO> refs = service.findCustomApiRefs(
                List.of("my-api/do-thing", "my-api/do-other"), PUBLISHER, null);

        assertEquals(1, refs.size());
        assertEquals(List.of("my-api/do-thing", "my-api/do-other"), refs.get(0).toolIdentifiers());
    }

    @Test
    @DisplayName("two distinct custom APIs are reported separately")
    void distinctApisAreReportedSeparately() {
        answerRows(
                row("api-one", "API One", "do-thing", UUID.randomUUID()),
                row("api-two", "API Two", "do-other", UUID.randomUUID()));

        List<CustomApiRefDTO> refs = service.findCustomApiRefs(
                List.of("api-one/do-thing", "api-two/do-other"), PUBLISHER, null);

        assertEquals(2, refs.size());
        assertEquals("api-one", refs.get(0).apiSlug());
        assertEquals("api-two", refs.get(1).apiSlug());
    }

    /**
     * `api_slug` is unique only per creator, so one reference can legitimately return two custom
     * APIs. Grouped by slug, they collapsed into one entry whose name was whichever row the
     * planner emitted first: the refusal could name a stranger's private API, or hide the
     * publisher's own and become unactionable.
     */
    @Test
    @DisplayName("two custom APIs sharing a slug stay TWO entries, each with its own name")
    void apisSharingASlugAreNotCollapsed() {
        UUID mine = UUID.fromString("aaaaaaaa-0000-4000-8000-000000000001");
        UUID theirs = UUID.fromString("bbbbbbbb-0000-4000-8000-000000000002");
        answerRows(
                row(mine, "slack", "My Slack Proxy", "slack-do-thing", UUID.randomUUID()),
                row(theirs, "slack", "Stranger Confidential API", "slack-other", UUID.randomUUID()));

        List<CustomApiRefDTO> refs =
                service.findCustomApiRefs(List.of("slack/slack-do-thing"), PUBLISHER, null);

        assertEquals(2, refs.size());
        assertEquals(List.of("My Slack Proxy", "Stranger Confidential API"),
                refs.stream().map(CustomApiRefDTO::apiName).toList());
    }

    @Test
    @DisplayName("a null api_name falls back to the slug so the refusal always names something")
    void nullApiNameFallsBackToSlug() {
        answerRows(row("my-api", null, "do-thing", UUID.randomUUID()));

        List<CustomApiRefDTO> refs =
                service.findCustomApiRefs(List.of("my-api/do-thing"), PUBLISHER, null);

        assertEquals("my-api", refs.get(0).apiName());
    }

    @Test
    @DisplayName("identifiers are trimmed and deduplicated into one clause per distinct value")
    void duplicateIdentifiersBindOnce() {
        answerNoRows();

        service.findCustomApiRefs(List.of("do-thing", "do-thing", " do-thing "), PUBLISHER, null);

        assertEquals(List.of("do-thing", "do-thing"), capturedParams(),
                "one clause, whose two binds are the same slug (exemption + match)");
    }

    // ==================== the caller-facing (scoped) variant ====================

    @Test
    @DisplayName("the scoped variant restricts the WHOLE query to the caller, and keeps the prefix match")
    void scopedLookupRestrictsEverythingToTheOwner() {
        answerNoRows();

        service.findCustomApiRefsInScope(List.of("my-api/do-thing"), PUBLISHER, null);

        String sql = capturedSql();
        assertTrue(sql.contains("a.created_by = ?"), "personal scope keys on created_by");
        assertTrue(sql.contains("a.organization_id IS NULL"));
        assertFalse(sql.contains("at.tool_slug = ? OR"),
                "everything is owner-restricted already, so the slug alone is enough");
        // Owner predicate trails the match clauses: exemption pair, api slug, then scope.
        assertEquals(List.of("my-api", "do-thing", "my-api", PUBLISHER), capturedParams());
    }

    @Test
    @DisplayName("the scoped variant in a workspace keys on the organization instead")
    void scopedLookupInOrgUsesOrganizationId() {
        answerNoRows();

        service.findCustomApiRefsInScope(List.of("my-api/do-thing"), PUBLISHER, PUBLISHER_ORG);

        assertTrue(capturedSql().contains("a.organization_id = ?"));
        assertEquals(List.of("my-api", "do-thing", "my-api", PUBLISHER_ORG), capturedParams());
    }

    @Test
    @DisplayName("the scoped variant refuses to answer at all without a tenant (never a global answer)")
    void scopedLookupWithoutTenantAnswersEmpty() {
        assertTrue(service.findCustomApiRefsInScope(List.of("my-api/do-thing"), null, null).isEmpty());
        assertTrue(service.findCustomApiRefsInScope(List.of("my-api/do-thing"), "  ", "  ").isEmpty());

        verify(jdbcTemplate, never()).queryForList(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("the scoped variant answers on a bare tool slug too, owner filter still applied")
    void scopedLookupWithBareSlugKeepsTheOwnerFilter() {
        answerNoRows();

        service.findCustomApiRefsInScope(List.of("do-thing"), PUBLISHER, null);

        String sql = capturedSql();
        assertTrue(sql.contains("at.tool_slug = ?"));
        assertTrue(sql.contains("a.created_by = ?"));
        assertEquals(List.of("do-thing", "do-thing", PUBLISHER), capturedParams());
    }
}
