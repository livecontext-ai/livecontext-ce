package com.apimarketplace.agent.tools.credential;

import com.apimarketplace.common.icon.IconSlugNormalizer;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Which of the owner's non-default accounts a call could actually run on, and the
 * sentence that offers them to an agent.
 *
 * <p>Two callers can name one: a workflow step, through its {@code credential_selector},
 * and a direct catalog execute, through its {@code credential_name} argument. Both resolve
 * the name through the same matcher, so one offer serves both. The default account needs no
 * naming on either, which is why it is not offered here; it is still in the listing.
 *
 * <p>Two tools list credentials and neither shows the other's output: workflow-building
 * agents call {@code get_connected_services}, chat agents call {@code credential(action='list')}.
 * They shape the same rows into the same {@code {name, integration, status, isDefault}}
 * entries, so this lives here rather than in either: the first version of this logic
 * existed only on the orchestrator side, and the chat side went on telling agents that
 * every non-default account was selectable, which is the claim the whole correction was
 * about. One copy is the only way that stays fixed.
 *
 * <p><b>The list is read as an offer, and the runtime is fail-closed.</b> Naming an entry
 * the matcher will not accept does not degrade to the default account, it FAILS the step.
 * So every refusal the matcher makes has to be mirrored here:
 * <ul>
 *   <li>a status other than {@code active} (a revoked entry is still listed),</li>
 *   <li>a name held by two active entries of one integration, which matches neither
 *       because picking one would be picking at random,</li>
 *   <li>a name that is a positive whole number, read as a credential id before the name
 *       path is consulted at all (so {@code 0} and {@code -1} are NOT ids, which is why
 *       every text says POSITIVE whole number),</li>
 *   <li>a blank name, and an entry with no integration to attribute it to.</li>
 * </ul>
 *
 * <p>Ambiguity is decided through {@link IconSlugNormalizer}, the same normaliser the
 * matcher compares identities with, so that half is exact rather than approximated. What
 * cannot be mirrored from a listing is the endpoint's own requirement, which widens the
 * candidate set in a way no listing can see. Any residual divergence has to stay in the
 * direction of offering LESS: an over-offer costs a failed step, an under-offer costs a
 * lookup.
 *
 * <p>One deliberate under-offer: a credential with a blank integration is never OFFERED,
 * though the runtime does admit one whose NAME collapses to the requirement slug. That is
 * how the workflow-native connectors (smtp, ssh, database) identify themselves, and an
 * entry with no integration cannot be attributed to a step in a listing, so offering it
 * tells the agent nothing it can act on. It still COUNTS toward ambiguity: dropping it
 * from the tally instead would let the namesake it collides with look unique and be
 * offered, which is the over-offer this whole class exists to prevent.
 */
public final class SelectableAccounts {

    /** How many names an offer spells out before it starts counting instead. */
    static final int MAX_NAMED = 12;

    private SelectableAccounts() {
    }

    /** The non-default entries that naming in a {@code credential_selector} could resolve to. */
    public static List<Map<String, Object>> selectable(List<Map<String, Object>> connected) {
        if (connected == null || connected.isEmpty()) {
            return List.of();
        }
        Map<String, Long> activeNameUses = connected.stream()
            .filter(SelectableAccounts::isActive)
            .collect(Collectors.groupingBy(SelectableAccounts::nameKey, Collectors.counting()));

        return connected.stream()
            .filter(c -> !Boolean.TRUE.equals(c.get("isDefault")))
            .filter(SelectableAccounts::isActive)
            .filter(SelectableAccounts::isSelectableByName)
            .filter(c -> activeNameUses.getOrDefault(nameKey(c), 0L) == 1L)
            .toList();
    }

