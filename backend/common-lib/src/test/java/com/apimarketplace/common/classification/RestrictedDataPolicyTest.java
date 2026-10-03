package com.apimarketplace.common.classification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RestrictedDataPolicyTest {

    @Test
    @DisplayName("Gmail and Drive are restricted under every identifier the runtime sees, Forms is not")
    void restrictedIntegrationsAcrossIdentifiers() {
        assertThat(RestrictedDataPolicy.isRestrictedIntegration("gmail")).isTrue();
        assertThat(RestrictedDataPolicy.isRestrictedIntegration("Gmail")).isTrue();
        assertThat(RestrictedDataPolicy.isRestrictedIntegration("googledrive")).isTrue();
        assertThat(RestrictedDataPolicy.isRestrictedIntegration("Google Drive")).isTrue();
        // Forms requests sensitive scopes only since its unused "drive" scope was dropped (2026-10-01).
        assertThat(RestrictedDataPolicy.isRestrictedIntegration("google_forms")).isFalse();
        assertThat(RestrictedDataPolicy.isRestrictedIntegration("googleforms")).isFalse();
        assertThat(RestrictedDataPolicy.isRestrictedIntegration("slack")).isFalse();
        assertThat(RestrictedDataPolicy.isRestrictedIntegration(null)).isFalse();
        assertThat(RestrictedDataPolicy.isRestrictedIntegration("  ")).isFalse();
    }

    @Test
    @DisplayName("A catalog tool reference is classified by its API part")
    void toolReference() {
        assertThat(RestrictedDataPolicy.forToolReference("gmail/list_messages")).isEqualTo(DataSensitivity.RESTRICTED);
        assertThat(RestrictedDataPolicy.forToolReference("github/list_repos")).isEqualTo(DataSensitivity.NORMAL);
        assertThat(RestrictedDataPolicy.forToolReference("crud/insert_row")).isEqualTo(DataSensitivity.NORMAL);
        assertThat(RestrictedDataPolicy.forToolReference(null)).isEqualTo(DataSensitivity.NORMAL);
    }

    @Test
    @DisplayName("Tool metadata: iconSlug, apiName, the propagated tag and the nested step metadata all classify")
    void toolMetadata() {
        assertThat(RestrictedDataPolicy.fromToolMetadata(Map.of("iconSlug", "gmail"))).isEqualTo(DataSensitivity.RESTRICTED);
        assertThat(RestrictedDataPolicy.fromToolMetadata(Map.of("apiName", "Google Drive"))).isEqualTo(DataSensitivity.RESTRICTED);
        assertThat(RestrictedDataPolicy.fromToolMetadata(Map.of(DataSensitivity.CREDENTIAL_KEY, "RESTRICTED")))
                .isEqualTo(DataSensitivity.RESTRICTED);
        assertThat(RestrictedDataPolicy.fromToolMetadata(Map.of("metadata", Map.of("iconSlug", "googledrive"))))
                .isEqualTo(DataSensitivity.RESTRICTED);
        // A generic wrapper key must not mask a restricted one further down the list.
        assertThat(RestrictedDataPolicy.fromToolMetadata(Map.of("iconSlug", "catalog", "apiName", "Gmail")))
                .isEqualTo(DataSensitivity.RESTRICTED);
        assertThat(RestrictedDataPolicy.fromToolMetadata(Map.of("iconSlug", "github"))).isEqualTo(DataSensitivity.NORMAL);
        assertThat(RestrictedDataPolicy.fromToolMetadata(null)).isEqualTo(DataSensitivity.NORMAL);
    }

    @Test
    @DisplayName("regression: the 'connect Gmail' card (credentialNeeded) read nothing and classifies NORMAL")
    void credentialNeededCardReadNothing() {
        // The exact metadata the catalog returns when the user has no Gmail connection: it names
        // Gmail three ways so the card can be drawn, and the call never reached Google. Classifying
        // it RESTRICTED marked the whole conversation (and every table row it wrote afterwards,
        // for good) for a mailbox it never opened.
        Map<String, Object> connectCard = Map.of(
                RestrictedDataPolicy.CREDENTIAL_NEEDED_KEY, true,
                "serviceApprovalRequested", true,
                "serviceType", "gmail",
                "iconSlug", "gmail",
                "services", java.util.List.of(Map.of("serviceType", "gmail", "iconSlug", "gmail")));

        assertThat(RestrictedDataPolicy.fromToolMetadata(connectCard)).isEqualTo(DataSensitivity.NORMAL);
    }

    @Test
    @DisplayName("only the boolean flag exempts; an explicit restricted tag still wins over it")
    void credentialNeededExemptionIsNarrow() {
        assertThat(RestrictedDataPolicy.fromToolMetadata(Map.of(
                RestrictedDataPolicy.CREDENTIAL_NEEDED_KEY, false, "iconSlug", "gmail")))
                .isEqualTo(DataSensitivity.RESTRICTED);
        assertThat(RestrictedDataPolicy.fromToolMetadata(Map.of(
                RestrictedDataPolicy.CREDENTIAL_NEEDED_KEY, "true", "iconSlug", "gmail")))
                .isEqualTo(DataSensitivity.RESTRICTED);
        assertThat(RestrictedDataPolicy.fromToolMetadata(Map.of(
                RestrictedDataPolicy.CREDENTIAL_NEEDED_KEY, true,
                DataSensitivity.CREDENTIAL_KEY, "RESTRICTED", "iconSlug", "gmail")))
                .isEqualTo(DataSensitivity.RESTRICTED);
    }

    @Test
    @DisplayName("the flag exempts only the card's own keys: a nested step output naming Gmail stays RESTRICTED")
    void credentialNeededDoesNotExemptNestedMetadata() {
        // A flattened workflow step output carrying the flag at the top must not hide the Gmail
        // step output nested under it: that one did read a mailbox.
        assertThat(RestrictedDataPolicy.fromToolMetadata(Map.of(
                RestrictedDataPolicy.CREDENTIAL_NEEDED_KEY, true,
                "metadata", Map.of("iconSlug", "gmail"))))
                .isEqualTo(DataSensitivity.RESTRICTED);
    }

    @Test
    @DisplayName("a relayed tool call's forwarded tag is restored into its credentials: RESTRICTED only, never relaxing")
    void forwardedTagIsRestoredRestrictedOnly() {
        Map<String, Object> credentials = new HashMap<>();
        DataSensitivity.restoreForwardedTag(Map.of(DataSensitivity.REQUEST_FIELD, "restricted"), credentials);
        assertThat(credentials).containsEntry(DataSensitivity.CREDENTIAL_KEY, "RESTRICTED");

        for (Object relaxed : new Object[] {"NORMAL", "garbage", 42}) {
            Map<String, Object> untouched = new HashMap<>(Map.of(DataSensitivity.CREDENTIAL_KEY, "RESTRICTED"));
            DataSensitivity.restoreForwardedTag(Map.of(DataSensitivity.REQUEST_FIELD, relaxed), untouched);
            // A body can never downgrade what the execution already holds.
            assertThat(untouched).containsEntry(DataSensitivity.CREDENTIAL_KEY, "RESTRICTED");
        }
        Map<String, Object> none = new HashMap<>();
        DataSensitivity.restoreForwardedTag(Map.of(), none);
        DataSensitivity.restoreForwardedTag(null, none);
        assertThat(none).isEmpty();
    }

    @Test
    @DisplayName("A self-referencing metadata map does not recurse forever")
    void selfReferencingMetadata() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("metadata", metadata);
        assertThat(RestrictedDataPolicy.fromToolMetadata(metadata)).isEqualTo(DataSensitivity.NORMAL);
    }

    @Test
    @DisplayName("Provider allow-list: Anthropic, OpenAI and mock only; tier-dependent vendors (Gemini, Mistral), aggregators, other vendors and CLI bridges are refused")
    void providerAllowList() {
        for (String allowed : new String[] {"anthropic", "OpenAI", "mock"}) {
            assertThat(RestrictedDataPolicy.mayReceiveRestricted(allowed)).as(allowed).isTrue();
        }
        for (String refused : new String[] {"google", "mistral", "openrouter", "zai", "qwen", "moonshot", "minimax", "deepseek",
                "perplexity", "claude-code", "codex", "gemini-cli", "mistral-vibe", "", null}) {
            assertThat(RestrictedDataPolicy.mayReceiveRestricted(refused)).as(String.valueOf(refused)).isFalse();
        }
        assertThat(RestrictedDataPolicy.mayReceive("openrouter", DataSensitivity.NORMAL)).isTrue();
        assertThat(RestrictedDataPolicy.mayReceive("openrouter", DataSensitivity.RESTRICTED)).isFalse();
        assertThat(RestrictedDataPolicy.mayReceive("openrouter", null)).isTrue();
    }

    @Test
    @DisplayName("allow-list switch: enforced on managed cloud by default, relaxed on self-hosted (embedded), overridable")
    void enforcementResolution() {
        assertThat(RestrictedDataPolicyConfig.resolve(null, "keycloak")).isTrue();
        assertThat(RestrictedDataPolicyConfig.resolve(null, "")).isTrue();
        assertThat(RestrictedDataPolicyConfig.resolve(null, null)).isTrue();
        assertThat(RestrictedDataPolicyConfig.resolve(null, "embedded")).isFalse();
        assertThat(RestrictedDataPolicyConfig.resolve("true", "embedded")).isTrue();
        assertThat(RestrictedDataPolicyConfig.resolve("false", "keycloak")).isFalse();
    }

    @Test
    @DisplayName("with the allow-list not enforced, every provider may receive restricted data; the default is enforced")
    void enforcementSwitch() {
        assertThat(RestrictedDataPolicy.isLlmAllowListEnforced()).isTrue();
        try {
            RestrictedDataPolicy.setLlmAllowListEnforced(false);
            assertThat(RestrictedDataPolicy.mayReceiveRestricted("openrouter")).isTrue();
            assertThat(RestrictedDataPolicy.mayReceive("claude-code", DataSensitivity.RESTRICTED)).isTrue();
        } finally {
            RestrictedDataPolicy.setLlmAllowListEnforced(true);
        }
        assertThat(RestrictedDataPolicy.mayReceiveRestricted("openrouter")).isFalse();
    }

    @Test
    @DisplayName("the refusal names the provider and the models that can process the data")
    void refusalMessage() {
        assertThat(RestrictedDataPolicy.refusalMessage("deepseek"))
                .startsWith(RestrictedDataPolicy.REFUSAL_CODE + ": ")
                .contains("deepseek").contains("Gmail").contains("Anthropic or OpenAI");
    }

    @Test
    @DisplayName("the publish refusal carries the code, names the resource and says unpublishing still works")
    void publishRefusalMessage() {
        assertThat(RestrictedDataPolicy.publishRefusalMessage("table"))
                .startsWith(RestrictedDataPolicy.REFUSAL_CODE + ": ")
                .contains("no table can be published")
                .contains("unpublishing and reads still work")
                .contains("Gmail or Google Drive")
                .doesNotContain(String.valueOf((char) 0x2014)).doesNotContain(String.valueOf((char) 0x2013));
        assertThat(RestrictedDataPolicy.publishRefusalMessage(null)).contains("no resource can be published");
    }

    @Test
    @DisplayName("scopes: full URL, short name, any case and trailing slash all resolve; non-restricted scopes do not")
    void scopeNormalization() {
        assertThat(RestrictedDataPolicy.isRestrictedScope("https://www.googleapis.com/auth/gmail.readonly")).isTrue();
        assertThat(RestrictedDataPolicy.isRestrictedScope("gmail.readonly")).isTrue();
        assertThat(RestrictedDataPolicy.isRestrictedScope("GMAIL.Readonly")).isTrue();
        assertThat(RestrictedDataPolicy.isRestrictedScope("https://www.googleapis.com/auth/drive/")).isTrue();
        assertThat(RestrictedDataPolicy.isRestrictedScope("https://mail.google.com")).isTrue();
        assertThat(RestrictedDataPolicy.isRestrictedScope("mail.google.com/")).isTrue();
        // Google classifies this one RESTRICTED (it grants read access to the user's Photos-backed
        // Drive folder), even though it is easy to mistake for the Photos API's own scopes, which
        // this platform does not use. See the union check below against the fix/casa-readiness
        // branch's RestrictedGoogleScopes, which omitted it.
        assertThat(RestrictedDataPolicy.isRestrictedScope("https://www.googleapis.com/auth/drive.photos.readonly")).isTrue();
        assertThat(RestrictedDataPolicy.isRestrictedScope("gmail.send")).isFalse();
        assertThat(RestrictedDataPolicy.isRestrictedScope("gmail.labels")).isFalse();
        assertThat(RestrictedDataPolicy.isRestrictedScope("https://www.googleapis.com/auth/drive.file")).isFalse();
        assertThat(RestrictedDataPolicy.isRestrictedScope("https://www.googleapis.com/auth/drive.appdata")).isFalse();
        assertThat(RestrictedDataPolicy.isRestrictedScope("https://www.googleapis.com/auth/calendar")).isFalse();
        assertThat(RestrictedDataPolicy.isRestrictedScope("https://www.googleapis.com/auth/calendar.events")).isFalse();
        assertThat(RestrictedDataPolicy.isRestrictedScope("https://www.googleapis.com/auth/contacts.readonly")).isFalse();
        assertThat(RestrictedDataPolicy.isRestrictedScope("  ")).isFalse();
        assertThat(RestrictedDataPolicy.isRestrictedScope(null)).isFalse();
    }

    @Test
    @DisplayName("LC-066/CASA re-audit item 6: RESTRICTED_GOOGLE_SCOPES is the UNION of this list and "
        + "fix/casa-readiness's duplicate RestrictedGoogleScopes.SHORT_NAMES, so that branch's merge can "
        + "delete its copy and call grantedScopesIncludeRestricted/isRestrictedScope here instead")
    void isTheAuthoritativeUnionOfTheOtherBranchsDuplicateList() {
        // Mirrors backend/common-storage-service/.../classification/RestrictedGoogleScopes.SHORT_NAMES
        // on fix/casa-readiness (fork point 0f9415ac57), short-name form (the part after
        // "https://www.googleapis.com/auth/"). Kept as a literal copy, not a cross-module import,
        // because this module cannot depend on common-storage-service and the whole point of this
        // test is to catch drift between the two lists by VALUE, not by reference.
        java.util.Set<String> otherBranchShortNames = java.util.Set.of(
                "gmail.readonly", "gmail.modify", "gmail.compose", "gmail.insert", "gmail.metadata",
                "gmail.settings.basic", "gmail.settings.sharing",
                "drive", "drive.readonly", "drive.metadata", "drive.metadata.readonly",
                "drive.scripts", "drive.activity", "drive.activity.readonly");

        for (String shortName : otherBranchShortNames) {
            assertThat(RestrictedDataPolicy.isRestrictedScope(shortName))
                    .as("RestrictedDataPolicy must recognise every scope the other branch's "
                        + "duplicate list already recognised: %s", shortName)
                    .isTrue();
        }
        // The other branch's FULL_MAIL_SCOPE constant.
        assertThat(RestrictedDataPolicy.isRestrictedScope("https://mail.google.com/")).isTrue();

        // The one addition this list carries beyond the other branch's (deliberately, per Google's
        // classification - see scopeNormalization above): not a subset relationship either way
        // would be a silent regression for whichever side is missing it after the merge.
        assertThat(RestrictedDataPolicy.RESTRICTED_GOOGLE_SCOPES)
                .as("must be a strict superset of the other branch's short-name list, mapped to full URLs, "
                    + "plus drive.photos.readonly")
                .containsAll(otherBranchShortNames.stream()
                        .map(s -> "https://www.googleapis.com/auth/" + s)
                        .collect(java.util.stream.Collectors.toSet()))
                .contains("https://www.googleapis.com/auth/drive.photos.readonly");
    }

    @Test
    @DisplayName("grantedScopesIncludeRestricted reads lists and space / comma separated token strings")
    void grantedScopes() {
        assertThat(RestrictedDataPolicy.grantedScopesIncludeRestricted(java.util.List.of(
                "openid email https://www.googleapis.com/auth/gmail.readonly"))).isTrue();
        assertThat(RestrictedDataPolicy.grantedScopesIncludeRestricted(java.util.List.of(
                "gmail.send,drive.file", "calendar"))).isFalse();
        assertThat(RestrictedDataPolicy.grantedScopesIncludeRestricted(java.util.List.of("drive.readonly"))).isTrue();
        assertThat(RestrictedDataPolicy.grantedScopesIncludeRestricted(null)).isFalse();
        assertThat(RestrictedDataPolicy.SENSITIVITY_KEY).isEqualTo("__dataSensitivity__");
    }

    @Test
    @DisplayName("DataSensitivity parses persisted, boolean and credential forms and only ratchets up")
    void sensitivityParsing() {
        assertThat(DataSensitivity.parse("RESTRICTED")).isEqualTo(DataSensitivity.RESTRICTED);
        assertThat(DataSensitivity.parse("restricted")).isEqualTo(DataSensitivity.RESTRICTED);
        assertThat(DataSensitivity.parse(Boolean.TRUE)).isEqualTo(DataSensitivity.RESTRICTED);
        assertThat(DataSensitivity.parse("nonsense")).isEqualTo(DataSensitivity.NORMAL);
        assertThat(DataSensitivity.parse(null)).isEqualTo(DataSensitivity.NORMAL);
        assertThat(DataSensitivity.fromCredentials(Map.of(DataSensitivity.CREDENTIAL_KEY, "RESTRICTED")))
                .isEqualTo(DataSensitivity.RESTRICTED);
        assertThat(DataSensitivity.fromCredentials(null)).isEqualTo(DataSensitivity.NORMAL);
        assertThat(DataSensitivity.NORMAL.max(DataSensitivity.RESTRICTED)).isEqualTo(DataSensitivity.RESTRICTED);
        assertThat(DataSensitivity.RESTRICTED.max(DataSensitivity.NORMAL)).isEqualTo(DataSensitivity.RESTRICTED);
        assertThat(DataSensitivity.NORMAL.max(DataSensitivity.NORMAL)).isEqualTo(DataSensitivity.NORMAL);
    }
}
