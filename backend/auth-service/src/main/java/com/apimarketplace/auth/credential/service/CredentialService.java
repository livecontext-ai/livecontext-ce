package com.apimarketplace.auth.credential.service;

import com.apimarketplace.auth.credential.domain.CredentialModels.*;
import com.apimarketplace.auth.credential.domain.CredentialRenameRefusedException;
import com.apimarketplace.auth.credential.repository.CredentialRepository;
import com.apimarketplace.common.icon.IconSlugNormalizer;
import com.apimarketplace.common.web.TenantResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Service for managing user credentials (API keys, OAuth2 tokens, etc.).
 */
@Service
public class CredentialService {

    private static final Logger log = LoggerFactory.getLogger(CredentialService.class);

    /**
     * Max length of a credential's display name - mirrors the
     * {@code auth.credentials.name VARCHAR(255)} column, so an over-long rename
     * is rejected with a 400 instead of a database error.
     */
    public static final int MAX_NAME_LENGTH = 255;

    /**
     * Trim and validate a credential's display name, for CREATE and for RENAME alike.
     *
     * <p>The three rules exist for the same reasons on both paths: the column is
     * {@code VARCHAR(255)} so an over-long name is a database error rather than a
     * feature, a blank name leaves an unidentifiable row in every picker, and control
     * characters survive {@code trim()} and would reach agent-facing listings
     * ({@code get_connected_services} returns the raw name) and every UI label.
     *
     * @return the trimmed name
     * @throws IllegalArgumentException when the name is blank, too long, or carries
     *                                  control characters
     */
    public static String validateName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Credential name cannot be empty");
        }
        if (trimmed.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "Credential name cannot exceed " + MAX_NAME_LENGTH + " characters");
        }
        if (trimmed.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Credential name cannot contain control characters");
        }
        return trimmed;
    }

    private final CredentialRepository credentialRepository;
    private final StringRedisTemplate redisTemplate;

    public CredentialService(CredentialRepository credentialRepository,
                             StringRedisTemplate redisTemplate) {
        this.credentialRepository = credentialRepository;
        this.redisTemplate = redisTemplate;
    }

    /**
     * PR19 - create a new credential with explicit org scope. The controller
     * passes {@code organizationId} from the {@code X-Organization-ID} header.
     * The first credential per integration in a given org scope becomes the
     * default - defaults are NOT shared across scopes (strict isolation).
     *
     * <p>Post-V261: {@code organizationId} is REQUIRED. Personal workspace
     * resolves to the user's {@code is_default=true} personal org UUID via
     * gateway. The previous null-org / personal-scope branch is gone.
     */
    public Credential createCredential(
            String tenantId,
            String organizationId,
            String name,
            String integration,
            CredentialType type,
            CredentialEnvironment environment,
            String description,
            Map<String, Object> credentialData,
            List<String> scopes,
            List<String> tags,
            String owner,
            String iconUrl
    ) {
        if (tenantId == null || tenantId.trim().isEmpty()) {
            throw new IllegalArgumentException("tenantId cannot be null or empty");
        }
        TenantResolver.requireOrgId(organizationId);
        // Same rules as a rename: a name too long for the column, or carrying control
        // characters, is no more acceptable at creation time than later.
        String validatedName = validateName(name);
        if (type == null) {
            throw new IllegalArgumentException("type cannot be null");
        }
        if (environment == null) {
            throw new IllegalArgumentException("environment cannot be null");
        }

        // First credential for this integration in the active org SCOPE becomes
        // the default. Scope-aware: org-scope defaults stay org-scope.
        boolean isFirstForIntegration = integration != null &&
            credentialRepository.findByScopeAndIntegration(tenantId, organizationId, integration).isEmpty();

        Credential credential = new Credential(
            null,
            tenantId,
            organizationId,
            validatedName,
            integration,
            type,
            environment,
            CredentialStatus.active,
            description,
            credentialData != null ? credentialData : Map.of(),
            scopes != null ? scopes : List.of(),
            tags != null ? tags : List.of(),
            owner,
            iconUrl,
            isFirstForIntegration, // isDefault - true if first for this integration in this scope
            null,
            Instant.now(),
            Instant.now()
        );

        return credentialRepository.save(credential);
    }

    /**
     * Get a credential by ID.
     */
    public Optional<Credential> getCredential(Long id) {
        return credentialRepository.findById(id);
    }

    /**
     * PR19 - Get credentials for the active workspace with pagination. Strict
     * isolation: returns ONLY rows tagged with {@code organizationId}.
     *
     * <p>Post-V261: {@code organizationId} is REQUIRED. Personal workspace
     * resolves to the user's {@code is_default=true} personal org UUID; the
     * previous {@code organization_id IS NULL} branch is removed.
     */
    public PaginatedCredentialsResponse getCredentialsForScope(
            String tenantId,
            String organizationId,
            int page,
            int pageSize,
            CredentialStatus statusFilter
    ) {
        if (tenantId == null || tenantId.trim().isEmpty()) {
            throw new IllegalArgumentException("tenantId cannot be null or empty");
        }
        TenantResolver.requireOrgId(organizationId);
        if (page < 1) page = 1;
        if (pageSize < 1) pageSize = 10;
        if (pageSize > 100) pageSize = 100;

        List<Credential> credentials;
        int totalItems;
        if (statusFilter != null) {
            credentials = credentialRepository.findByOrganizationIdAndStatusStrict(
                    organizationId, statusFilter, page, pageSize);
            totalItems = credentialRepository.countByOrganizationIdAndStatusStrict(
                    organizationId, statusFilter);
        } else {
            credentials = credentialRepository.findByOrganizationIdStrict(organizationId, page, pageSize);
            totalItems = credentialRepository.countByOrganizationIdStrict(organizationId);
        }

        int totalPages = (int) Math.ceil((double) totalItems / pageSize);
        boolean hasNext = page < totalPages;
        boolean hasPrevious = page > 1;
        return new PaginatedCredentialsResponse(
                credentials, page, pageSize, totalItems, totalPages, hasNext, hasPrevious);
    }

    /**
     * Scope-aware single fetch - returns the credential only if it belongs to
     * the active workspace. Returns empty if the row exists but is in a
     * different scope (strict isolation).
     *
     * <p>Post-V261: {@code organizationId} is REQUIRED.
     */
    public Optional<Credential> getCredentialForScope(Long id, String tenantId, String organizationId) {
        TenantResolver.requireOrgId(organizationId);
        return credentialRepository.findById(id)
                .filter(cred -> matchesScope(cred, tenantId, organizationId));
    }

    /**
     * Strict-isolation delete. Returns false if the credential doesn't exist or
     * is in a different scope than the caller's active workspace.
     *
     * <p>Post-V261: {@code organizationId} is REQUIRED.
     */
    public boolean deleteCredentialForScope(Long id, String tenantId, String organizationId) {
        TenantResolver.requireOrgId(organizationId);
        Optional<Credential> opt = credentialRepository.findById(id)
                .filter(cred -> matchesScope(cred, tenantId, organizationId));
        if (opt.isEmpty()) {
            return false;
        }
        Credential credential = opt.get();
        boolean wasDefault = credential.isDefault();

        credentialRepository.deleteById(id);

        reassignDefaultAfterDelete(credential, wasDefault, organizationId);

        return true;
    }

    private void reassignDefaultAfterDelete(Credential deletedCredential, boolean wasDefault, String organizationId) {
        String integration = deletedCredential.integration();
        if (integration == null) {
            return;
        }

        Set<Long> vanishedFallbackIds = new HashSet<>();
        while (true) {
            List<Credential> remaining = credentialRepository.findByScopeAndIntegration(
                            deletedCredential.tenantId(), organizationId, integration)
                    .stream()
                    .filter(credential -> !vanishedFallbackIds.contains(credential.id()))
                    .toList();
            if (remaining.isEmpty()) {
                return;
            }

            boolean hasDefault = remaining.stream().anyMatch(Credential::isDefault);
            if (!wasDefault && hasDefault) {
                return;
            }

            Credential fallback = mostRecentCredential(remaining);
            try {
                credentialRepository.setAsDefaultInScope(
                        fallback.tenantId(), organizationId, fallback.id());
                return;
            } catch (RuntimeException ex) {
                // A concurrent request (e.g. a parallel bulk-delete of the same
                // scope) can delete the chosen fallback between our scope query and
                // the promote. setAsDefaultInScope raises that as an
                // IllegalArgumentException, but the @Repository proxy translates it
                // into a Spring DataAccessException (InvalidDataAccessApiUsageException),
                // so we CANNOT key off the exception type. Re-probe existence instead:
                // if the row is genuinely gone, skip it and pick the next best
                // candidate; if it still exists, the failure is unrelated (a real
                // error) and must propagate.
                if (credentialRepository.findById(fallback.id()).isPresent()) {
                    throw ex;
                }
                vanishedFallbackIds.add(fallback.id());
                log.debug(
                        "Skipping vanished credential {} while reassigning default after delete",
                        fallback.id());
            }
        }
    }

    private static Credential mostRecentCredential(List<Credential> credentials) {
        return credentials.stream()
                .max(Comparator.comparing(
                        Credential::createdAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElseThrow();
    }

    /**
     * Post-V261: scope match is purely org-equality. Caller guarantees a
     * non-null/non-blank {@code organizationId} (sweep enforced at every
     * scope-aware entry point).
     */
    private static boolean matchesScope(Credential cred, String tenantId, String organizationId) {
        return organizationId.equals(cred.organizationId());
    }

    /**
     * Get credentials for a tenant with pagination. Legacy entry point - kept
     * for back-compat callers that haven't migrated to scope-aware fetch.
     */
    public PaginatedCredentialsResponse getCredentialsByTenant(
            String tenantId,
            int page,
            int pageSize,
            CredentialStatus statusFilter
    ) {
        if (tenantId == null || tenantId.trim().isEmpty()) {
            throw new IllegalArgumentException("tenantId cannot be null or empty");
        }
        if (page < 1) {
            page = 1;
        }
        if (pageSize < 1) {
            pageSize = 10;
        }
        if (pageSize > 100) {
            pageSize = 100;
        }

        List<Credential> credentials;
        int totalItems;

        if (statusFilter != null) {
            credentials = credentialRepository.findByTenantIdAndStatus(tenantId, statusFilter, page, pageSize);
            totalItems = credentialRepository.countByTenantIdAndStatus(tenantId, statusFilter);
        } else {
            credentials = credentialRepository.findByTenantId(tenantId, page, pageSize);
            totalItems = credentialRepository.countByTenantId(tenantId);
        }

        int totalPages = (int) Math.ceil((double) totalItems / pageSize);
        boolean hasNext = page < totalPages;
        boolean hasPrevious = page > 1;

        return new PaginatedCredentialsResponse(
            credentials,
            page,
            pageSize,
            totalItems,
            totalPages,
            hasNext,
            hasPrevious
        );
    }

    /**
     * Resolve a credential by the name an API's credential requirement carries
     * ({@code gmail}, {@code elevenlabs}, {@code smtp}...), returning it ONLY when
     * that name really identifies this credential's provider.
     *
     * <p><b>The trap this closes.</b> A credential's {@code name} is free text the
     * user types. A requirement name is a provider slug. Matching one against the
     * other with no further check means any credential a user happens to call
     * {@code elevenlabs} answers every ElevenLabs call, whatever provider it is
     * actually for, and one provider's secret goes out to another provider's
     * endpoint. The name is accepted as an identity in exactly one case: a
     * credential carrying NO {@code integration}, which is how the workflow-native
     * connectors (smtp, ssh, database) identify themselves and the reason this
     * name-first branch exists at all. Otherwise {@code integration} decides, and it
     * is compared on the canonical slug ({@link IconSlugNormalizer#normalizeForKey}),
     * the same normalisation catalog-service applies when it validates a pinned
     * credential.
     *
     * <p>Rejecting here is safe: every caller falls back to resolving by
     * {@code integration}, which is the correct row.
     *
     * <p><b>Two behaviour changes for an install that already holds duplicate names</b>, both
     * from preferring an identifying row over whichever row sorts first, and neither reachable
     * without two rows sharing one exact name:
     * <ul>
     *   <li>A scope preflight that used to be skipped can start running. {@code GET /scopes}
     *       404'd whenever the first-sorting row did not identify, and its caller fails open,
     *       so the check silently did not happen. It now resolves, and a call that ran
     *       unchecked can be refused for missing scopes. That is the point of the endpoint,
     *       but it is a behaviour change, not a pure bug fix.</li>
     *   <li>Which key answers can flip, and NOT always within one provider. With rows
     *       {@code A(slack, default, named "xai")}, {@code B(xai, named "xai")} and
     *       {@code C(xai, default)}, the name branch used to return nothing and the
     *       integration fallback picked {@code C}; it now returns {@code B}: same provider,
     *       different key, its own quota and granted scopes. But a row with a BLANK
     *       integration identifies EVERY name, so it can be the newly preferred one: with
     *       {@code A(slack, default, named "elevenlabs")}, {@code B(no integration, named
     *       "elevenlabs")} and {@code C(elevenlabs)}, the walk used to stop at {@code A},
     *       reject it and fall back to {@code C}; it now returns {@code B}, whose secret is
     *       not an ElevenLabs key. That is the declared contract of a blank integration (the
     *       name IS the identity, which is how smtp/ssh/database connectors resolve), and
     *       {@code A} shielding {@code B} was an accident of sort order rather than a
     *       control. It is still the one shape of this change that can send an unrelated
     *       secret to a provider, so it is named here rather than left to be discovered.</li>
     * </ul>
     */
    public Optional<Credential> findByNameIdentifyingIntegration(String tenantId, String requirementName) {
        List<Credential> byName = credentialRepository.findAllByTenantIdAndName(tenantId, requirementName);
        if (byName.isEmpty()) {
            return Optional.empty();
        }
        // Every row carrying this name is considered, not just the one that sorts first.
        // Nothing stops two credentials of one owner sharing a name (the create path applies
        // no uniqueness rule at all), and when they do, the row that sorts first can easily be
        // a credential merely LABELLED with the slug. Taking that row and then rejecting it
        // would shadow the real provider's credential and report "no match" for a name that
        // resolves perfectly well. The token path survives that on its integration fallback;
        // the /scopes preflight has none and its caller fails open, i.e. the scope check
        // silently stops running. So: prefer a row the name really identifies, and fall back
        // to the sort order only among rows that are equally valid answers.
        Optional<Credential> identifying = byName.stream()
                .filter(candidate -> nameIdentifies(candidate, requirementName))
                .findFirst();
        if (identifying.isEmpty()) {
            // Only when NO row answers. Before the walk, a mislabelled row sorting first was
            // reported even when the right credential sat behind it; that line is gone for the
            // case that now resolves correctly, which is the point, but it does mean a
            // mislabelled credential is no longer announced while a good namesake exists.
            //
            // WARNed, never silent: these rows DID answer this name before the check existed,
            // so a workflow can stop finding "its" credential here. The caller then resolves
            // by integration, but if that misses too the only other symptom is a missing
            // token, which is undiagnosable without this line. Mirrors the WARN catalog-service
            // logs when it drops a pinned credential for the same reason.
            Credential first = byName.get(0);
            log.warn("Credential {} is named '{}' but belongs to integration '{}', so it does not "
                            + "answer for '{}' ({} row(s) carry that name). Resolving by "
                            + "integration instead.",
                    first.id(), requirementName, first.integration(), requirementName, byName.size());
        }
        return identifying;
    }

    /**
     * True when this credential has nothing but its name to identify it: catalog-service
     * validates a pinned credential of this shape by name
     * ({@code HttpExecutionService.resolvePinnedCredentialOwnership}), the builder's
     * picker mirrors it ({@code frontend/lib/credentials/credentialMatching.ts}), and
     * {@link #findByNameIdentifyingIntegration} accepts its name as an identity.
     */
    private static boolean isNameTheIdentity(Credential credential) {
        return credential.integration() == null || credential.integration().isBlank();
    }

    /**
     * True when {@code requirementName} may stand in as this credential's identity: the
     * credential declares no integration, or its integration IS that slug.
     *
     * <p>The {@code -credential} suffix is stripped before comparing, because the two
     * mirrors of this rule strip it too: {@code InternalCredentialService} derives its
     * integration fallback as {@code credentialName.replaceAll("-credential$", "")}, and
     * catalog's {@code HttpExecutionService.resolvePinnedCredentialOwnership} compares a
     * pinned credential's integration against BOTH the raw requirement and the stripped
     * one. Without the strip, a credential named {@code smtp-credential} declaring
     * {@code integration = "smtp"} would be rejected here. On the token path that costs
     * only a WARN (the integration fallback recovers the row), but the {@code /scopes}
     * preflight has NO fallback: it would 404, and the caller fails open, so the scope
     * check the endpoint exists for would silently stop running for that credential.
     */
    private static boolean nameIdentifies(Credential credential, String requirementName) {
        return nameIdentifies(credential.integration(), requirementName);
    }

    /** What a {@code -credential} requirement suffix becomes once canonicalised. */
    private static final String CREDENTIAL_REQUIREMENT_SUFFIX_KEY = "credential";

    /**
     * True when two canonical identifiers would put their credentials in front of ONE
     * requirement, as catalog's run-time selector decides it
     * ({@code HttpExecutionService.credentialIdentityMatchesRequirement}).
     *
     * <p>That reader admits a credential three ways, and each of them compares CANONICAL slugs
     * ({@code sameCredentialIdentity} runs both sides through {@code normalizeForKey}), against
     * either the requirement or the integration derived from it by dropping a {@code -credential}
     * suffix. Canonicalising deletes punctuation and case, so {@code "smtp-credential"} and
     * {@code "SMTP Credential"} are one key, and the difference between the two spellings of a
     * requirement survives only as a trailing {@code "credential"} on the key. Hence the two
     * asymmetric comparisons: without them a guard reasoning on {@code integration} alone reads
     * two rows the selector offers side by side as unrelated.
     *
     * <p><b>Known corner, left open deliberately.</b> {@code normalize} strips a trailing
     * {@code -api} BEFORE deleting punctuation, so for a requirement spelt
     * {@code "shop-api-credential"} the two keys are {@code "shopapicredential"} and
     * {@code "shop"}, and the suffix relation above does not hold between them. No requirement in
     * the catalog seeds is spelt that way (checked), and inventing a second normalisation to
     * cover a shape nothing produces would make this harder to reason about than the risk earns.
     *
     * <p>Deliberately NOT the same test as {@link #nameIdentifies(String, String)}, which mirrors
     * auth's resolver: that one strips a LITERAL {@code "-credential"} off the requirement before
     * comparing, so it does not recognise {@code "SMTP Credential"} at all. Two readers, two
     * rules, both applied.
     */
    private static boolean offeredForTheSameRequirement(String keyA, String keyB) {
        if (keyA == null || keyB == null || keyA.isBlank() || keyB.isBlank()) {
            return false;
        }
        return keyA.equals(keyB)
                || keyA.equals(keyB + CREDENTIAL_REQUIREMENT_SUFFIX_KEY)
                || keyB.equals(keyA + CREDENTIAL_REQUIREMENT_SUFFIX_KEY);
    }

    /**
     * {@link #nameIdentifies(Credential, String)} against a bare {@code integration} value, so
     * the rename guard can apply the SAME rule to rows it reads one column of. Keeping one
     * implementation is the point: a guard that approximated this rule would refuse renames the
     * resolver considers harmless, or allow ones it considers ambiguous.
     */
    private static boolean nameIdentifies(String integration, String requirementName) {
        if (integration == null || integration.isBlank()) {
            return true;
        }
        String integrationKey = IconSlugNormalizer.normalizeForKey(integration);
        if (integrationKey.equals(IconSlugNormalizer.normalizeForKey(requirementName))) {
            return true;
        }
        String stripped = requirementName == null
                ? null
                : requirementName.replaceAll("-credential$", "");
        return stripped != null
                && !stripped.equals(requirementName)
                && integrationKey.equals(IconSlugNormalizer.normalizeForKey(stripped));
    }

    /** The two routes an {@code llm_<provider>} credential can take (see {@link #setLlmKeyModeForScope}). */
    public static final Set<String> LLM_KEY_MODES = Set.of("no_proxy", "proxy");

    /**
     * Switch whose key serves the caller's executions on one provider: {@code no_proxy} =
     * the saved key (their billing, a flat platform fee per turn), {@code proxy} = the
     * platform key (platform billing). The key stays saved either way, so switching back is
     * one click and never a re-paste.
     *
     * <p>Only an {@code llm_<provider>} credential in the caller's workspace can be switched:
     * anything else is refused as invalid, never silently given a {@code mode}.
     *
     * @return the credential after the switch, empty when out of scope or gone
     * @throws IllegalArgumentException for a mode outside {@link #LLM_KEY_MODES} or a
     *         credential that is not an LLM key
     */
    @Transactional
    public Optional<Credential> setLlmKeyModeForScope(Long id,
                                                      String tenantId,
                                                      String organizationId,
                                                      String mode) {
        TenantResolver.requireOrgId(organizationId);
        if (mode == null || !LLM_KEY_MODES.contains(mode)) {
            throw new IllegalArgumentException("'mode' must be one of " + LLM_KEY_MODES);
        }
        Optional<Credential> existing = credentialRepository.findById(id)
                .filter(cred -> matchesScope(cred, tenantId, organizationId));
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        Credential credential = existing.get();
        if (credential.integration() == null || !credential.integration().startsWith("llm_")) {
            throw new IllegalArgumentException("Only an LLM provider key has a route to switch");
        }
        if (credentialRepository.updateLlmMode(id, organizationId, mode) == 0) {
            return Optional.empty(); // deleted between the read and the write
        }
        log.info("Credential {} ({}) switched to mode '{}' by {} in org {}",
                id, credential.integration(), mode, tenantId, organizationId);
        return credentialRepository.findById(id)
                .filter(cred -> matchesScope(cred, tenantId, organizationId));
    }

    /**
     * Rename a credential in the caller's active workspace. Strict isolation:
     * returns empty when the row does not exist or lives in another scope,
     * exactly like {@link #getCredentialForScope} / {@link #deleteCredentialForScope}
     * (so an org member can rename a credential shared in their workspace, and
     * nobody can rename across workspaces).
     *
     * <p><b>A rename is a relabel.</b> Only {@code name} and {@code updated_at}
     * change; {@code integration}, {@code is_default}, the status and the encrypted
     * {@code credential_data} are untouched ({@link CredentialRepository#updateName}).
     * Everything that pins a credential pins its {@code id} (workflow nodes, agent
     * tool configs, published apps) and the id never moves.
     *
     * <p><b>Two lookups do read the name, and this method refuses the renames that
     * would disturb them</b> rather than letting a relabel change which key an
     * execution runs on:
     * <ul>
     *   <li>A credential with a BLANK {@code integration} is identified by its name
     *       and nothing else - catalog-service validates a pinned credential that
     *       way ({@code HttpExecutionService.resolvePinnedCredentialOwnership}) and
     *       the builder's picker mirrors it
     *       ({@code frontend/lib/credentials/credentialMatching.ts}). Renaming would
     *       detach every node pinning it, so it is refused
     *       ({@link CredentialRenameRefusedException.Reason#NAME_IS_IDENTITY}).</li>
     *   <li>{@code InternalCredentialService.findCredential} tries the exact name
     *       before the {@code integration} slug, and catalog's run-time selector
     *       ({@code HttpExecutionService.resolveCredentialIdNamed}) matches a typed
     *       LABEL, trimmed and case-insensitively, among the credentials the
     *       endpoint's integration would offer. A rename is refused
     *       ({@link CredentialRenameRefusedException.Reason#DUPLICATE_NAME}) when
     *       another credential of the owner carries that name AND either declares the
     *       same integration (the selector would then call the choice ambiguous and
     *       resolve nothing) or answers to the name alongside this one
     *       ({@link #nameIdentifies(String, String)} true for both).</li>
     * </ul>
     * What remains possible, deliberately, in two shapes. A name that identifies
     * neither credential and belongs to a DIFFERENT provider: no reader can confuse
     * them, and refusing it was refusing a rename over a row of an unrelated API,
     * often in a workspace the user cannot even see, with a message they had no way to
     * act on. And a name that identifies THIS credential but no other row, typically
     * labelling a credential with its own provider slug: that is allowed, and it does
     * change which key answers, because the name branch now resolves to this row where
     * it previously fell through to the integration default. Same provider, different
     * key, its own quota and granted scopes.
     *
     * <p><b>Known gap, recorded rather than relied on:</b> {@link #createCredential}
     * applies neither this rule nor the identity rule, so the very ambiguity refused
     * here can still be created in one click. That is not an argument for a weaker
     * guard, it is a hole on the create path; it is left alone here only because
     * credential names on the OAuth callback path are generated, and a hard refusal
     * there would break a legitimate second account of one provider.
     *
     * <p><b>One rename that used to succeed now fails</b>, on an install that already holds
     * near-duplicate labels: the probe matches trimmed and case-insensitively, so an owner whose
     * two keys of one provider are called {@code "Prod"} and {@code "prod"} gets a 409 where they
     * previously got a rename. Catalog's selector already cannot resolve that pair, so the
     * refusal reports a state that was silently broken rather than creating a new problem, but
     * it is a new refusal and it ships with no warning.
     *
     * <p><b>Also unguarded, and inherent:</b> renaming a credential AWAY from a name
     * that identified it hands that requirement back to the {@code integration}
     * fallback, which may select a different key of the same provider. Guarding it
     * would make every credential named after its provider unrenameable, which is the
     * over-refusal this rule exists to avoid.
     *
     * @param newName trimmed, non-blank, free of control characters, at most
     *                {@value #MAX_NAME_LENGTH} chars (the column is
     *                {@code VARCHAR(255)})
     * @throws IllegalArgumentException          when the name is blank, too long, or
     *                                           carries control characters
     * @throws CredentialRenameRefusedException  when the credential is identified by
     *                                           its name, or the name is taken
     */
    @Transactional
    public Optional<Credential> renameCredentialForScope(Long id,
                                                         String tenantId,
                                                         String organizationId,
                                                         String newName) {
        // Both throw IllegalArgumentException, and the HTTP layer reports the two
        // differently, so it pre-checks the workspace itself and only ever reaches this
        // requireOrgId with a valid one. Kept for non-HTTP callers.
        TenantResolver.requireOrgId(organizationId);
        String trimmed = validateName(newName);

        Optional<Credential> existing = credentialRepository.findById(id)
                .filter(cred -> matchesScope(cred, tenantId, organizationId));
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        Credential credential = existing.get();
        if (trimmed.equals(credential.name())) {
            return Optional.of(credential); // no-op rename: skip the write
        }
        if (isNameTheIdentity(credential)) {
            // EVERY rename, including one that looks identity-preserving. Two of the
            // three readers normalise the name (catalog's pinned-credential check and
            // the builder's picker), but the third does not: the token lookup starts at
            // findAllByTenantIdAndName, whose SQL is an EXACT `name = ?`. Verified live on
            // 2026-08-24 - renaming "smtp" to "SMTP" left the pin and the picker intact
            // and still emptied `data-map?name=smtp`. So the refusal is deliberately as
            // wide as the strictest reader.
            throw new CredentialRenameRefusedException(
                    CredentialRenameRefusedException.Reason.NAME_IS_IDENTITY,
                    "This credential carries no integration, so its name is what identifies it "
                            + "to the nodes that use it. Renaming it would detach them.");
        }
        // TWO readers select a credential by name, and the guard has to satisfy both.
        //
        //   1. findByNameIdentifyingIntegration (auth). A name resolves a credential only when
        //      nameIdentifies holds, so two rows are in each other's way under a name only when
        //      BOTH would answer for it.
        //   2. HttpExecutionService.resolveCredentialIdNamed (catalog), the run-time credential
        //      selector on a step. It matches the LABEL a person typed, trimmed and
        //      case-insensitively, over the credentials the endpoint's integration would offer.
        //      No slug is involved: two ACTIVE credentials of ONE provider sharing any label at
        //      all make it report "ambiguous", and it then either fails the step outright or
        //      falls back to the account default, i.e. runs on a key nobody chose.
        //
        // Reader 2 is why the same-provider arm cannot be dropped: it is the difference between
        // this guard and one derived from the auth resolver alone, and dropping it lets a rename
        // break a step that was resolving correctly.
        //
        // Status is deliberately NOT part of the test even though reader 2 filters on ACTIVE: a
        // revoked credential can be reactivated, and a refusal that depends on a state which
        // flips underneath the user is worse than one that is slightly wide.
        //
        // SCOPE, stated because it does not match reader 2 and cannot be made to here. The probe
        // is the OWNER's tenant, which is exactly reader 1's scope. Reader 2 resolves over an
        // ORG-scoped identity list, so against it this scope is wrong in both directions: it
        // refuses across two workspaces of the same owner, where that reader would never see the
        // two rows together, and it misses a teammate's identically labelled credential, where
        // it would. Fixing the second means refusing over rows of another person's tenant, which
        // is a wider decision than this one; see CredentialRepository for the full note.
        String renamedKey = IconSlugNormalizer.normalizeForKey(credential.integration());
        String labelKey = IconSlugNormalizer.normalizeForKey(trimmed);
        boolean renamedAnswersToTheName = nameIdentifies(credential.integration(), trimmed);
        // A plain loop, not stream().filter(...).findFirst(): the contending integration is
        // NULL for a credential that declares none, and findFirst throws on a null element.
        // That row is the single most important case here (its NAME is its identity), so the
        // stream form would turn the refusal it exists for into a 500.
        for (String otherIntegration : credentialRepository
                .findOtherIntegrationsWithNameForTenant(id, credential.tenantId(), trimmed)) {
            boolean contenderIsNameless = otherIntegration == null || otherIntegration.isBlank();
            // Reader 2, first two arms: the endpoint offers both rows because their integrations
            // name one provider.
            boolean sameProvider = !contenderIsNameless
                    && offeredForTheSameRequirement(
                            IconSlugNormalizer.normalizeForKey(otherIntegration), renamedKey);
            // Reader 2, third arm: it also offers a row that declares NO integration when that
            // row's NAME collapses to the requirement's key. Both rows carry the new label here,
            // so the nameless one is offered whenever the label is this provider's requirement,
            // and the renamed one is offered because its integration is that provider. This is
            // the arm a pairwise integration comparison cannot see: the contender has no
            // integration to compare. Concretely, an SMTP connector named "SMTP Credential" and
            // an smtp key relabelled "SMTP Credential" are both offered for requirement
            // "smtp-credential", because normalizeForKey deletes the space and the hyphen alike.
            boolean namelessRivalForThisProvider =
                    contenderIsNameless && offeredForTheSameRequirement(labelKey, renamedKey);
            // Reader 1: a name resolves a credential only when nameIdentifies holds, so two rows
            // are in each other's way there only when it holds for both.
            boolean bothAnswerToTheName =
                    renamedAnswersToTheName && nameIdentifies(otherIntegration, trimmed);
            if (!sameProvider && !namelessRivalForThisProvider && !bothAnswerToTheName) {
                continue;
            }
            // The integration stays in the LOG, not in the response: it belongs to a row the
            // caller may not be able to open, and the message reaches them over HTTP.
            log.info("Refusing to rename credential {} to '{}': a credential of owner {} already "
                            + "answers to that name (integration={}, sameProvider={})",
                    id, trimmed, credential.tenantId(),
                    otherIntegration == null ? "none" : otherIntegration, sameProvider);
            throw new CredentialRenameRefusedException(
                    CredentialRenameRefusedException.Reason.DUPLICATE_NAME,
                    "'" + trimmed + "' already identifies another credential of this "
                            + "credential's owner");
        }
        if (credentialRepository.updateName(id, organizationId, trimmed) == 0) {
            return Optional.empty(); // deleted between the read and the write
        }
        log.info("Credential {} renamed from '{}' to '{}' (integration={}, owner={}) by {} in org {}",
                id, credential.name(), trimmed, credential.integration(), credential.tenantId(),
                tenantId, organizationId);
        // Re-read through the same scope filter: a concurrent scope move must not
        // hand back a row the caller can no longer see.
        return credentialRepository.findById(id)
                .filter(cred -> matchesScope(cred, tenantId, organizationId));
    }

    /**
     * Update credential data (for OAuth2 token refresh).
     */
    public Credential updateCredentialData(Long id, String tenantId, Map<String, Object> newCredentialData) {
        Optional<Credential> existingOpt = credentialRepository.findById(id);
        if (existingOpt.isEmpty()) {
            throw new IllegalArgumentException("Credential not found: " + id);
        }

        Credential existing = existingOpt.get();
        if (!existing.tenantId().equals(tenantId)) {
            throw new IllegalArgumentException("Credential does not belong to this user");
        }

        Credential updated = new Credential(
            existing.id(),
            existing.tenantId(),
            existing.organizationId(),
            existing.name(),
            existing.integration(),
            existing.type(),
            existing.environment(),
            existing.status(),
            existing.description(),
            newCredentialData,
            existing.scopes(),
            existing.tags(),
            existing.owner(),
            existing.iconUrl(),
            existing.isDefault(),
            Instant.now(), // Update lastUsed
            existing.createdAt(),
            Instant.now()
        );

        return credentialRepository.save(updated);
    }

    /**
     * Record that a credential was exercised at execution time (drives the
     * "Last used" column in settings). Thin pass-through to
     * {@link CredentialRepository#touchLastUsed}: only {@code last_used} moves -
     * {@code credential_data} and {@code updated_at} are left untouched, unlike
     * {@link #updateCredentialData} which rewrites the whole row on an OAuth2
     * refresh. Callers throttle to avoid write amplification on hot workflows.
     */
    public void touchLastUsed(Long id) {
        credentialRepository.touchLastUsed(id);
    }

    /**
     * Scrub selected keys from {@code credential_data}, merge diagnostic fields, and flip the
     * credential's status - in one persisted snapshot.
     *
     * <p>Used exclusively by the OAuth2 refresh pipeline on a terminal error: the refresh_token
     * (or access_token) must be <em>removed</em> from the map, not set to {@code null} - a null
     * value would leave a sentinel key behind and confuse the expiring-token sweep predicate
     * (which uses {@code jsonb_exists}). Callers pass:
     * <ul>
     *   <li>{@code fieldsToRemove} - keys to drop from the existing credential_data.</li>
     *   <li>{@code newStatus} - typically {@code needs_reauth} (TERMINAL_USER) or {@code error}
     *       (TERMINAL_CONFIG). Pass {@code null} to keep the current status.</li>
     *   <li>{@code diagFieldsToMerge} - diagnostic fields to add/overwrite
     *       ({@code refresh_error_reason}, {@code refresh_error_http_status},
     *       {@code refresh_error_provider_code}, {@code refresh_error_at},
     *       {@code refresh_attempts_before_terminal}, {@code refresh_cooldown_until}).
     *       Pass an empty map to skip.</li>
     * </ul>
     *
     * <p>The encryption of remaining sensitive fields is preserved - {@link CredentialRepository}
     * re-encrypts on save by inspecting which keys are sensitive, so no double-encryption occurs.
     */
    public Credential scrubSensitiveFields(Long id,
                                           String tenantId,
                                           Set<String> fieldsToRemove,
                                           CredentialStatus newStatus,
                                           Map<String, Object> diagFieldsToMerge) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId cannot be null or empty");
        }
        Optional<Credential> existingOpt = credentialRepository.findById(id);
        if (existingOpt.isEmpty()) {
            throw new IllegalArgumentException("Credential not found: " + id);
        }
        Credential existing = existingOpt.get();
        if (!existing.tenantId().equals(tenantId)) {
            throw new IllegalArgumentException("Credential does not belong to this user");
        }

        Map<String, Object> newData = existing.credentialData() == null
                ? new HashMap<>()
                : new HashMap<>(existing.credentialData());
        if (fieldsToRemove != null) {
            for (String key : fieldsToRemove) {
                newData.remove(key);
            }
        }
        if (diagFieldsToMerge != null && !diagFieldsToMerge.isEmpty()) {
            newData.putAll(diagFieldsToMerge);
        }

        Credential updated = new Credential(
                existing.id(),
                existing.tenantId(),
                existing.organizationId(),
                existing.name(),
                existing.integration(),
                existing.type(),
                existing.environment(),
                newStatus != null ? newStatus : existing.status(),
                existing.description(),
                newData,
                existing.scopes(),
                existing.tags(),
                existing.owner(),
                existing.iconUrl(),
                existing.isDefault(),
                existing.lastUsed(),
                existing.createdAt(),
                Instant.now()
        );
        return credentialRepository.save(updated);
    }

    /**
     * Cascade-revoke every active user credential for {@code (tenantId, integration)}
     * when the underlying tenant-owned BYOK platform_credential row is being deleted.
     *
     * <p>This is the load-bearing fix for the silent-zombie bug raised in v3/v4 audits:
     * {@code OAuth2Service.handleOAuth2Callback} caches {@code oauth_client_secret} +
     * {@code client_secret_masked} INLINE in {@code auth.credentials.credential_data}
     * at OAuth-callback time, and {@code OAuth2Service.refreshAccessToken} reads that
     * inline copy first (only falling back to {@code platform_credentials} when absent).
     * Without this scrub, deleting the BYOK row leaves dependent user credentials
     * refreshing indefinitely against a platform_credential that no longer exists.
     *
     * <p>Steps for each dependent row already in {@code active}/{@code expiring}:
     * <ol>
     *   <li>Reuse {@link #scrubSensitiveFields} to drop the inline OAuth client copy
     *       AND the outstanding token material, and flip status to
     *       {@code needs_reauth}. The token scrub matches what
     *       {@code OAuth2Service.releaseTerminal} writes for any other terminal-user
     *       transition, so the rest of the refresh pipeline (scheduler, fast-path
     *       gate, internal-credential resolver) treats the row identically to a
     *       refresh-rejected credential.</li>
     *   <li>Set the Redis "refresh-disabled" sentinel + clear any "refresh-cooldown"
     *       so the in-flight fast-path gate immediately rejects further attempts
     *       without a DB round-trip. Same prefixes as {@link OAuth2Service}; making
     *       the constants {@code public} avoids a circular dep back into that service.</li>
     * </ol>
     *
     * <p>Rows already in a terminal state ({@code error}/{@code needs_reauth}) are
     * skipped - re-scrubbing would bump {@code updated_at} and might inadvertently
     * clear diagnostic fields useful for support. The method is idempotent: a second
     * call after every dependent has already been revoked returns {@code 0}.
     *
     * @return number of dependent credentials transitioned (excludes already-terminal rows)
     */
    @Transactional
    public int revokeForByokDelete(String tenantId, String integration) {
        if (tenantId == null || tenantId.isBlank() || integration == null || integration.isBlank()) {
            return 0;
        }
        // The BYOK row stores integration_name in normalized form (no separators, lowercased).
        // auth.credentials.integration stores the RAW iconSlug from the catalog template,
        // which may contain '-' or '_' (e.g. "audit-tracking", "azure_translator"). The
        // normalized-comparison repo method handles the asymmetry - see its javadoc.
        // Re-normalize on this side defensively in case the caller passes a non-normalized
        // form; the SQL is symmetric so a doubly-normalized input is a no-op.
        String normalizedIntegration = normalizeForCascade(integration);
        List<Credential> dependents = credentialRepository
                .findActiveByTenantIdAndIntegrationNormalized(tenantId, normalizedIntegration);
        if (dependents.isEmpty()) {
            return 0;
        }

        Set<String> fieldsToScrub = Set.of(
                // Tokens - match OAuth2Service.releaseTerminal so the row reaches the
                // same shape a refresh-rejected credential would.
                "access_token",
                "refresh_token",
                "refresh_token_issued_at",
                "refresh_cooldown_until",
                // BYOK-specific - the platform_credential row backing these is gone, so
                // the inline copy MUST also go or OAuth2Service.refreshAccessToken would
                // keep using it (line 725 reads inline first; only falls back when null).
                "oauth_client_secret",
                "client_secret_masked");

        Map<String, Object> diag = Map.of(
                "byok_revoked_at", Instant.now().toString(),
                "byok_revoke_reason", "platform_credential_deleted");

        int revoked = 0;
        for (Credential dep : dependents) {
            // Defense in depth: the SQL filter already limits to active/expiring, but a
            // lost-update race (status flipped between SELECT and UPDATE) shouldn't churn
            // a now-terminal row's diagnostics. Skip if status changed under us.
            if (dep.status() == CredentialStatus.error
                    || dep.status() == CredentialStatus.needs_reauth) {
                continue;
            }
            scrubSensitiveFields(dep.id(), tenantId, fieldsToScrub,
                    CredentialStatus.needs_reauth, diag);
            invalidateOAuth2RefreshSentinels(dep.id());
            revoked++;
        }

        if (revoked > 0) {
            log.info("Revoked {} user credentials following BYOK delete: tenant={}, integration={}",
                    revoked, tenantId, integration);
        }
        return revoked;
    }

    /**
     * Mirror of the SQL normalization used by
     * {@link CredentialRepository#findActiveByTenantIdAndIntegrationNormalized}: strip
     * everything except {@code [a-zA-Z0-9]}, then lowercase. Symmetric with the SQL so
     * the parameter and the row-side comparison reach the same canonical form.
     */
    private static String normalizeForCascade(String name) {
        if (name == null) return null;
        return name.replaceAll("[^a-zA-Z0-9]", "").toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Count the ACTIVE user credentials that would be transitioned to
     * {@code needs_reauth} if the BYOK platform_credential for
     * {@code (tenantId, integration)} were deleted right now. Mirror of
     * {@link #revokeForByokDelete} that does not mutate.
     *
     * <p>Uses the same normalized-integration comparison as the cascade itself
     * (see {@link CredentialRepository#countActiveByTenantIdAndIntegrationNormalized})
     * so the displayed delete-impact count matches what the cascade actually
     * transitions: rows already in {@code error} or {@code needs_reauth} are
     * excluded, and integrations with non-alphanumeric iconSlugs (e.g.
     * {@code "audit-tracking"}, {@code "azure_translator"}) are correctly
     * matched against their normalized BYOK row name.
     */
    public int countDependentForByokDelete(String tenantId, String integration) {
        if (tenantId == null || tenantId.isBlank() || integration == null || integration.isBlank()) {
            return 0;
        }
        return credentialRepository.countActiveByTenantIdAndIntegrationNormalized(
                tenantId, normalizeForCascade(integration));
    }

    /**
     * Delete the OAuth2 fast-path Redis sentinels for a credential. Same prefixes the
     * refresh pipeline uses ({@link OAuth2Service#REDIS_REFRESH_DISABLED_PREFIX} and
     * {@link OAuth2Service#REDIS_REFRESH_COOLDOWN_PREFIX}). Best-effort: a Redis outage
     * leaves the DB as the ground truth and the next refresh attempt will read
     * {@code status='needs_reauth'} from there.
     *
     * <p>We DELETE rather than SET-disabled here: the credential's terminal state is
     * already encoded in the DB row (status + scrubbed tokens), so the fast-path gate
     * hitting empty Redis falls back to DB and sees the new status. SET-disabled would
     * also work but adds a TTL window and an extra round-trip to no benefit.
     */
    private void invalidateOAuth2RefreshSentinels(Long credentialId) {
        try {
            redisTemplate.delete(OAuth2Service.REDIS_REFRESH_DISABLED_PREFIX + credentialId);
            redisTemplate.delete(OAuth2Service.REDIS_REFRESH_COOLDOWN_PREFIX + credentialId);
        } catch (Exception redisDown) {
            log.debug("Redis unreachable while invalidating refresh sentinels for credential {}: {}",
                    credentialId, redisDown.getMessage());
        }
    }

    /**
     * Delete a credential.
     * Ensures there's always a default credential if any remain for the integration:
     * - If the deleted credential was the default, reassigns to the most recent
     * - If only one credential remains and none is default, makes it the default
     */
    public boolean deleteCredential(Long id, String tenantId) {
        Optional<Credential> credentialOpt = credentialRepository.findById(id);
        if (credentialOpt.isEmpty()) {
            return false;
        }

        Credential credential = credentialOpt.get();
        if (!credential.tenantId().equals(tenantId)) {
            return false;
        }

        boolean wasDefault = credential.isDefault();
        String integration = credential.integration();

        credentialRepository.deleteById(id);

        // Ensure there's always a default if credentials remain for this integration
        if (integration != null) {
            List<Credential> remaining = credentialRepository.findByTenantIdAndIntegration(tenantId, integration);
            if (!remaining.isEmpty()) {
                // Check if any remaining credential is default
                boolean hasDefault = remaining.stream().anyMatch(Credential::isDefault);
                if (!hasDefault || wasDefault) {
                    // No default exists, or the deleted one was the default - assign to most recent
                    Credential mostRecent = remaining.get(0);
                    credentialRepository.setAsDefault(tenantId, mostRecent.id());
                }
            }
        }

        return true;
    }

    /**
     * Delete all credentials for a given integration name (across all tenants).
     * Used when an API's auth type changes during catalog reimport to invalidate
     * all user-stored credentials for that API.
     *
     * @return the number of deleted credentials
     */
    public int markNeedsReauthByIntegration(String integration) {
        if (integration == null || integration.trim().isEmpty()) {
            throw new IllegalArgumentException("integration cannot be null or empty");
        }
        return credentialRepository.markNeedsReauthByIntegration(integration.trim());
    }

    /**
     * Get credentials for a tenant by integration. Legacy entry point -
     * cross-scope (returns both personal and org rows). New callers should use
     * {@link #getCredentialsByIntegrationForScope}.
     */
    public List<Credential> getCredentialsByIntegration(String tenantId, String integration) {
        if (tenantId == null || tenantId.trim().isEmpty()) {
            throw new IllegalArgumentException("tenantId cannot be null or empty");
        }
        return credentialRepository.findByTenantIdAndIntegration(tenantId, integration);
    }

    /**
     * Distinct ACTIVE-credential integration names for one tenant. Used by agent-facing
     * list tools to compute the {@code requirements.integrations[].configured} flag in
     * one batch call instead of N+1 per-integration lookups.
     */
    public java.util.Set<String> findActiveIntegrationsByTenantId(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) return java.util.Set.of();
        return credentialRepository.findActiveIntegrationsByTenantId(tenantId);
    }

    /**
     * Org-aware "configured integrations": when an active workspace is supplied,
     * returns the integrations configured by ANY member of that workspace (so the
     * agent's {@code configured} flag matches the org-aware credential resolution).
     * Falls back to tenant scope when no org is supplied (back-compat / personal CLI).
     */
    public java.util.Set<String> findActiveIntegrationsForScope(String tenantId, String organizationId) {
        if (organizationId != null && !organizationId.isBlank()) {
            return credentialRepository.findActiveIntegrationsByOrganizationId(organizationId);
        }
        return findActiveIntegrationsByTenantId(tenantId);
    }

    /**
     * PR19 - strict-isolation by-integration lookup. Returns ONLY rows in
     * the active workspace's scope. Used by {@code /api/credentials/by-integration}.
     */
    public List<Credential> getCredentialsByIntegrationForScope(String tenantId,
                                                                String organizationId,
                                                                String integration) {
        if (tenantId == null || tenantId.trim().isEmpty()) {
            throw new IllegalArgumentException("tenantId cannot be null or empty");
        }
        return credentialRepository.findByScopeAndIntegration(tenantId, organizationId, integration);
    }

    /**
     * Get all credentials for a tenant. Legacy entry point - cross-scope.
     */
    public List<Credential> getAllCredentialsByTenant(String tenantId) {
        if (tenantId == null || tenantId.trim().isEmpty()) {
            throw new IllegalArgumentException("tenantId cannot be null or empty");
        }
        return credentialRepository.findAllByTenantId(tenantId);
    }

    /**
     * PR19 - strict-isolation "all credentials" for the active workspace.
     * Backed by the same finders as {@link #getCredentialsForScope} without
     * pagination. Used by frontend workflow-builder + chat-attachment paths
     * that need a non-paginated lookup.
     *
     * <p>Post-V261: {@code organizationId} is REQUIRED.
     */
    public List<Credential> getAllCredentialsForScope(String tenantId, String organizationId) {
        if (tenantId == null || tenantId.trim().isEmpty()) {
            throw new IllegalArgumentException("tenantId cannot be null or empty");
        }
        TenantResolver.requireOrgId(organizationId);
        // Unpaginated - use a large page-size so the existing scope-aware
        // repository finder returns every row in one shot. (For tenants with
        // > Integer.MAX_VALUE creds we have bigger problems; 10_000 covers
        // every realistic scope.)
        return credentialRepository.findByOrganizationIdStrict(organizationId, 1, 10_000);
    }

    /**
     * PR19 - scope-aware {@code setAsDefault}. The repository sweep that
     * clears sibling defaults is narrowed to the active scope so toggling an
     * org credential as default NEVER touches a different org's defaults.
     * Strict-isolation guard: caller must be an active member of the row's
     * org (membership is enforced upstream by the gateway).
     *
     * <p>Post-V261: {@code organizationId} is REQUIRED.
     */
    public void setAsDefault(String tenantId, String organizationId, Long credentialId) {
        if (tenantId == null || tenantId.trim().isEmpty()) {
            throw new IllegalArgumentException("tenantId cannot be null or empty");
        }
        TenantResolver.requireOrgId(organizationId);
        if (credentialId == null) {
            throw new IllegalArgumentException("credentialId cannot be null");
        }
        // Strict-isolation gate: refuse to mutate a row in a different scope.
        Credential cred = credentialRepository.findById(credentialId)
                .filter(c -> matchesScope(c, tenantId, organizationId))
                .orElseThrow(() -> new IllegalArgumentException(
                        "Credential not found in active scope: " + credentialId));
        credentialRepository.setAsDefaultInScope(cred.tenantId(), organizationId, credentialId);
    }

    /**
     * PR19 - scope-aware {@code clearDefault}. The "only credential for
     * integration" guard counts rows in the active org scope only, so a
     * different org's Gmail credential never blocks clearing the default flag
     * from this org's Gmail credential.
     *
     * <p>Post-V261: {@code organizationId} is REQUIRED.
     */
    public void clearDefault(String tenantId, String organizationId, Long credentialId) {
        if (tenantId == null || tenantId.trim().isEmpty()) {
            throw new IllegalArgumentException("tenantId cannot be null or empty");
        }
        TenantResolver.requireOrgId(organizationId);
        if (credentialId == null) {
            throw new IllegalArgumentException("credentialId cannot be null");
        }

        Credential credential = credentialRepository.findById(credentialId)
                .filter(c -> matchesScope(c, tenantId, organizationId))
                .orElseThrow(() -> new IllegalArgumentException(
                        "Credential not found in active scope: " + credentialId));

        // Scope-isolated "only credential for integration" check - never count
        // across scope boundaries.
        String integration = credential.integration();
        if (integration != null) {
            int count = credentialRepository.countByScopeAndIntegrationExact(
                    credential.tenantId(), organizationId, integration);
            if (count == 1 && credential.isDefault()) {
                throw new IllegalStateException(
                        "Cannot remove default status: this is the only credential for " + integration);
            }
        }

        credentialRepository.clearDefaultInScope(credential.tenantId(), organizationId, credentialId);
    }

    /**
     * Opaque credential-state version for one execution context (own rows +
     * active-workspace rows). Moves on every mutation that can change credential
     * resolution (connect / delete / set-as-default / edit / token refresh).
     * Consumed by catalog-service as a response-cache key component - see
     * {@code CredentialRepository.computeStateVersion}.
     */
    public String getCredentialStateVersion(String tenantId, String organizationId) {
        if (tenantId == null || tenantId.trim().isEmpty()) {
            throw new IllegalArgumentException("tenantId cannot be null or empty");
        }
        return credentialRepository.computeStateVersion(tenantId.trim(), organizationId);
    }

    /**
     * Get the default credential for an integration.
     * Returns the first credential marked as default, or empty if none.
     */
    public Optional<Credential> getDefaultCredential(String tenantId, String integration) {
        if (tenantId == null || tenantId.trim().isEmpty()) {
            throw new IllegalArgumentException("tenantId cannot be null or empty");
        }
        return credentialRepository.findDefaultByTenantIdAndIntegration(tenantId, integration);
    }
}
