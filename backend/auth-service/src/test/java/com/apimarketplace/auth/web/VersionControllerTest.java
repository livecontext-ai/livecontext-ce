package com.apimarketplace.auth.web;

import com.apimarketplace.auth.web.version.UpdateStatus;
import com.apimarketplace.auth.web.version.VersionUpdateService;
import com.apimarketplace.auth.web.version.VersionUpdateService.VersionUpdateView;
import com.apimarketplace.common.web.AppEditionProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.GitProperties;
import org.springframework.core.env.Environment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Unit tests for {@link VersionController} / {@link VersionInfo}: the data mapping
 * (edition + git build metadata + update status) and the HTTP contract of
 * {@code GET /api/version}.
 *
 * <p>Covers the {@code version} resolution precedence: a stamped release tag
 * ({@code APP_VERSION} / {@code app.version}) wins; otherwise the short git commit
 * sha is surfaced as {@code dev-<sha>} for from-source builds; otherwise it degrades
 * to the Maven build version or {@code "dev"}.
 */
class VersionControllerTest {

    /** Each test starts with no stamped release tag; clear any the test set so it cannot leak. */
    @AfterEach
    void clearReleaseTagOverride() {
        System.clearProperty(VersionInfo.APP_VERSION_PROPERTY);
    }

    /** Builds a real AppEditionProvider off a mocked Environment (no Spring context, no @PostConstruct). */
    private static AppEditionProvider editionFor(String appEdition) {
        Environment env = mock(Environment.class);
        when(env.getProperty("app.edition", "")).thenReturn(appEdition);
        return new AppEditionProvider(env);
    }

    /** git.properties as Spring would expose it (keys without the "git." prefix). */
    private static GitProperties gitProperties(String version, String shortSha, String buildTime) {
        Properties p = new Properties();
        p.setProperty("build.version", version);
        p.setProperty("commit.id.abbrev", shortSha);
        p.setProperty("build.time", buildTime);
        p.setProperty("branch", "main");
        return new GitProperties(p);
    }

    private static VersionUpdateService updateServiceWith(UpdateStatus status) {
        VersionUpdateService svc = new VersionUpdateService();
        if (status != null) {
            svc.update(status);
        }
        return svc;
    }

    @Test
    @DisplayName("cloud build with git info but no release tag: version is the from-source dev-<sha>")
    void cloudWithGitInfoNoReleaseTag() {
        VersionInfo info = VersionInfo.from(
                editionFor("cloud"),
                gitProperties("0.1.0-SNAPSHOT", "abc1234", "2026-06-25T10:30:00Z"),
                VersionUpdateView.none());

        // Maven SNAPSHOT version is never surfaced; the short sha identifies the build.
        assertThat(info.version()).isEqualTo("dev-abc1234");
        assertThat(info.gitSha()).isEqualTo("abc1234");
        assertThat(info.buildTime()).isEqualTo("2026-06-25T10:30:00Z");
        assertThat(info.edition()).isEqualTo("cloud");
        assertThat(info.selfHosted()).isFalse();
        assertThat(info.managedCloud()).isTrue();
        assertThat(info.updateAvailable()).isFalse();
        assertThat(info.latestVersion()).isNull();
    }

    @Test
    @DisplayName("released build: stamped APP_VERSION/app.version release tag wins over the git sha")
    void releaseTagWinsOverGitSha() {
        System.setProperty(VersionInfo.APP_VERSION_PROPERTY, "v1.5.0");

        VersionInfo info = VersionInfo.from(
                editionFor("ce"),
                gitProperties("0.1.0-SNAPSHOT", "abc1234", "2026-06-25T10:30:00Z"),
                VersionUpdateView.none());

        assertThat(info.version()).isEqualTo("v1.5.0");
        // The raw commit sha is still surfaced independently of the displayed version.
        assertThat(info.gitSha()).isEqualTo("abc1234");
    }

