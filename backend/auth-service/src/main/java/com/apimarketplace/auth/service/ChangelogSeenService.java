package com.apimarketplace.auth.service;

import com.apimarketplace.auth.repository.ChangelogEntryFirstSeenRepository;
import com.apimarketplace.auth.repository.UserChangelogSeenRepository;
import com.apimarketplace.auth.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.regex.Pattern;

/**
 * Per-user acknowledgement of the in-app "What's new" entry, plus the deployment-level switch
 * that turns the whole affordance off.
 *
 * <p>The service never knows what the entry SAYS: the copy and the media live in the frontend
 * bundle, so cloud and CE each announce their own build. It knows the entry's key, which is all
 * two decisions need: has this user already acknowledged it, and was this account created after
 * this install started announcing it.
 */
@Service
public class ChangelogSeenService {

    /**
     * Accepted shape of an entry key. Narrow on purpose: the value is user-supplied (it comes from
     * the browser, which could send anything) and is stored, returned to every device of that
     * user, and compared against a key in the bundle. Nothing outside this alphabet can be a
     * legitimate key, so rejecting the rest keeps junk out of the row entirely.
     *
     * <p>The frontend enforces the SAME alphabet before it ever ships an entry
     * ({@code frontend/lib/changelog/latestEntry.ts}). The two are kept in step by
     * {@code ChangelogKeyPatternParityTest}, because divergence is silent and permanent: the
     * acknowledgement is refused with a 400 the client never surfaces, and the panel reopens for
     * every user in every new session.
     */
    static final String KEY_PATTERN_SOURCE = "^[A-Za-z0-9][A-Za-z0-9._-]{0,119}$";

    private static final Pattern KEY_PATTERN = Pattern.compile(KEY_PATTERN_SOURCE);

    private final UserChangelogSeenRepository seenRepository;
    private final ChangelogEntryFirstSeenRepository firstSeenRepository;
    private final UserRepository userRepository;
    private final boolean enabled;

    public ChangelogSeenService(UserChangelogSeenRepository seenRepository,
                                ChangelogEntryFirstSeenRepository firstSeenRepository,
                                UserRepository userRepository,
                                @Value("${changelog.enabled:true}") boolean enabled) {
        this.seenRepository = seenRepository;
        this.firstSeenRepository = firstSeenRepository;
        this.userRepository = userRepository;
        this.enabled = enabled;
    }

    /** Whether this deployment surfaces the in-app changelog at all. */
    public boolean isEnabled() {
        return enabled;
    }

    /** True when {@code key} is a shape this service is willing to store. */
    public static boolean isValidKey(String key) {
        return key != null && KEY_PATTERN.matcher(key).matches();
    }

    /**
     * What the caller needs to decide whether to announce {@code entryKey}.
     *
     * <p>When the feature is off the state is empty and no database access happens at all: an
     * operator who flips the switch gets the affordance gone, not merely hidden.
     *
     * @param entryKey the entry this build ships, or null when the caller has none (it then gets
     *                 the acknowledgement alone, and never a seal).
     */
    @Transactional
    public ChangelogState state(Long userId, String entryKey) {
        if (!enabled) {
            return new ChangelogState(false, null, false);
        }
        String seenKey = seenRepository.findEntryKey(userId).orElse(null);
        if (entryKey == null || !isValidKey(entryKey) || entryKey.equals(seenKey)) {
            // Already acknowledged (or nothing to compare against): the seal question cannot
            // arise, so neither the stamp nor the account lookup is worth a query. That is the
            // common case on every authenticated page load once a user has seen the entry.
            return new ChangelogState(true, seenKey, false);
        }
        return new ChangelogState(true, seenKey, shouldSeal(userId, entryKey));
    }

