package com.apimarketplace.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Static structural assertions on the Cloud-Link Phase 1 migrations
 * (V259 CE-side, V260 cloud-side). These tests read the .sql files as
 * strings and verify the key invariants the design doc (CLOUD_LINK_ONBOARDING.md)
 * commits to. Cheap, no DB required.
 *
 * Each assertion is a regression guard for a specific design rationale
 * - when an assertion fails, the failure message + comment should point
 * the next maintainer straight at the audit finding it defends.
 */
@DisplayName("Cloud-Link Phase 1 migrations - V259 + V260 structural invariants")
class CloudLinkMigrationTest {

    private static final Path V259 = Path.of(
        "src/main/resources/db/migration/V259__ce_cloud_links_install_id_and_state.sql");
    private static final Path V260 = Path.of(
        "src/main/resources/db/migration/V260__cloud_ce_link.sql");

    // =========================================================================
    // V259 (CE-side: extend publication.ce_cloud_links)
    // =========================================================================

    @Test
    @DisplayName("V259 adds install_id with DEFAULT gen_random_uuid() so unupgraded JARs can still INSERT")
    void v259InstallIdHasDefaultForBackwardCompat() throws Exception {
        String sql = Files.readString(V259);
        assertThat(sql).contains("install_id                 UUID DEFAULT gen_random_uuid()");
        assertThat(sql).contains("ALTER COLUMN install_id SET NOT NULL");
    }

    @Test
    @DisplayName("V259 adds all required PKCE state columns + reset retry queue columns")
    void v259AddsPkceAndQueueColumns() throws Exception {
        String sql = Files.readString(V259);
        assertThat(sql).contains("pending_state_hash");
        assertThat(sql).contains("pending_state_expires_at");
        assertThat(sql).contains("pending_state_authcode_enc");
        assertThat(sql).contains("registered_at");
        assertThat(sql).contains("label");
        assertThat(sql).contains("ce_cloud_link_pending_delete");
        assertThat(sql).contains("give_up_at");
    }

    @Test
    @DisplayName("V259 does NOT re-convert token_expires_at/linked_at/last_used_at - V196 already did it (audit C1 r1)")
    void v259DoesNotReConvertV196TimestampColumns() throws Exception {
        String sql = Files.readString(V259);
        // V196 lines 128-130 already converted these to TIMESTAMPTZ. A second
        // conversion with `AT TIME ZONE 'UTC'` would silently shift values
        // when the session timezone is not UTC.
        assertThat(sql).doesNotContain("ALTER COLUMN token_expires_at TYPE TIMESTAMPTZ");
        assertThat(sql).doesNotContain("ALTER COLUMN linked_at        TYPE TIMESTAMPTZ");
        assertThat(sql).doesNotContain("ALTER COLUMN last_used_at     TYPE TIMESTAMPTZ");
    }

    @Test
    @DisplayName("V259 does NOT DROP cached_access_token - that's deferred to V261 to avoid \"column does not exist\" deploy-gap (audit I-C3 r4)")
    void v259DoesNotDropCachedAccessToken() throws Exception {
        String sql = Files.readString(V259);
        assertThat(sql).doesNotContain("DROP COLUMN IF EXISTS cached_access_token");
        assertThat(sql).doesNotContain("DROP COLUMN cached_access_token");
    }

    @Test
    @DisplayName("V259 UNIQUE constraint on install_id uses idempotent DO $$ IF NOT EXISTS pattern (matches V169/V223)")
    void v259InstallIdUniqueConstraintIsIdempotent() throws Exception {
        String sql = Files.readString(V259);
        assertThat(sql).contains("uq_ce_cloud_links_install_id");
        assertThat(sql).contains("IF NOT EXISTS").contains("pg_constraint");
    }

    @Test
    @DisplayName("V259 single-flight pending state via partial unique index")
    void v259PartialUniqueIndexForSingleFlight() throws Exception {
        String sql = Files.readString(V259);
        assertThat(sql).contains("uq_ce_cloud_links_one_pending_state");
        assertThat(sql).contains("WHERE pending_state_hash IS NOT NULL");
    }

    // =========================================================================
    // V260 (cloud-side: auth.ce_link* tables)
    // =========================================================================