    @Test
    @DisplayName("CE build without git info and no release tag: falls back to dev / null, marked self-hosted")
    void ceWithoutGitInfo() {
        VersionInfo info = VersionInfo.from(editionFor("ce"), null, VersionUpdateView.none());

        assertThat(info.version()).isEqualTo("dev");
        assertThat(info.gitSha()).isNull();
        assertThat(info.buildTime()).isNull();
        assertThat(info.edition()).isEqualTo("ce");
        assertThat(info.selfHosted()).isTrue();
        assertThat(info.managedCloud()).isFalse();
    }

    @Test
    @DisplayName("blank build.version and no sha degrade to dev (does not surface an empty version)")
    void blankVersionAndNoShaFallBackToDev() {
        VersionInfo info = VersionInfo.from(
                editionFor("cloud"),
                gitProperties("   ", "", "2026-06-25T10:30:00Z"),
                VersionUpdateView.none());

        assertThat(info.version()).isEqualTo("dev");
    }

    @Test
    @DisplayName("self-hosted enterprise surfaces an available update from the update view")
    void selfHostedEnterpriseWithUpdate() {
        VersionInfo info = VersionInfo.from(
                editionFor("self-hosted-enterprise"),
                gitProperties("1.4.2", "abc1234", "2026-06-25T10:30:00Z"),
                new VersionUpdateView(true, "1.5.0", "https://example.test/releases", true, "2026-06-25T11:00:00Z"));

        assertThat(info.edition()).isEqualTo("self-hosted-enterprise");
        assertThat(info.selfHosted()).isTrue();
        assertThat(info.managedCloud()).isFalse();
        assertThat(info.updateAvailable()).isTrue();
        assertThat(info.latestVersion()).isEqualTo("1.5.0");
        assertThat(info.releaseUrl()).isEqualTo("https://example.test/releases");
        assertThat(info.securityFix()).isTrue();
        assertThat(info.checkedAt()).isEqualTo("2026-06-25T11:00:00Z");
    }

    @Test
    @DisplayName("resolveVersion precedence: release tag, else dev-<sha>, else build.version, else dev")
    void resolveVersionPrecedence() {
        GitProperties withSha = gitProperties("0.1.0-SNAPSHOT", "abc1234", "2026-06-25T10:30:00Z");
        GitProperties withoutSha = gitProperties("2.0.0", "", "2026-06-25T10:30:00Z");

        // (a) explicit release tag wins even when a sha and a build.version are present.
        assertThat(VersionInfo.resolveVersion(withSha, "1.9.0")).isEqualTo("1.9.0");
        assertThat(VersionInfo.resolveVersion(withSha, "  1.9.0  ")).isEqualTo("1.9.0");
        // A blank override is ignored and falls through to the sha.
        assertThat(VersionInfo.resolveVersion(withSha, "   ")).isEqualTo("dev-abc1234");

        // (b) short git sha, prefixed, when no release tag is stamped.
        assertThat(VersionInfo.resolveVersion(withSha, null)).isEqualTo("dev-abc1234");

        // (c) Maven build version when there is no sha, then "dev" when nothing is known.
        assertThat(VersionInfo.resolveVersion(withoutSha, null)).isEqualTo("2.0.0");
        assertThat(VersionInfo.resolveVersion(null, null)).isEqualTo("dev");
    }

    @Test
    @DisplayName("resolveVersion(GitProperties) reads the app.version system property as the release tag")
    void resolveVersionReadsSystemProperty() {
        System.setProperty(VersionInfo.APP_VERSION_PROPERTY, "v3.2.1");

        assertThat(VersionInfo.resolveVersion(gitProperties("0.1.0-SNAPSHOT", "abc1234", "2026-06-25T10:30:00Z")))
                .isEqualTo("v3.2.1");
    }

