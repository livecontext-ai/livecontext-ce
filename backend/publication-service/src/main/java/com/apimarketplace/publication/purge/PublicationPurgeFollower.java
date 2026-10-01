package com.apimarketplace.publication.purge;

import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.auth.client.purge.PurgeFollower;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Deletes the publication schema's rows for a purged workspace (ORG-owned publications and
 * the org's receipts) or account (USER-owned publications), driven by {@code auth.purge_log}
 * (auth-client {@link PurgeFollower}).
 */
@Component
public class PublicationPurgeFollower implements PurgeFollower.Handler, PurgeFollower.Cursor {

    /**
     * {@code image_screening_decisions} FIRST: it references {@code workflow_publications}
     * with {@code ON DELETE RESTRICT} (V274, deliberately, so a publication cannot vanish
     * from under a screening verdict). The old purger's DELETE on publications failed inside
     * its savepoint whenever such a row existed and was silently skipped; here a failing
     * statement stops the follower instead, so the child must go before the parent or the
     * first org that ever had a screened image stalls every later publication purge.
     */
    public static final List<String> ORG_TABLES = List.of(
            "publication.image_screening_decisions",
            "publication.workflow_publications",
            "publication.publication_receipts");
    public static final List<String> USER_TABLES = List.of(
            "publication.image_screening_decisions",
            "publication.workflow_publications",
            "publication.creator_follows");

    private final JdbcTemplate jdbc;
    private final PurgeFollower follower;
    private final boolean enabled;

    public PublicationPurgeFollower(JdbcTemplate jdbc, AuthClient authClient,
                                    @Value("${purge.follower.enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.enabled = enabled;
        this.follower = new PurgeFollower("publication", authClient, this, this);
    }

    @PostConstruct
    void start() {
        if (enabled) {
            follower.start();
        }
    }

    @PreDestroy
    void stop() {
        follower.stop();
    }

    @Override
    public long read() {
        Long seq = jdbc.queryForObject("SELECT last_seq FROM publication.purge_cursor WHERE id = 1", Long.class);
        return seq == null ? 0L : seq;
    }

    @Override
    public void advanceTo(long seq) {
        jdbc.update("UPDATE publication.purge_cursor SET last_seq = GREATEST(last_seq, ?), updated_at = now() WHERE id = 1", seq);
    }

    @Override
    public void purgeOrganization(String orgId) {
        jdbc.update("DELETE FROM publication.image_screening_decisions WHERE publication_id IN "
                + "(SELECT id FROM publication.workflow_publications WHERE owner_type = 'ORG' AND owner_id::text = ?)", orgId);
        jdbc.update("DELETE FROM publication.workflow_publications WHERE owner_type = 'ORG' AND owner_id::text = ?", orgId);
        jdbc.update("DELETE FROM publication.publication_receipts WHERE organization_id::text = ?", orgId);
    }

    @Override
    public void purgeUser(String userId) {
        jdbc.update("DELETE FROM publication.image_screening_decisions WHERE publication_id IN "
                + "(SELECT id FROM publication.workflow_publications WHERE owner_type = 'USER' AND owner_id = ?)", userId);
        jdbc.update("DELETE FROM publication.workflow_publications WHERE owner_type = 'USER' AND owner_id = ?", userId);
        // Both directions: the people this account followed, and its own followers.
        jdbc.update("DELETE FROM publication.creator_follows WHERE follower_id = ? OR creator_id = ?", userId, userId);
    }
}
