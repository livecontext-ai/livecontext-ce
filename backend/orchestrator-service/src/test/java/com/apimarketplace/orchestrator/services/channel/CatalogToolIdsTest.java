package com.apimarketplace.orchestrator.services.channel;

import com.apimarketplace.orchestrator.domain.ToolRef;
import com.apimarketplace.orchestrator.services.approvalchannel.telegram.TelegramApprovalNotifier;
import com.apimarketplace.orchestrator.services.channel.discord.DiscordChannelConnector;
import com.apimarketplace.orchestrator.services.channel.slack.SlackChannelConnector;
import com.apimarketplace.orchestrator.services.channel.teams.TeamsChannelConnector;
import com.apimarketplace.orchestrator.services.channel.telegram.TelegramChannelConnector;
import com.apimarketplace.orchestrator.services.channel.whatsapp.WhatsAppChannelConnector;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every catalog tool a chat channel calls exists in the catalog seed, under the exact id it is
 * called by.
 *
 * <p>The ids are derived, not declared: {@code <api slug>/<tool slug>}, where the importer builds
 * the tool slug from the snake-cased API NAME plus the endpoint name ("WhatsApp Business" gives
 * "whats-app-business-..."). A connector with a mistyped id compiles, passes every mocked test and
 * fails only against a real catalog, with "tool not found" on the first message. This rebuilds
 * each id from {@code scripts/api-migrations/*.json} the way the importer does and checks every
 * {@link ToolRef} constant of every channel class against it.
 */
@DisplayName("Chat-channel catalog tool ids exist in the catalog seed")
class CatalogToolIdsTest {

    /** Every class that calls a catalog tool on behalf of a chat channel. */
    private static final List<Class<?>> CALLERS = List.of(
            TelegramChannelConnector.class, TelegramApprovalNotifier.class, SlackChannelConnector.class,
            DiscordChannelConnector.class, WhatsAppChannelConnector.class, TeamsChannelConnector.class);

    private static final List<String> SEEDS = List.of(
            "telegram.json", "slack.json", "discord.json", "whatsapp_business.json", "microsoft_teams.json");

    @Test
    @DisplayName("each ToolRef constant names a tool the importer creates")
    void everyToolRefExists() throws Exception {
        Set<String> catalog = catalogToolIds();
        List<String> used = toolRefs();

        assertThat(used).as("the channel classes call catalog tools").hasSizeGreaterThan(15);
        assertThat(catalog).containsAll(used);
    }

    @Test
    @DisplayName("the id derivation is the importer's: a known multi-word API gives its known id")
    void derivationMatchesTheImporter() {
        // Guards the helper itself: a derivation that went wrong in the same way as a connector
        // would make the main test agree with the mistake.
        assertThat(apiSlug("WhatsApp Business")).isEqualTo("whatsapp-business");
        assertThat(toolSlug("WhatsApp Business", "send_text_message"))
                .isEqualTo("whats-app-business-send-text-message");
        assertThat(apiSlug("Microsoft Teams") + "/" + toolSlug("Microsoft Teams", "list_chats"))
                .isEqualTo("microsoft-teams/microsoft-teams-list-chats");
    }

    private static List<String> toolRefs() throws IllegalAccessException {
        List<String> ids = new ArrayList<>();
        for (Class<?> type : CALLERS) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) && field.getType() == ToolRef.class) {
                    field.setAccessible(true);
                    ids.add(((ToolRef) field.get(null)).toolId());
                }
            }
        }
        return ids;
    }

    private static Set<String> catalogToolIds() throws Exception {
        Path dir = seedDirectory();
        ObjectMapper mapper = new ObjectMapper();
        Set<String> ids = new TreeSet<>();
        for (String seed : SEEDS) {
            JsonNode root = mapper.readTree(Files.readString(dir.resolve(seed)));
            String apiName = root.get("apiName").asText();
            for (JsonNode endpoint : root.get("endpoints")) {
                ids.add(apiSlug(apiName) + "/" + toolSlug(apiName, endpoint.get("name").asText()));
            }
        }
        return ids;
    }

    /** The seed directory, found from wherever the build runs the tests. */
    private static Path seedDirectory() {
        File dir = new File("").getAbsoluteFile();
        while (dir != null) {
            Path candidate = dir.toPath().resolve("scripts").resolve("api-migrations");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            dir = dir.getParentFile();
        }
        throw new IllegalStateException("scripts/api-migrations not found above " + new File("").getAbsolutePath());
    }

    /** SlugUtils.generateSlug: lowercase, spaces and underscores to dashes, the rest dropped. */
    static String apiSlug(String apiName) {
        return apiName.toLowerCase(Locale.ROOT).trim().replaceAll("[\\s_]+", "-").replaceAll("[^a-z0-9\\-]", "")
                .replaceAll("-+", "-").replaceAll("^-|-$", "");
    }

    /** ApiMigrationImporter: slugify(toSnakeCase(apiName) + "-" + toSnakeCase(endpointName)). */
    static String toolSlug(String apiName, String endpointName) {
        return (snake(apiName) + "-" + snake(endpointName)).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
    }

    private static String snake(String value) {
        return value.trim().replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_").replaceAll("_+", "_").replaceAll("^_+", "").replaceAll("_+$", "");
    }
}