    /**
     * Whether this account post-dates the moment the install started announcing this entry.
     *
     * <p>Compared against THIS INSTALL's first sight of the entry, never the entry's publication
     * date: the publication date is written by a developer, while an install adopts a release
     * whenever it upgrades. Using it would make a self-hosted box that takes a release six months
     * late seal every user who signed up in between - none of them would ever see the
     * announcement, and nothing would say why.
     *
     * <p>Unknown account creation means "treat as an existing account", which announces: one
     * notice too many is the harmless direction, and it is still shown only once.
     */
    private boolean shouldSeal(Long userId, String entryKey) {
        Instant firstSeenAt = firstSeenRepository.findFirstSeenAt(entryKey)
                .orElseGet(() -> stampFirstSight(entryKey));
        if (firstSeenAt == null) {
            return false;
        }
        Instant accountCreatedAt = userRepository.findById(userId)
                .map(user -> toInstant(user.getCreatedAt()))
                .orElse(null);
        return accountCreatedAt != null && accountCreatedAt.isAfter(firstSeenAt);
    }

    /**
     * Records that the install is announcing this entry as of now, and answers with that instant.
     *
     * <p>Plain private method, joining the caller's transaction: a self-invoked
     * {@code @Transactional} would be silently ignored (the proxy is bypassed), which is the kind
     * of annotation that reads as a guarantee and provides none.
     *
     * <p>The instant is RE-READ rather than assumed, because the insert does nothing when another
     * request won the race: the value that matters is the winner's, i.e. the earlier one.
     */
    private Instant stampFirstSight(String entryKey) {
        firstSeenRepository.stampIfAbsent(entryKey);
        return firstSeenRepository.findFirstSeenAt(entryKey).orElse(null);
    }

    /**
     * Records that the user has seen {@code entryKey}.
     *
     * <p>Idempotent and unconditional: re-acknowledging the same key refreshes the timestamp, and
     * acknowledging a DIFFERENT key overwrites the row rather than comparing the two. There is no
     * ordering to enforce here, because after a rollback the entry actually displayed is an older
     * one.
     *
     * @return {@link SeenOutcome#DISABLED} when the feature is off and
     *         {@link SeenOutcome#UNKNOWN_USER} when no account has this id (a session that outlived
     *         the deletion of its account); nothing is written in either case.
     * @throws IllegalArgumentException when the key is not a shape this service stores. Validated
     *         here and not only at the edge: this is the method that writes the row, and a caller
     *         added later would otherwise put an over-long key straight into a constraint
     *         violation at flush time instead of a clean rejection.
     */
    @Transactional
    public SeenOutcome markSeen(Long userId, String entryKey) {
        if (!isValidKey(entryKey)) {
            throw new IllegalArgumentException("invalid changelog entry key");
        }
        if (!enabled) {
            return SeenOutcome.DISABLED;
        }
        // The upsert itself checks that the user exists (see the repository): a separate
        // existsById first would leave a window for the delete to land in between.
        return seenRepository.acknowledge(userId, entryKey) > 0
                ? SeenOutcome.RECORDED
                : SeenOutcome.UNKNOWN_USER;
    }

    /** What {@link #markSeen} did. */
    public enum SeenOutcome {
        /** The acknowledgement is stored. */
        RECORDED,
        /** The deployment has the feature off; nothing was written. */
        DISABLED,
        /** No account has this user id; nothing was written. */
        UNKNOWN_USER
    }

    /**
     * Reads the stored local timestamp in the JVM's own zone.
     *
     * <p>The column is written with {@code LocalDateTime.now()}, i.e. in the writing JVM's zone, so
     * reading it back in that same zone is the only interpretation that round-trips. An earlier
     * version asserted UTC; nothing in the deployment configuration pins the container's zone, so
     * that assertion was unbacked.
     */
    private static Instant toInstant(LocalDateTime createdAt) {
        return createdAt == null ? null : createdAt.atZone(ZoneId.systemDefault()).toInstant();
    }

    /**
     * @param enabled whether this deployment surfaces the changelog at all
     * @param seenKey last acknowledged entry key, or null when the user acknowledged none
     * @param seal    acknowledge the entry WITHOUT showing it: this account is newer than the
     *                moment the install started announcing it
     */
    public record ChangelogState(boolean enabled, String seenKey, boolean seal) {
    }
}