    /**
     * The clause to append to a listing's hint, or an empty string when nothing qualifies.
     *
     * <p>Silence is the correct output for an empty list: naming the field when no account
     * can be selected sends the agent looking for one that is not there.
     */
    public static String offer(List<Map<String, Object>> connected) {
        List<Map<String, Object>> selectable = selectable(connected);
        if (selectable.isEmpty()) {
            return "";
        }
        // Names are quoted because they are free text and the list is comma-separated: an
        // account called `Acme, Inc` would otherwise read as two names, both of which fail.
        String names = selectable.stream()
            .limit(MAX_NAMED)
            // The quote is escaped as well as added: a name containing one would otherwise
            // close the quoting early and re-create the ambiguity this is here to remove.
            .map(c -> String.format(Locale.ROOT, "\"%s\" (%s)",
                trimmedString(c.get("name")).replace("\"", "\\\""),
                trimmedString(c.get("integration"))))
            .collect(Collectors.joining(", "));
        // Without a cap this grows with the account's credential count and is served on
        // EVERY call. Say the list was cut, so the agent reads the rest out of `connected`
        // rather than concluding the missing ones do not exist. The cap is global, not per
        // integration, so it can hide the only account of one.
        int hidden = Math.max(0, selectable.size() - MAX_NAMED);
        String more = hidden == 0
            ? ""
            : String.format(Locale.ROOT, " and %d more in the connected list", hidden);
        return String.format(Locale.ROOT, " Also held and selectable BY NAME, either on a direct "
            + "catalog execute call through its credential_name argument, or by a workflow step "
            + "that names one in its credential_selector (active only): %s%s.", names, more);
    }

    private static boolean isActive(Map<String, Object> credential) {
        // equalsIgnoreCase, like the matcher. Callers lower-case the status today, but this
        // takes a bare map and is one call site away from being handed a raw one.
        return credential.get("status") instanceof String status && "active".equalsIgnoreCase(status);
    }

    private static boolean isSelectableByName(Map<String, Object> credential) {
        String name = trimmedString(credential.get("name"));
        // Normalised, not merely non-blank: an integration of "!!!" is non-blank and
        // normalises to nothing, and sameCredentialIdentity refuses a blank side, so
        // such an entry is never a run-time candidate however it is named.
        if (name.isEmpty() || IconSlugNormalizer.normalize(
                trimmedString(credential.get("integration"))).isEmpty()) {
            return false;
        }
        try {
            return Long.parseLong(name) <= 0;
        } catch (NumberFormatException notANumber) {
            return true;
        }
    }

    /** Keyed the way the matcher compares: normalised identity, then trimmed case-folded name. */
    private static String nameKey(Map<String, Object> credential) {
        // Nothing splits this key back apart; what matters is that the concatenation is
        // injective. The identity half is [a-z0-9]* after normalising, so it can never
        // contain the separator, and no two distinct pairs can produce one string.
        //
        // The name is folded with toLowerCase(ROOT) where the matcher uses
        // equalsIgnoreCase. Those disagree only on exotica such as U+0130 vs "i", where
        // the matcher calls two names equal and refuses both while this would offer both.
        // Left alone deliberately: the case is vanishingly rare and chasing it would cost
        // more clarity than it buys.
        return identityKey(credential) + "/"
            + trimmedString(credential.get("name")).toLowerCase(Locale.ROOT);
    }

    /**
     * The bucket two entries must share to be ambiguous at run time.
     *
     * <p>{@code credentialIdentityMatchesRequirement} admits a credential three ways: by
     * its integration, by its integration matching the requirement, and, when it has NO
     * integration, by its NAME matching the requirement. So bucketing on the integration
     * alone splits pairs the matcher would find ambiguous, and splitting them is the one
     * error that offers MORE: each half then looks unique. Two shapes need folding in.
     *
     * <p>A blank integration keys on the name, which is how the matcher attributes it.
     * Without this, an account named {@code smtp} with no integration and another named
     * {@code smtp} under integration {@code smtp} land in different buckets, the second
     * is offered, and naming it at run time matches both and resolves neither. Note the
     * fold is exact only where the requirement is the bare integration: under a
     * {@code smtp-credential} requirement the matcher would NOT admit the blank-integration
     * entry, so this bucket is conservative there rather than faithful.
     *
     * <p>A trailing {@code -credential} is stripped because that is how a requirement
     * names its integration, so {@code acme} and {@code acme-credential} are one identity
     * to the matcher. The strip is case-INsensitive here where the matcher's is not, which
     * only ever folds MORE together, so it can shrink the offer and never widen it. The remaining axis, an endpoint whose requirement names some third
     * integration, is not knowable from a listing; it stays an accepted divergence.
     */
    private static String identityKey(Map<String, Object> credential) {
        String integration = trimmedString(credential.get("integration"));
        String identity = integration.isEmpty() ? trimmedString(credential.get("name")) : integration;
        // IconSlugNormalizer is the SAME normaliser the matcher compares through
        // (catalog-service's copy delegates to this one), so this is not an approximation
        // of the integration comparison any more, only of the requirement axis above.
        return IconSlugNormalizer.normalize(identity.replaceAll("(?i)-credential$", ""));
    }

    private static String trimmedString(Object value) {
        return value instanceof String text ? text.trim() : "";
    }
}
