package com.apimarketplace.auth.web;

import com.apimarketplace.auth.web.version.VersionUpdateService.VersionUpdateView;
import com.apimarketplace.common.web.AppEditionProvider;
import org.springframework.boot.info.GitProperties;

import java.time.Instant;

/**
 * Immutable view returned by {@code GET /api/version}: what build is running,
 * which edition it is, and (for self-hosted editions) whether a newer release is
 * available. Consumed by the frontend Settings &gt; Information "Version" card.
 *
 * <p>The displayed {@code version} is resolved with precedence (see
 * {@link #resolveVersion(GitProperties)}): the release tag stamped at release time
 * ({@code APP_VERSION}) for released images, else the short git commit sha as
 * {@code dev-<sha>} for from-source builds (e.g. the local CE), else the Maven
 * build version or {@code "dev"}. It never surfaces the raw Maven SNAPSHOT version
 * when a more meaningful identity is available.
 *
 * <p>All build fields degrade gracefully: a build produced without git info (no
 * reachable {@code .git} at build time, so no {@code git.properties}) and without a
 * stamped {@code APP_VERSION} reports {@code version="dev"} and {@code gitSha=null}
 * rather than failing. The update fields are empty for managed-cloud editions and
 * until the version-check feed has been fetched.
 *
 * @param version        resolved running version: the release tag when stamped, else
 *                       the short commit sha as {@code dev-<sha>}, else the Maven build
 *                       version or {@code "dev"} when nothing is known.
 * @param gitSha         short commit sha of the build, or {@code null} when unknown.
 * @param buildTime      ISO-8601 UTC build timestamp string, or {@code null} when unknown.
 * @param edition        canonical edition token ({@code ce}, {@code self-hosted-enterprise},
 *                       {@code cloud}, {@code dedicated-cloud}).
 * @param selfHosted     true for editions the user runs themselves (CE / self-hosted
 *                       enterprise): the ones that can fall behind and need a manual
 *                       update. Note the update fields below are populated by the
 *                       Community-Edition poller only; self-hosted enterprise reports
 *                       {@code selfHosted=true} but leaves the update fields empty.
 * @param managedCloud   true for cloud / dedicated-cloud (updates are managed).
 * @param updateAvailable true when a newer release exists for this self-hosted install.
 * @param latestVersion  latest-known published version (shown even when up to date), or {@code null}.
 * @param releaseUrl     release-notes link for the latest version, or {@code null}.
 * @param securityFix    whether the available update carries a security fix (false when no update).
 * @param checkedAt      ISO-8601 timestamp of the last successful update-feed check, or {@code null}.
 */
public record VersionInfo(
        String version,
        String gitSha,
        String buildTime,
        String edition,
        boolean selfHosted,
        boolean managedCloud,
        boolean updateAvailable,
        String latestVersion,
        String releaseUrl,
        boolean securityFix,
        String checkedAt) {

    private static final String UNKNOWN_VERSION = "dev";

    /**
     * Prefix marking a from-source build's version (the short commit sha), e.g.
     * {@code dev-abc1234}. Deliberately not a parseable semver so the self-hosted
     * update check ({@link com.apimarketplace.auth.web.version.VersionComparator})
     * never flags a source build as behind a published release.
     */
    private static final String SOURCE_BUILD_PREFIX = "dev-";

    /**
     * Environment variable that stamps the published release tag into a released image
     * at release time (set by the release / Docker build via the {@code APP_VERSION}
     * build arg). Highest-precedence version source.
     */
    static final String APP_VERSION_ENV = "APP_VERSION";

    /** System-property equivalent of {@link #APP_VERSION_ENV} (local / test override). */
    static final String APP_VERSION_PROPERTY = "app.version";

    /**
     * Builds the view from the edition provider, the (possibly absent) git build
     * metadata, and the resolved update view. {@code git} is {@code null} when no
     * {@code git.properties} is on the classpath.
     */
    public static VersionInfo from(AppEditionProvider editionProvider,
                                   GitProperties git,
                                   VersionUpdateView update) {
        String gitSha = git != null ? git.getShortCommitId() : null;
        // GitProperties normalises build.time to epoch millis internally; getInstant
        // gives it back as an Instant, whose toString() is ISO-8601 UTC.
        Instant buildInstant = git != null ? git.getInstant("build.time") : null;
        String buildTime = buildInstant != null ? buildInstant.toString() : null;

        return new VersionInfo(
                resolveVersion(git),
                gitSha,
                buildTime,
                editionProvider.get().configValue(),
                editionProvider.isSelfHosted(),
                editionProvider.isManagedCloud(),
                update.updateAvailable(),
                update.latestVersion(),
                update.releaseUrl(),
                update.securityFix(),
                update.checkedAt());
    }

    /**
     * The running build version with this precedence (each step null/blank-safe):
     * <ol>
     *   <li>the release tag stamped at release time - the {@code APP_VERSION} env var
     *       or the {@code app.version} system property - when set and non-blank;</li>
     *   <li>else the short git commit sha of the build ({@code git.commit.id.abbrev}),
     *       prefixed {@code dev-} to mark a from-source build (e.g. {@code dev-abc1234});</li>
     *   <li>else the Maven build version ({@code git.build.version}), or {@code "dev"}
     *       as a last resort when nothing is known.</li>
     * </ol>
     * Shared by the controller and the version-check poller so both compare the same
     * string. A from-source {@code dev-<sha>} value is intentionally not a parseable
     * semver, so the update check never wrongly flags a source build as behind a
     * published release.
     */
    public static String resolveVersion(GitProperties git) {
        return resolveVersion(git, appVersionOverride());
    }

    /**
     * Precedence resolution with the release-tag override passed in, so the precedence
     * is unit-testable without mutating the process environment. {@code appVersion} is
     * the already-resolved {@code APP_VERSION} / {@code app.version} value
     * ({@code null} or blank = absent).
     */
    static String resolveVersion(GitProperties git, String appVersion) {
        if (appVersion != null && !appVersion.isBlank()) {
            return appVersion.trim();
        }
        String shortSha = git != null ? git.getShortCommitId() : null;
        if (shortSha != null && !shortSha.isBlank()) {
            return SOURCE_BUILD_PREFIX + shortSha.trim();
        }
        String buildVersion = git != null ? git.get("build.version") : null;
        if (buildVersion != null && !buildVersion.isBlank()) {
            return buildVersion.trim();
        }
        return UNKNOWN_VERSION;
    }

    /**
     * The stamped release tag: the {@code APP_VERSION} env var wins, the
     * {@code app.version} system property is the fallback (local / test override).
     * {@code null} when neither is set.
     */
    private static String appVersionOverride() {
        String fromEnv = System.getenv(APP_VERSION_ENV);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        return System.getProperty(APP_VERSION_PROPERTY);
    }
}
