package com.apimarketplace.orchestrator.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The contract of {@code findVersionAuthors}, read off the SHIPPED {@link Query} by reflection.
 *
 * <p>Two facts the editor list rests on live in that JPQL string and nowhere else, and both are
 * invisible to the service's own unit tests because those hand-build the projection rows:
 *
 * <ul>
 *   <li><b>The aliases are the getter names.</b> Spring Data maps a closed projection by
 *       matching each {@code AS <alias>} to a {@code get<Alias>()}. Rename one side only and
 *       every getter answers null: the service then skips every row as authorless and the
 *       popover reports that nobody has ever edited the workflow. No exception, no log.</li>
 *   <li><b>The order is version DESC.</b> The service takes the FIRST row it sees for a user as
 *       their most recent save and relies on map insertion order for "most recently active
 *       first". Serve the same rows ascending and every date shown is that person's OLDEST
 *       edit, and the list is ordered backwards, while every assertion made against a mock
 *       that hands back a pre-sorted list still passes.</li>
 * </ul>
 *
 * <p>Reading the annotation rather than restating the query is the point: a copy would be a
 * second source of truth that agrees with itself while the shipped one drifts.
 */
class WorkflowPlanVersionRepositoryQueryContractTest {

    private static String versionAuthorsQuery() throws NoSuchMethodException {
        Method method = WorkflowPlanVersionRepository.class
                .getMethod("findVersionAuthors", java.util.UUID.class);
        Query query = method.getAnnotation(Query.class);
        assertThat(query)
                .as("findVersionAuthors lost its @Query; a derived query would name no aliases")
                .isNotNull();
        return query.value().replaceAll("\\s+", " ").trim();
    }

    @Test
    @DisplayName("selects one alias per projection getter, spelled the way Spring Data matches them")
    void aliasesMatchTheProjectionGetters() throws Exception {
        String jpql = versionAuthorsQuery().toLowerCase(Locale.ROOT);

        for (Method getter : WorkflowPlanVersionRepository.VersionAuthorProjection.class.getMethods()) {
            String alias = getter.getName().substring("get".length());
            alias = Character.toLowerCase(alias.charAt(0)) + alias.substring(1);
            assertThat(jpql)
                    .as("%s() has no matching 'AS %s' in the query, so it will always answer null",
                            getter.getName(), alias)
                    .contains(" as " + alias.toLowerCase(Locale.ROOT));
        }
    }

    @Test
    @DisplayName("binds each alias to the RIGHT column, not merely to some column")
    void aliasesAreBoundToTheRightColumns() throws Exception {
        String jpql = versionAuthorsQuery().toLowerCase(Locale.ROOT);

        // Checking the aliases exist is not enough: SWAPPING them
        // (`createdAt AS userId, createdBy AS editedAt`) leaves both substrings present and
        // every other assertion here true, while the popover would file each edit under a
        // timestamp and date it by a user id - and the service's own tests, which hand-build
        // the projection, could never see it.
        assertThat(jpql).contains("v.createdat as editedat");
        assertThat(jpql).contains("v.createdby as userid");
    }

    @Test
    @DisplayName("returns the newest version first, which is what makes the first row seen the latest save")
    void ordersByVersionDescending() throws Exception {
        String jpql = versionAuthorsQuery().toLowerCase(Locale.ROOT);

        assertThat(jpql).contains("order by v.version desc");
    }

    @Test
    @DisplayName("carries no plan body, which is the reason this query exists at all")
    void selectsNoPlanBody() throws Exception {
        String jpql = versionAuthorsQuery().toLowerCase(Locale.ROOT);

        // The full finder deserializes one JSONB document per row (see its javadoc). Selecting
        // the plan here would silently reintroduce that cost on a popover, and no test that
        // mocks the repository could ever notice.
        assertThat(jpql).doesNotContain("v.plan");
        // Scalars only. Naming them keeps "no plan body" from being satisfied by a SELECT that
        // grew an entity back into it.
        assertThat(jpql).startsWith("select v.createdat");
        assertThat(jpql).doesNotContain("select v ");
    }

    @Test
    @DisplayName("scopes to one workflow, by the parameter the repository declares")
    void scopesToTheRequestedWorkflow() throws Exception {
        String jpql = versionAuthorsQuery().toLowerCase(Locale.ROOT);

        assertThat(jpql).contains("where v.workflowid = :workflowid");
    }
}