    @Test
    @DisplayName("GET /api/version on a released CE install (APP_VERSION set) surfaces an available update")
    void httpContractCeUpdateAvailable() throws Exception {
        // A released CE install carries a parseable release tag, so the update check can compare it.
        System.setProperty(VersionInfo.APP_VERSION_PROPERTY, "0.1.0");

        @SuppressWarnings("unchecked")
        ObjectProvider<GitProperties> gitProvider = mock(ObjectProvider.class);
        when(gitProvider.getIfAvailable())
                .thenReturn(gitProperties("0.1.0-SNAPSHOT", "abc1234", "2026-06-25T10:30:00Z"));

        VersionUpdateService svc = updateServiceWith(
                new UpdateStatus("0.2.0", "https://example.test/notes", false, null, Instant.parse("2026-06-25T11:00:00Z")));

        VersionController controller = new VersionController(editionFor("ce"), svc, gitProvider);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(get("/api/version"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("0.1.0"))
                .andExpect(jsonPath("$.edition").value("ce"))
                .andExpect(jsonPath("$.selfHosted").value(true))
                .andExpect(jsonPath("$.updateAvailable").value(true))
                .andExpect(jsonPath("$.latestVersion").value("0.2.0"))
                .andExpect(jsonPath("$.releaseUrl").value("https://example.test/notes"))
                .andExpect(jsonPath("$.securityFix").value(false));
    }

    @Test
    @DisplayName("GET /api/version on a from-source CE build (dev-<sha>) is never flagged as behind a release")
    void httpContractCeFromSourceNotFlaggedAsBehind() throws Exception {
        // No APP_VERSION: the running version is the unparseable "dev-<sha>", so the comparator
        // cannot (and must not) claim the source build is behind a published release.
        @SuppressWarnings("unchecked")
        ObjectProvider<GitProperties> gitProvider = mock(ObjectProvider.class);
        when(gitProvider.getIfAvailable())
                .thenReturn(gitProperties("0.1.0-SNAPSHOT", "abc1234", "2026-06-25T10:30:00Z"));

        VersionUpdateService svc = updateServiceWith(
                new UpdateStatus("9.9.9", "https://example.test/notes", true, null, Instant.now()));

        VersionController controller = new VersionController(editionFor("ce"), svc, gitProvider);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(get("/api/version"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("dev-abc1234"))
                .andExpect(jsonPath("$.gitSha").value("abc1234"))
                .andExpect(jsonPath("$.updateAvailable").value(false));
    }

    @Test
    @DisplayName("GET /api/version in cloud reports no update even when a feed status is present")
    void httpContractCloudNoUpdate() throws Exception {
        @SuppressWarnings("unchecked")
        ObjectProvider<GitProperties> gitProvider = mock(ObjectProvider.class);
        when(gitProvider.getIfAvailable())
                .thenReturn(gitProperties("0.1.0", "abc1234", "2026-06-25T10:30:00Z"));

        // Even with a populated feed, managed cloud must never advertise an update.
        VersionUpdateService svc = updateServiceWith(
                new UpdateStatus("9.9.9", "https://example.test/notes", true, null, Instant.now()));

        VersionController controller = new VersionController(editionFor("cloud"), svc, gitProvider);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(get("/api/version"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.edition").value("cloud"))
                .andExpect(jsonPath("$.managedCloud").value(true))
                .andExpect(jsonPath("$.updateAvailable").value(false))
                .andExpect(jsonPath("$.latestVersion").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    @DisplayName("controller tolerates an absent GitProperties bean (dev fallback)")
    void httpContractWithoutGit() throws Exception {
        @SuppressWarnings("unchecked")
        ObjectProvider<GitProperties> gitProvider = mock(ObjectProvider.class);
        when(gitProvider.getIfAvailable()).thenReturn(null);

        VersionController controller = new VersionController(editionFor("ce"), updateServiceWith(null), gitProvider);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(get("/api/version"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("dev"))
                .andExpect(jsonPath("$.gitSha").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.edition").value("ce"))
                .andExpect(jsonPath("$.selfHosted").value(true))
                .andExpect(jsonPath("$.updateAvailable").value(false));
    }
}
