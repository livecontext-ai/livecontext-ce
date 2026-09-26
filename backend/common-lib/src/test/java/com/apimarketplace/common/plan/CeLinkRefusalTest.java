package com.apimarketplace.common.plan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CeLinkRefusal - the shared 403 bodies of every CE-link-gated endpoint")
class CeLinkRefusalTest {

    @Test
    @DisplayName("ACTIVE access is not refused: no response, no error code")
    void activeIsNotRefused() {
        CeLinkAccessResult active = CeLinkAccessResult.active("PRO");

        assertThat(CeLinkRefusal.response(active)).isNull();
        assertThat(CeLinkRefusal.errorCode(active)).isNull();
    }

    @Test
    @DisplayName("NOT_LINKED keeps today's wire shape: 403 {error: CE_LINK_NOT_ACTIVE} and nothing else")
    void notLinkedKeepsLegacyShape() {
        ResponseEntity<Map<String, Object>> response = CeLinkRefusal.response(CeLinkAccessResult.notLinked());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsExactly(Map.entry("error", "CE_LINK_NOT_ACTIVE"));
        assertThat(CeLinkRefusal.errorCode(CeLinkAccessResult.notLinked())).isEqualTo("CE_LINK_NOT_ACTIVE");
    }

    @Test
    @DisplayName("PLAN_REQUIRED answers 403 with the code, the plan and the message older installs display")
    void planRequiredBody() {
        ResponseEntity<Map<String, Object>> response =
                CeLinkRefusal.response(CeLinkAccessResult.planRequired("FREE"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody())
                .containsEntry("error", "CLOUD_LINK_PLAN_REQUIRED")
                .containsEntry("planCode", "FREE")
                .containsEntry("message", "Linking a self-hosted install to LiveContext Cloud requires a paid plan. "
                        + "Choose a plan at https://livecontext.ai/app/settings/pricing and your install "
                        + "reconnects automatically.")
                .hasSize(3);
        assertThat(CeLinkRefusal.errorCode(CeLinkAccessResult.planRequired("FREE")))
                .isEqualTo("CLOUD_LINK_PLAN_REQUIRED");
    }

    @Test
    @DisplayName("A PLAN_REQUIRED verdict keeps the real plan code (CREDIT_PACK is reported, not rewritten)")
    void planRequiredKeepsPlanCode() {
        assertThat(CeLinkRefusal.planRequiredBody("CREDIT_PACK")).containsEntry("planCode", "CREDIT_PACK");
    }

    @Test
    @DisplayName("A blank or missing plan code reads as FREE in the body, never null")
    void blankPlanReadsAsFree() {
        assertThat(CeLinkRefusal.planRequiredBody(null)).containsEntry("planCode", "FREE");
        assertThat(CeLinkRefusal.planRequiredBody(" ")).containsEntry("planCode", "FREE");
    }

    @Test
    @DisplayName("A null verdict fails closed as not linked")
    void nullResultFailsClosed() {
        assertThat(CeLinkRefusal.response(null).getBody()).containsEntry("error", "CE_LINK_NOT_ACTIVE");
        assertThat(CeLinkRefusal.errorCode(null)).isEqualTo("CE_LINK_NOT_ACTIVE");
        assertThat(new CeLinkAccessResult(null, null).access()).isEqualTo(CeLinkAccess.NOT_LINKED);
    }
}
