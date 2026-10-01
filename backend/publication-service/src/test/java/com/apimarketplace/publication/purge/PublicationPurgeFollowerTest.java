package com.apimarketplace.publication.purge;

import com.apimarketplace.auth.client.AuthClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@DisplayName("PublicationPurgeFollower")
class PublicationPurgeFollowerTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PublicationPurgeFollower follower = new PublicationPurgeFollower(jdbc, mock(AuthClient.class), false);

    /**
     * image_screening_decisions references workflow_publications with ON DELETE RESTRICT
     * (V274). The old purger's publication DELETE failed in its savepoint whenever such a row
     * existed and was skipped; a follower stops on failure instead, so the child MUST be
     * deleted first or the first screened image stalls every later publication purge. This
     * order is the regression test for that finding (audit 2026-09-02).
     */
    @Test
    @DisplayName("An ORG purge deletes screening decisions BEFORE the workspace's publications, then its receipts")
    void orgPurge() {
        follower.purgeOrganization(ORG);

        InOrder order = inOrder(jdbc);
        order.verify(jdbc).update("DELETE FROM publication.image_screening_decisions WHERE publication_id IN "
                + "(SELECT id FROM publication.workflow_publications WHERE owner_type = 'ORG' AND owner_id::text = ?)", ORG);
        order.verify(jdbc).update("DELETE FROM publication.workflow_publications WHERE owner_type = 'ORG' AND owner_id::text = ?", ORG);
        order.verify(jdbc).update("DELETE FROM publication.publication_receipts WHERE organization_id::text = ?", ORG);
        verify(jdbc, times(3)).update(anyString(), eq(ORG));
    }

    /**
     * Used to be a cross-schema statement in AccountPurgeService; the account purge now logs a
     * USER subject and this follower drops the user's own publications, screening rows first
     * for the same FK reason.
     */
    @Test
    @DisplayName("A USER purge deletes screening decisions BEFORE the account's USER-owned publications, then its follows both ways")
    void userPurge() {
        follower.purgeUser("42");

        InOrder order = inOrder(jdbc);
        order.verify(jdbc).update("DELETE FROM publication.image_screening_decisions WHERE publication_id IN "
                + "(SELECT id FROM publication.workflow_publications WHERE owner_type = 'USER' AND owner_id = ?)", "42");
        order.verify(jdbc).update("DELETE FROM publication.workflow_publications WHERE owner_type = 'USER' AND owner_id = ?", "42");
        order.verify(jdbc).update("DELETE FROM publication.creator_follows WHERE follower_id = ? OR creator_id = ?", "42", "42");
        verifyNoMoreInteractions(jdbc);
    }

    @Test
    @DisplayName("The declared table lists name the screening child before its parent")
    void listsNameChildBeforeParent() {
        assertThat(PublicationPurgeFollower.ORG_TABLES.indexOf("publication.image_screening_decisions"))
                .isLessThan(PublicationPurgeFollower.ORG_TABLES.indexOf("publication.workflow_publications"));
        assertThat(PublicationPurgeFollower.USER_TABLES.indexOf("publication.image_screening_decisions"))
                .isLessThan(PublicationPurgeFollower.USER_TABLES.indexOf("publication.workflow_publications"));
    }

    @Test
    @DisplayName("The cursor lives in publication.purge_cursor and only moves forward")
    void cursor() {
        follower.advanceTo(2L);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), eq(2L));
        assertThat(sql.getValue()).contains("publication.purge_cursor").contains("GREATEST(last_seq, ?)");
    }
}
