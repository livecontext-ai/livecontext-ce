package com.apimarketplace.conversation.purge;

import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.auth.client.purge.PurgeFollower;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Deletes the conversation schema's rows for a purged workspace, driven by
 * {@code auth.purge_log} (auth-client {@link PurgeFollower}). Messages first (they carry no
 * organization column of their own), then the conversations.
 */
@Component
public class ConversationPurgeFollower implements PurgeFollower.Handler, PurgeFollower.Cursor {

    public static final List<String> ORG_TABLES = List.of(
            "conversation.messages",
            "conversation.conversations");

    private final JdbcTemplate jdbc;
    private final PurgeFollower follower;
    private final boolean enabled;

    /**
     * conversation-service has no AuthClient bean of its own (it never needed one), so the
     * follower builds a plain client from the same URL every other service uses for auth.
     */
    @org.springframework.beans.factory.annotation.Autowired   // two constructors: Spring must be told which one
    public ConversationPurgeFollower(JdbcTemplate jdbc,
                                     @Value("${services.auth-service.url:http://localhost:8083}") String authServiceUrl,
                                     @Value("${gateway.filter.secret-key:${GATEWAY_SECRET_KEY:}}") String gatewaySecretKey,
                                     @Value("${purge.follower.enabled:true}") boolean enabled) {
        // Signed: /api/internal/auth/purges sits under the prefix auth-service can HMAC-gate.
        this(jdbc, new AuthClient(authServiceUrl, gatewaySecretKey), enabled);
    }

    ConversationPurgeFollower(JdbcTemplate jdbc, AuthClient authClient, boolean enabled) {
        this.jdbc = jdbc;
        this.enabled = enabled;
        this.follower = new PurgeFollower("conversation", authClient, this, this);
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
        Long seq = jdbc.queryForObject("SELECT last_seq FROM conversation.purge_cursor WHERE id = 1", Long.class);
        return seq == null ? 0L : seq;
    }

    @Override
    public void advanceTo(long seq) {
        jdbc.update("UPDATE conversation.purge_cursor SET last_seq = GREATEST(last_seq, ?), updated_at = now() WHERE id = 1", seq);
    }

    @Override
    public void purgeOrganization(String orgId) {
        jdbc.update("DELETE FROM conversation.messages WHERE conversation_id IN "
                + "(SELECT id FROM conversation.conversations WHERE organization_id::text = ?)", orgId);
        jdbc.update("DELETE FROM conversation.conversations WHERE organization_id::text = ?", orgId);
    }

    @Override
    public void purgeUser(String userId) {
        // Conversations always belong to a workspace.
    }
}
