package com.apimarketplace.conversation.repository;

import com.apimarketplace.conversation.dto.MessageSearchHit;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Native PostgreSQL implementation of {@link MessageSearchRepository}.
 *
 * <p>Uses {@code websearch_to_tsquery} (vs {@code to_tsquery}) so that
 * user-supplied query strings are handled safely AND support search-engine
 * syntax: {@code OR}, quoted phrases, and {@code -negation}. Malformed
 * input never raises a SQL error - invalid characters are silently ignored.
 *
 * <p>Excerpt highlighting uses unicode brackets {@code U+27E6 ⟦} and
 * {@code U+27E7 ⟧} rather than HTML tags. UI maps them to its own
 * emphasis style; agent consumers pass the excerpt unchanged to LLMs.
 */
@Repository
public class MessageSearchRepositoryImpl implements MessageSearchRepository {

    @PersistenceContext
    private EntityManager em;

    /**
     * {@code ts_headline} options:
     * <ul>
     *   <li>{@code MaxWords=30, MinWords=10} - bound excerpt length.</li>
     *   <li>{@code ShortWord=3} - drop tiny stop-like words from boundary.</li>
     *   <li>{@code MaxFragments=2} - return up to 2 contextual fragments.</li>
     *   <li>{@code FragmentDelimiter=" … "} - joined by ellipsis.</li>
     *   <li>{@code StartSel}/{@code StopSel} - unicode brackets, not HTML.</li>
     * </ul>
     */
    private static final String HEADLINE_OPTIONS =
            "MaxWords=30, MinWords=10, ShortWord=3, "
          + "MaxFragments=2, FragmentDelimiter=\" … \", "
          + "StartSel=⟦, StopSel=⟧";

    @Override
    public List<MessageSearchHit> search(
            List<String> conversationIds,
            String query,
            Instant since,
            Instant until,
            List<String> roles,
            String toolName,
            boolean includeInactive,
            Instant cursorTimestamp,
            String cursorId,
            int limit
    ) {
        if (conversationIds == null || conversationIds.isEmpty()) {
            return List.of();
        }

        // Build SQL dynamically - null params skip their predicate to keep
        // the planner happy and let it pick the best index.
        StringBuilder sql = new StringBuilder()
                .append("WITH q AS (SELECT websearch_to_tsquery('simple', :query) AS query) ")
                .append("SELECT m.id, m.conversation_id, c.title, m.agent_id, m.execution_id, ")
                .append("       m.role, m.tool_name, ")
                .append("       ts_headline('simple', coalesce(m.content, ''), q.query, '")
                .append(HEADLINE_OPTIONS).append("') AS excerpt, ")
                .append("       ts_rank(m.search_vector, q.query) AS rank, ")
                .append("       m.created_at ")
                .append("FROM conversation.messages m ")
                .append("JOIN conversation.conversations c ON c.id = m.conversation_id ")
                .append("CROSS JOIN q ")
                .append("WHERE c.id IN (:conversationIds) ")
                .append("  AND m.search_vector @@ q.query ");

        if (!includeInactive) {
            sql.append("  AND c.active = TRUE ");
        }
        if (since != null) {
            sql.append("  AND m.created_at >= :since ");
        }
        if (until != null) {
            sql.append("  AND m.created_at <= :until ");
        }
        if (roles != null && !roles.isEmpty()) {
            sql.append("  AND UPPER(m.role) IN (:roles) ");
        }
        if (toolName != null && !toolName.isBlank()) {
            sql.append("  AND m.tool_name = :toolName ");
        }
        if (cursorTimestamp != null && cursorId != null) {
            // Tuple comparison for stable cursor: (created_at, id) < (cursorTs, cursorId)
            sql.append("  AND (m.created_at, m.id) < (:cursorTs, :cursorId) ");
        }

        // ORDER BY is strictly time-based to keep the cursor tuple monotonic
        // across pages. Mixing rank into the order key (page 1 ordered by
        // rank, page 2 by time) causes high-rank rows after the cursor's
        // timestamp to be skipped - a real bug with same-timestamp ties or
        // when rank dominates ordering.
        //
        // Each hit still carries its `rank` field so callers (UI, agent) can
        // re-sort within a page or aggregate across pages if they want a
        // relevance-ranked view.
        sql.append("ORDER BY m.created_at DESC, m.id DESC ")
           .append("LIMIT :limit");

        Query nativeQuery = em.createNativeQuery(sql.toString());
        nativeQuery.setParameter("query", query);
        nativeQuery.setParameter("conversationIds", conversationIds);
        nativeQuery.setParameter("limit", limit);

        if (since != null) nativeQuery.setParameter("since", Timestamp.from(since));
        if (until != null) nativeQuery.setParameter("until", Timestamp.from(until));
        if (roles != null && !roles.isEmpty()) {
            // Roles are stored UPPER (USER, ASSISTANT, ...) - normalize input
            List<String> normalized = roles.stream().map(r -> r.toUpperCase()).toList();
            nativeQuery.setParameter("roles", normalized);
        }
        if (toolName != null && !toolName.isBlank()) {
            nativeQuery.setParameter("toolName", toolName);
        }
        if (cursorTimestamp != null && cursorId != null) {
            nativeQuery.setParameter("cursorTs", Timestamp.from(cursorTimestamp));
            nativeQuery.setParameter("cursorId", cursorId);
        }

        @SuppressWarnings("unchecked")
        List<Object[]> rows = nativeQuery.getResultList();
        // The TIMESTAMPTZ column type comes back from the JDBC driver as
        // either java.sql.Timestamp (legacy) or java.time.Instant /
        // java.time.OffsetDateTime (driver 42.x with proper type mapping).
        // We accept all three forms via {@link #toInstant}.

        List<MessageSearchHit> hits = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            hits.add(new MessageSearchHit(
                    (String) row[0],                                  // m.id
                    (String) row[1],                                  // conversation_id
                    (String) row[2],                                  // c.title
                    (String) row[3],                                  // agent_id
                    (String) row[4],                                  // execution_id
                    (String) row[5],                                  // role
                    (String) row[6],                                  // tool_name
                    (String) row[7],                                  // excerpt
                    row[8] == null ? 0.0 : ((Number) row[8]).doubleValue(),  // rank
                    toInstant(row[9])  // created_at (UTC)
            ));
        }
        return hits;
    }

    /**
     * Coerce the driver's TIMESTAMPTZ representation into an {@link Instant}.
     *
     * <p>The PostgreSQL JDBC driver may return TIMESTAMPTZ as one of:
     * <ul>
     *   <li>{@link java.sql.Timestamp} - legacy / many old setups</li>
     *   <li>{@link Instant} - driver 42.x with explicit type mapping</li>
     *   <li>{@link java.time.OffsetDateTime} - driver 42.x default for tstz</li>
     * </ul>
     * We accept all three forms so the repository works regardless of
     * driver / mapper config.
     */
    private static Instant toInstant(Object value) {
        if (value == null) return null;
        if (value instanceof Instant i) return i;
        if (value instanceof Timestamp ts) return ts.toInstant();
        if (value instanceof java.time.OffsetDateTime odt) return odt.toInstant();
        if (value instanceof java.time.LocalDateTime ldt) {
            // Should not happen with TIMESTAMPTZ, but guard against driver edge cases
            return ldt.toInstant(java.time.ZoneOffset.UTC);
        }
        throw new IllegalStateException(
                "Unexpected created_at type from JDBC driver: " + value.getClass().getName());
    }
}
