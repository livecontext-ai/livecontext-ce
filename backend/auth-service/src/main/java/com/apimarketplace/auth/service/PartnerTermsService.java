package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.PartnerTermsAcceptance;
import com.apimarketplace.auth.domain.PartnerTermsAcceptance.Source;
import com.apimarketplace.auth.repository.PartnerTermsAcceptanceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The Partner Program Terms (V557): which version is current, who accepted what, and the one
 * rule the money follows, no payout to a partner who never accepted them.
 *
 * <p>The current version and the fingerprint of its text are constants, not configuration: the
 * text is compiled into the frontend, and a guard test there checks both against it (the
 * fingerprint is the sha256 of the two language versions). A partner accepts the text they were
 * shown, so an application or an acceptance quoting any other version is refused with
 * {@code terms_outdated}, and the partner reloads the current text. Publishing a new text means
 * a new version and a new fingerprint here, never a new fingerprint for an existing version.
 *
 * <p>An acceptance is evidence of a contract, so it is recorded once per version (the first
 * click wins) with the fingerprint of the text, the client address and browser, and never
 * changed.
 */
@Service
public class PartnerTermsService {

    private static final Logger log = LoggerFactory.getLogger(PartnerTermsService.class);

    /** The version printed on the terms pages. Must equal PARTNER_TERMS_VERSION (frontend). */
    public static final String CURRENT_VERSION = "2026-10-01";
    /** sha256 of that version's text (both languages). Must equal PARTNER_TERMS_FINGERPRINT. */
    public static final String CURRENT_FINGERPRINT = "sha256:983a99ad5e3e59a9e5f6bf0c0cccaf825a94e226603a854bd7996e0cd2d94887";

    static final int MAX_IP = 64;
    static final int MAX_USER_AGENT = 256;

    /** A recorded acceptance, as the dashboard and the admin report show it. */
    public record Acceptance(String version, Instant acceptedAt) {}

    /**
     * Where a partner stands with the terms: the current version, their latest acceptance
     * (null when they never accepted any), and whether that acceptance is of the current version.
     */
    public record Status(String currentVersion, Acceptance latest, boolean acceptedCurrent) {
        public boolean acceptedAny() { return latest != null; }

        /** A partner who has not accepted the current version: the dashboard asks them to. */
        public boolean required(boolean partner) { return partner && !acceptedCurrent; }

        /**
         * A partner who never accepted any version: nothing is paid out to them until they do.
         * The same rule as the payout gate ({@link PartnerTermsService#hasAcceptedAny}).
         */
        public boolean payoutsBlocked(boolean partner) { return partner && !acceptedAny(); }
    }

    /** The client address and browser of the click, recorded as evidence. Either may be null. */
    public record Evidence(String ip, String userAgent) {
        public static Evidence none() { return new Evidence(null, null); }
    }

    private final PartnerTermsAcceptanceRepository repository;
    private final String currentVersion;
    private final String currentFingerprint;

    @Autowired
    public PartnerTermsService(PartnerTermsAcceptanceRepository repository) {
        this(repository, CURRENT_VERSION, CURRENT_FINGERPRINT);
    }

    /** Tests: another version, with the current fingerprint. */
    PartnerTermsService(PartnerTermsAcceptanceRepository repository, String currentVersion) {
        this(repository, currentVersion, CURRENT_FINGERPRINT);
    }

    PartnerTermsService(PartnerTermsAcceptanceRepository repository, String currentVersion, String currentFingerprint) {
        if (currentVersion == null || currentVersion.isBlank() || currentVersion.length() > 32) {
            throw new IllegalStateException("Partner terms version must be 1 to 32 characters");
        }
        this.repository = repository;
        this.currentVersion = currentVersion.trim();
        this.currentFingerprint = currentFingerprint;
    }

    public String currentVersion() {
        return currentVersion;
    }

    public String currentFingerprint() {
        return currentFingerprint;
    }

    /**
     * Why an acceptance of {@code version} cannot be recorded, or null when it can:
     * {@code terms_not_accepted} when no version was sent (the box was not ticked),
     * {@code terms_outdated} when it is not the current one.
     */
    public String refusal(String version) {
        if (version == null || version.isBlank()) return "terms_not_accepted";
        if (!currentVersion.equals(version.trim())) return "terms_outdated";
        return null;
    }

    /**
     * Record that {@code userId} accepted {@code version}. Returns the refusal token when the
     * version cannot be accepted (see {@link #refusal}), null otherwise. Accepting a version
     * already accepted is a success that records nothing new: the first acceptance stands.
     */
    @Transactional
    public String accept(Long userId, String version, Source source, Evidence evidence) {
        String refused = refusal(version);
        if (refused != null) return refused;
        Evidence e = evidence == null ? Evidence.none() : evidence;
        int written = repository.record(userId, currentVersion, currentFingerprint, Instant.now(), source.name(),
                cap(e.ip(), MAX_IP), cap(e.userAgent(), MAX_USER_AGENT));
        if (written == 1) log.info("Partner terms {} accepted by user {} ({})", currentVersion, userId, source);
        return null;
    }

    @Transactional(readOnly = true)
    public Status status(Long userId) {
        Optional<PartnerTermsAcceptance> latest = repository.findFirstByUserIdOrderByAcceptedAtDescIdDesc(userId);
        boolean current = repository.existsByUserIdAndTermsVersion(userId, currentVersion);
        return new Status(currentVersion, latest.map(PartnerTermsService::toAcceptance).orElse(null), current);
    }

    /** Whether the partner is bound by the terms at all: any version accepted. */
    @Transactional(readOnly = true)
    public boolean hasAcceptedAny(Long userId) {
        return userId != null && repository.existsByUserId(userId);
    }

    /** The latest acceptance of each of these partners; a partner who never accepted is absent. */
    @Transactional(readOnly = true)
    public Map<Long, Acceptance> latestFor(Collection<Long> userIds) {
        Map<Long, Acceptance> latest = new HashMap<>();
        if (userIds == null || userIds.isEmpty()) return latest;
        repository.findByUserIdIn(userIds).stream()
                .sorted(Comparator.comparing(PartnerTermsAcceptance::getAcceptedAt)
                        .thenComparing(PartnerTermsAcceptance::getId))
                .forEach(a -> latest.put(a.getUserId(), toAcceptance(a)));
        return latest;
    }

    private static Acceptance toAcceptance(PartnerTermsAcceptance a) {
        return new Acceptance(a.getTermsVersion(), a.getAcceptedAt());
    }

    private static String cap(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String v = value.trim();
        return v.length() > max ? v.substring(0, max) : v;
    }
}