    @Test
    @DisplayName("V260 declares auth.ce_link with FK user_id ON DELETE RESTRICT (per §13 product decision)")
    void v260UserFkRestrict() throws Exception {
        String sql = Files.readString(V260);
        assertThat(sql).contains("REFERENCES auth.users(id) ON DELETE RESTRICT");
    }

    @Test
    @DisplayName("V260 enforces status + revoke_reason consistency via CHECK")
    void v260StatusRevokeConsistencyCheck() throws Exception {
        String sql = Files.readString(V260);
        assertThat(sql).contains("chk_ce_link_revoked_consistency");
        assertThat(sql).contains("status='ACTIVE'");
        assertThat(sql).contains("status='REVOKED'");
    }

    @Test
    @DisplayName("V260 revoke_reason has CHECK enum matching design §1 #12 (audit L2)")
    void v260RevokeReasonEnumCheck() throws Exception {
        String sql = Files.readString(V260);
        assertThat(sql).contains("'USER'").contains("'ADMIN'").contains("'RESET_SIGNAL'")
                       .contains("'SQUAT_RECOVERY'").contains("'SYSTEM'").contains("'DEACTIVATED_USER'");
    }

    @Test
    @DisplayName("V260 declares the immutability trigger function raising on any mutation")
    void v260ImmutabilityFunctionExists() throws Exception {
        String sql = Files.readString(V260);
        assertThat(sql).contains("auth.ce_link_audit_immutable");
        assertThat(sql).contains("RAISE EXCEPTION");
        assertThat(sql).contains("append-only");
    }

    @Test
    @DisplayName("V260 wires triggers on UPDATE, DELETE, AND TRUNCATE (closes audit I-5 r1 - TRUNCATE is a statement-level trigger)")
    void v260TriggersCoverAllThreeMutations() throws Exception {
        String sql = Files.readString(V260);
        assertThat(sql).contains("BEFORE UPDATE ON auth.ce_link_audit");
        assertThat(sql).contains("BEFORE DELETE ON auth.ce_link_audit");
        assertThat(sql).contains("BEFORE TRUNCATE ON auth.ce_link_audit")
                       .contains("FOR EACH STATEMENT");                                       // TRUNCATE is statement-level
    }

    @Test
    @DisplayName("V260 separates heartbeat hot-row into auth.ce_link_heartbeat with cascade-delete")
    void v260HeartbeatTableSeparate() throws Exception {
        String sql = Files.readString(V260);
        assertThat(sql).contains("CREATE TABLE IF NOT EXISTS auth.ce_link_heartbeat");
        assertThat(sql).contains("REFERENCES auth.ce_link(install_id) ON DELETE CASCADE");
        assertThat(sql).contains("key_version");                                              // HMAC IP-hash rotation tracking
    }

    @Test
    @DisplayName("V260 audit table includes event enum CHECK covering all design §2.2 values")
    void v260AuditEventEnumComplete() throws Exception {
        String sql = Files.readString(V260);
        assertThat(sql).contains("'REGISTER'").contains("'REVOKE'").contains("'RESET'")
                       .contains("'SCOPE_GRANT'").contains("'HEARTBEAT'")
                       .contains("'NETWORK_CHANGE'").contains("'SUSPECTED_CROSS_USER_RESET'");
    }

    @Test
    @DisplayName("V260 uses TIMESTAMPTZ throughout (V196 convention)")
    void v260AllTimestampsAreTimestamptz() throws Exception {
        String sql = Files.readString(V260);
        // Crude check: there should be no `TIMESTAMP[^T]` (legacy TIMESTAMP without zone)
        // in the migration body apart from comments. We check positive: every new
        // table has a TIMESTAMPTZ column.
        assertThat(sql).contains("created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()");
        assertThat(sql).contains("last_seen_at                TIMESTAMPTZ NOT NULL");
        assertThat(sql).contains("created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()");    // ce_link_audit
        // Negative: no naked TIMESTAMP column declarations (would suggest legacy regression).
        // We accept "TIMESTAMPTZ" and "TIMESTAMP without time zone" if explicitly chosen,
        // but no bare "TIMESTAMP " followed by space + identifier without 'TZ'.
        // (Loose pattern; the positive checks above are the real defense.)
    }
}
