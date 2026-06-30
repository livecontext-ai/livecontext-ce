package com.apimarketplace.agent.skill.bundle;

import com.apimarketplace.agent.domain.SkillEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Canonicalisation contract for the skill bundle payload: deterministic bytes (the signer
 * depends on it), skills sorted by key, the exact field set the applier reads, null icon
 * omitted, and {@code isDefaultActive} always carried (the cloud controls auto-activation).
 */
@DisplayName("SkillBundlePayload - canonical bytes")
class SkillBundlePayloadTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final Instant SNAP = Instant.parse("2026-06-29T10:00:00Z");

    private SkillEntity skill(UUID id, String name, String icon, boolean defaultActive) {
        SkillEntity s = new SkillEntity("t", name, "desc of " + name, icon, "instr of " + name, true);
        s.setId(id);
        s.setIsDefaultActive(defaultActive);
        return s;
    }

    @Test
    @DisplayName("two calls with the same input produce byte-identical output")
    void deterministic() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        List<SkillEntity> skills = List.of(skill(a, "A", "icon", true), skill(b, "B", null, false));

        byte[] first = SkillBundlePayload.canonicalBytes(7L, 1, "issuer", SNAP, skills);
        byte[] second = SkillBundlePayload.canonicalBytes(7L, 1, "issuer", SNAP, skills);

        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("input order does not change the bytes - skills are sorted by key (UUID)")
    void orderIndependent() {
        UUID a = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID b = UUID.fromString("00000000-0000-0000-0000-000000000002");

        byte[] ab = SkillBundlePayload.canonicalBytes(7L, 1, "i", SNAP,
                List.of(skill(a, "A", "x", true), skill(b, "B", "y", false)));
        byte[] ba = SkillBundlePayload.canonicalBytes(7L, 1, "i", SNAP,
                List.of(skill(b, "B", "y", false), skill(a, "A", "x", true)));

        assertThat(ab).isEqualTo(ba);
    }

    @Test
    @DisplayName("emits exactly key/name/description/instructions/isDefaultActive (+icon when set); icon omitted when null")
    void fieldSet() throws Exception {
        UUID withIcon = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
        UUID noIcon = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
        byte[] bytes = SkillBundlePayload.canonicalBytes(1L, 1, "issuer", SNAP,
                List.of(skill(withIcon, "WithIcon", "search", true),
                        skill(noIcon, "NoIcon", null, false)));

        JsonNode root = M.readTree(bytes);
        assertThat(root.get("version").asLong()).isEqualTo(1L);
        assertThat(root.get("skills")).hasSize(2);

        JsonNode first = root.get("skills").get(0);  // sorted by key -> ...aa first
        assertThat(first.get("key").asText()).isEqualTo(withIcon.toString());
        assertThat(first.get("name").asText()).isEqualTo("WithIcon");
        assertThat(first.get("description").asText()).isEqualTo("desc of WithIcon");
        assertThat(first.get("instructions").asText()).isEqualTo("instr of WithIcon");
        assertThat(first.get("icon").asText()).isEqualTo("search");
        assertThat(first.get("isDefaultActive").asBoolean()).isTrue();

        JsonNode second = root.get("skills").get(1);  // ...bb
        assertThat(second.has("icon")).as("null icon omitted").isFalse();
        assertThat(second.get("isDefaultActive").asBoolean()).isFalse();
    }
}
