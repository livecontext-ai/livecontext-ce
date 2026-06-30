package com.apimarketplace.auth.web;

import com.apimarketplace.auth.web.version.VersionUpdateService;
import com.apimarketplace.common.web.AppEditionProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.GitProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes the running build, edition and update status at {@code GET /api/version}.
 *
 * <p>One controller serves both editions: in cloud the gateway routes
 * {@code /api/version} here; in CE the monolith component-scans this class and
 * serves it directly (no gateway). It reads the auto-configured
 * {@link GitProperties} bean (present only when {@code git.properties} was
 * stamped into the runnable artifact), the shared {@link AppEditionProvider}, and
 * the {@link VersionUpdateService} (populated by the CE version-check poller; empty
 * in cloud, where updates are managed).
 *
 * <p>In cloud the gateway enforces auth on {@code /api/version} (it is not in the
 * gateway public-endpoint allowlist). In CE the monolith's soft-auth model lets the
 * controller answer without a JWT; that is acceptable because the payload
 * (version, edition, short sha, build time, update availability) is low-sensitivity
 * build metadata, not user data. It backs the in-app Settings &gt; Information view,
 * not the public marketing pages.
 */
@RestController
public class VersionController {

    private final AppEditionProvider editionProvider;
    private final VersionUpdateService versionUpdateService;
    private final GitProperties gitProperties;

    public VersionController(AppEditionProvider editionProvider,
                             VersionUpdateService versionUpdateService,
                             ObjectProvider<GitProperties> gitProperties) {
        this.editionProvider = editionProvider;
        this.versionUpdateService = versionUpdateService;
        // Absent when the build carried no git.properties (graceful "dev" fallback).
        this.gitProperties = gitProperties.getIfAvailable();
    }

    @GetMapping("/api/version")
    public VersionInfo getVersion() {
        String runningVersion = VersionInfo.resolveVersion(gitProperties);
        var update = versionUpdateService.resolve(runningVersion, editionProvider.isSelfHosted());
        return VersionInfo.from(editionProvider, gitProperties, update);
    }
}
