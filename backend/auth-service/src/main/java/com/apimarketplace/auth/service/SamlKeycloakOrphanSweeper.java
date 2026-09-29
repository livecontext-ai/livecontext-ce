package com.apimarketplace.auth.service;

import com.apimarketplace.auth.repository.OrganizationSamlConnectionRepository;
import com.apimarketplace.auth.repository.UserRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Backstop for what a workspace SAML IdP can leave behind in Keycloak (Cloud only: CE has no
 * Keycloak).
 *
 * <p><b>Users.</b> Keycloak's first broker login creates the user BEFORE the app ever sees a
 * token. A login the app refuses is released on the spot (UserResolutionService), but a login
 * that never reaches the app (the IdP asserts an address and the browser never completes the
 * code exchange), a Keycloak outage during the release, or a crash in between leaves a Keycloak
 * user holding an email address nobody proved. This sweep deletes a Keycloak user only when ALL
 * of these hold: its ONLY federated identity is a workspace SAML IdP ({@code org-<uuid>-saml}),
 * it holds no credential of its own, no app account points at it ({@code auth.users.provider_id}),
 * and it is older than {@code auth.saml.orphan-sweep.min-age} (default 30 minutes, so a login in
 * progress is never raced). The link and the credentials are re-checked by the client right
 * before the delete. Anything else (a password, a Google link, an app account) is never touched.
 *
 * <p><b>IdPs.</b> A workspace IdP whose connection row is gone (a purge whose after-commit
 * Keycloak delete failed, a Keycloak restore) is deleted once it has been seen orphaned on two
 * consecutive runs of this instance: saving a new connection creates the IdP in Keycloak before
 * its row commits, and a single sighting could be that window.
 */
@Component
@ConditionalOnProperty(name = "auth.mode", havingValue = "keycloak", matchIfMissing = false)
public class SamlKeycloakOrphanSweeper {

    private static final Logger log = LoggerFactory.getLogger(SamlKeycloakOrphanSweeper.class);

    static final int PAGE_SIZE = 100;
    /** Per IdP per run; the remainder is picked up by the next run. */
    static final int MAX_PAGES_PER_ALIAS = 20;

    private final KeycloakSamlIdentityProviderClient keycloakClient;
    private final UserRepository userRepository;
    private final OrganizationSamlConnectionRepository samlRepository;
    private final Duration minAge;
    private final boolean enabled;
    private Clock clock = Clock.systemUTC();

    /** Orphan IdP aliases seen on the previous run; deleted when still orphaned on this one. */
    private Set<String> orphanIdpsSeenLastRun = Set.of();

    public SamlKeycloakOrphanSweeper(KeycloakSamlIdentityProviderClient keycloakClient,
                                     UserRepository userRepository,
                                     OrganizationSamlConnectionRepository samlRepository,
                                     @Value("${auth.saml.orphan-sweep.min-age:PT30M}") Duration minAge,
                                     @Value("${auth.saml.orphan-sweep.enabled:true}") boolean enabled) {
        this.keycloakClient = keycloakClient;
        this.userRepository = userRepository;
        this.samlRepository = samlRepository;
        this.minAge = minAge;
        this.enabled = enabled;
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    /** What one run did, for the log line and the tests. */
    record Result(int idpsScanned, int usersDeleted, int usersKept, int idpsDeleted, int errors) {
    }

    @Scheduled(initialDelayString = "${auth.saml.orphan-sweep.initial-delay:PT5M}",
               fixedDelayString = "${auth.saml.orphan-sweep.interval:PT15M}")
    @SchedulerLock(name = "saml_keycloak_orphan_sweep", lockAtMostFor = "PT10M")
    public void scheduledSweep() {
        if (!enabled) {
            return;
        }
        try {
            Result r = sweep();
            if (r.usersDeleted() > 0 || r.idpsDeleted() > 0 || r.errors() > 0) {
                log.info("[saml-sweep] {} workspace IdP(s) scanned: {} orphan Keycloak user(s) deleted, "
                                + "{} kept, {} orphan IdP(s) deleted, {} error(s)",
                        r.idpsScanned(), r.usersDeleted(), r.usersKept(), r.idpsDeleted(), r.errors());
            }
        } catch (RuntimeException e) {
            // The scheduler thread must survive; the next run retries.
            log.warn("[saml-sweep] run failed: {}", e.toString());
        }
    }

    Result sweep() {
        List<String> aliases = keycloakClient.listOrganizationSamlAliases();
        long cutoff = clock.millis() - minAge.toMillis();
        int deleted = 0, kept = 0, errors = 0;
        for (String alias : aliases) {
            // Collect first, delete after: deleting while paging would shift the offsets and
            // skip users.
            List<KeycloakSamlIdentityProviderClient.BrokeredUser> candidates = new ArrayList<>();
            for (int page = 0; page < MAX_PAGES_PER_ALIAS; page++) {
                List<KeycloakSamlIdentityProviderClient.BrokeredUser> batch =
                        keycloakClient.listUsersLinkedTo(alias, page * PAGE_SIZE, PAGE_SIZE);
                candidates.addAll(batch);
                if (batch.size() < PAGE_SIZE) {
                    break;
                }
            }
            for (KeycloakSamlIdentityProviderClient.BrokeredUser user : candidates) {
                if (user.createdTimestamp() == null || user.createdTimestamp() > cutoff
                        || userRepository.findByProviderId(user.id()).isPresent()) {
                    kept++;
                    continue;
                }
                try {
                    if (keycloakClient.deleteIfOnlyBrokeredBy(user.id(), alias)) {
                        deleted++;
                        log.info("[saml-sweep] deleted orphan Keycloak user {} (only linked to {})", user.id(), alias);
                    } else {
                        kept++;
                    }
                } catch (RuntimeException e) {
                    errors++;
                    log.warn("[saml-sweep] could not sweep Keycloak user {} ({}): {}", user.id(), alias, e.toString());
                }
            }
        }

        Set<String> orphanIdps = new HashSet<>();
        int idpsDeleted = 0;
        for (String alias : aliases) {
            if (samlRepository.findByIdpAlias(alias).isPresent()) {
                continue;
            }
            if (!orphanIdpsSeenLastRun.contains(alias)) {
                orphanIdps.add(alias);
                continue;
            }
            try {
                keycloakClient.delete(alias);
                idpsDeleted++;
                log.info("[saml-sweep] deleted workspace IdP {} (no connection row)", alias);
            } catch (RuntimeException e) {
                errors++;
                orphanIdps.add(alias);
                log.warn("[saml-sweep] could not delete orphan IdP {}: {}", alias, e.toString());
            }
        }
        orphanIdpsSeenLastRun = orphanIdps;
        return new Result(aliases.size(), deleted, kept, idpsDeleted, errors);
    }
}
