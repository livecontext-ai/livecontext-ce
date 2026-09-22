package com.apimarketplace.orchestrator.services.template;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The last thing a node's parameters pass through before they are persisted.
 *
 * <p>{@code resolved_params} is copied into {@code workflow_step_data.input_data} and
 * rendered in the inspector's Params column, so everything in it is published to everyone
 * who can read the run, and kept for as long as the run is. Two properties have to hold
 * for every node, and each was broken somewhere:
 *
 * <ol>
 *   <li><b>No credential.</b> A webhook trigger's {@code basicPassword} / {@code
 *       jwtSecretKey} / {@code authHeaderValue}, an agent's {@code credentials}, a browser
 *       agent's {@code llm.api_key} and saved {@code session}, a signed token in a url's
 *       query string: each of these reached the column verbatim, on every epoch, because
 *       the node copied its whole configuration map. Masking is by KEY here rather than by
 *       value, because a node cannot know which of its strings authenticates - but the
 *       author named it.</li>
 *   <li><b>Nothing unbounded.</b> A request body, a whole dataset, a document: a value
 *       that has no size limit is copied onto the row of every item of every split. Values
 *       under the inline budget are kept EXACTLY as they are, typed and readable, because
 *       showing the value is the entire point of the column; only what exceeds it is
 *       replaced by a {@link ResolvedValuePreview} description of its shape.</li>
 * </ol>
 *
 * <p>Neither rule guesses. A key is masked because its NAME says it holds a credential,
 * and a value is described because its SIZE says it cannot be shown.
 */
public final class ReportedParams {

    private static final org.slf4j.Logger logger =
        org.slf4j.LoggerFactory.getLogger(ReportedParams.class);

    /** What a masked value reads as. Distinct from an absent key, which means nothing was set. */
    public static final String WITHHELD_CREDENTIAL = "<withheld: credential>";

    /**
     * A value weighing more than this is described rather than copied. Generous on purpose:
     * a url, a query, a prompt, a selector are all worth reading in full, and the budget is
     * here for the payloads that have no ceiling at all.
     */
    static final int MAX_INLINE_WEIGHT = 2_000;

    /**
     * The budget for a WHOLE reported map. Eight times the per-value one: a node with a
     * dozen readable parameters is never near it, and a node whose parameters add up to
     * more than this is writing a payload rather than a configuration.
     */
    static final int MAX_TOTAL_WEIGHT = 16_000;

    /**
     * How much of the report ONE pre-gated value may take. Half the map budget, so a node
     * that reports such a value still has room to report everything else it ran with, and
     * the whole row stays inside a single order of magnitude of {@link #MAX_TOTAL_WEIGHT}.
     */
    private static final int PRE_GATED_BUDGET = MAX_TOTAL_WEIGHT / 2;

    /** Says the map was cut, so a reader never mistakes the cut for the configuration. */
    public static final String TRUNCATED = "paramsTruncated";

    /** How deep the redaction walks. A node's configuration nests a block or two, no more. */
    static final int MAX_DEPTH = 6;

    /** Beyond this many elements a list or an array is described rather than walked. */
    static final int MAX_ITEMS = 200;

    /**
     * A key that IS a credential, whole. Checked first, before any exemption.
     *
     * <p>{@code sessionId} is here because a session id is a bearer credential, unlike
     * every other id, which is a reference.
     */
    private static final Set<String> CREDENTIAL_KEYS = Set.of(
        "token", "session", "sessionid", "authheadervalue", "apikey", "privatekey",
        "clientsecret", "credentials", "credential", "authorization", "cookie",
        "pwd", "pass", "auth", "sig", "pat", "authentication",
        // A DSN carries its password INLINE, so the whole string is the credential. On the
        // product's own list of what to encrypt at rest (CredentialEncryptionService
        // .SENSITIVE_FIELDS) and read by neither of the word rules: `connection` and
        // `string` each mean something innocent.
        "connectionstring",
        "key" /* only as a whole URL query name, see maskUrlSecrets */);

    /**
     * A WORD that means a credential wherever it appears in a key, exemptions included.
     * These never mean anything else: nothing legitimate is called a {@code password}
     * without being one, so {@code passwordSource} is as masked as {@code password}.
     *
     * <p>{@code credential} is deliberately NOT here: {@code credentialId} names WHICH
     * stored credential ran, which is what keeps a masked row diagnosable.
     */
    private static final Set<String> ABSOLUTE_CREDENTIAL_WORDS = Set.of(
        "password", "passwd", "passphrase", "secret", "authorization", "bearer",
        "cookie", "signature");

    /**
     * A word BEFORE {@code token} that makes it a COUNT, a CURSOR or a WINDOW rather than a
     * credential. {@code token} is masked unless one of these precedes it: the benign uses
     * are a short, closed list (a usage figure, a pagination cursor) while the credential
     * ones are open-ended - {@code botToken}, {@code webhookToken}, {@code cdpToken},
     * {@code securityToken}, one per vendor. An allow-list on the open side is a leak per
     * vendor nobody thought of; this way the unknown case is masked.
     */
    private static final Set<String> QUANTITY_QUALIFIERS = Set.of(
        "max", "min", "page", "next", "prev", "previous", "total", "count", "num",
        "number", "limit", "input", "output", "prompt", "completion", "cached", "per",
        // Pagination cursors, which is the other benign use: a continuation token names a
        // position in a result set, and masking it costs a reader the one value that says
        // where a paged read stopped.
        "continuation", "resume", "sync", "delta", "scroll", "cursor");

    /**
     * A whole key that is NOT a credential however its words read. The short list of
     * counter-examples the word rules cannot see: {@code tokens} (plural) is a usage figure
     * on every LLM surface, and {@code key} is the name of a map entry.
     */
    private static final Set<String> NEVER_CREDENTIAL_KEYS = Set.of("tokens", "key");

    /**
     * A value its PRODUCER has already gated, entry by entry: bounded here like anything
     * else, never masked by name.
     *
     * <p>A wrapper rather than a key name, because a key name is something any caller can
     * supply: an exemption for the string {@code "variableMapping"} would be reachable by
     * a tool argument that happens to be called that, and an exemption a caller can claim
     * for itself is not an exemption, it is a hole. Only a node that holds this type can
     * claim it.
     *
     * <p>The one producer is {@code InterfaceNode}, which builds
     * {@code {<author name>: {expression, resolved, status}}} where {@code resolved} has
     * already been through the workspace-variable rule this class owns: withheld for a
     * {@code $vars} scalar, a {@link ResolvedValuePreview} description above the scalar cap,
     * and the value itself below it - the run's own data, which is already in the producing
     * node's Output column, and which is the whole point of the panel. The
     * keys inside are names the AUTHOR chose for their own screen variables, not parameter
     * names this product defines, so the word rules have nothing true to say about them:
     * reading them replaced a variable an author called {@code token} - its expression, its
     * resolved shape, its status - with one string, and left the panel with nothing to
     * diagnose on either path.
     *
     * <p>Unwrapped by the gate, so what is persisted is the plain map.
     */
    public record PreGated(Map<String, Object> value) {
    }

    /**
     * A suffix that turns even an ABSOLUTE credential word into a description of one.
     * Narrower than {@link #DESCRIBES_RATHER_THAN_HOLDS} on purpose: {@code
     * signatureAlgorithm} is "RS256" and belongs on the row, while {@code passwordSource}
     * still holds a password.
     */
    private static final Set<String> PURE_DESCRIPTOR_SUFFIXES = Set.of(
        "algorithm", "type", "kind", "mode", "scheme", "format");

    /**
     * A word BEFORE {@code key} or {@code session} that makes it a credential.
     *
     * <p>{@code key} keeps an ALLOW-list where {@code token} has a deny-list, because its
     * benign uses are the open-ended side: {@code sortKey}, {@code partitionKey},
     * {@code objectKey}, {@code primaryKey}, {@code cacheKey}, {@code idempotencyKey} are
     * all structure, and masking them would empty the panel of the values a reader came
     * for. The residual risk is stated rather than hidden: a vendor prefix nobody listed
     * below is REPORTED, because the qualifier is what turns a structural {@code key} into
     * a credential. {@code mashape} and {@code rapidapi} ARE listed, so those two are
     * masked; the next vendor is one word away from being. A credential that reaches the
     * column that way is a missing qualifier here, and that is the whole fix.
     */
    private static final Set<String> CREDENTIAL_QUALIFIERS = Set.of(
        "api", "access", "refresh", "auth", "id", "bearer", "session", "csrf", "xsrf",
        "jwt", "oauth", "sso", "private", "encryption", "signing", "client", "secret",
        "personal", "service", "shared", "consumer", "app", "subscription", "license",
        "master", "account", "server", "deploy", "hmac", "webhook", "bot", "admin",
        "security", "cdp", "publishable", "anon", "application", "developer", "stream",
        "integration", "merchant", "partner", "vendor", "rapidapi", "mashape", "project");

    /**
     * A key ending this way NAMES or REFERENCES a credential rather than holding one, and
     * saying which one is what keeps a masked row diagnosable: {@code apiKeyName=api_key}
     * says which parameter was masked, {@code authType=jwt} which scheme ran, {@code
     * credentialId} which stored credential was chosen. Checked after {@link
     * #CREDENTIAL_KEYS}, so {@code sessionId} is still masked.
     */
    private static final Set<String> DESCRIBES_RATHER_THAN_HOLDS = Set.of(
        "name", "type", "location", "algorithm", "id", "kind", "source", "mode",
        "count", "length", "expiry", "expiresat", "required", "enabled", "timeout",
        "ttl", "used", "remaining", "limit");

    private ReportedParams() {
    }

    /**
     * The map a node should report: credentials masked, oversized values described, at every
     * depth, and the WHOLE map bounded. Returns a new map; the input is not touched.
     *
     * <p>The total budget is the one an entry-by-entry rule cannot give: two thousand
     * parameters of five hundred characters each are individually small and add up to a
     * megabyte on a row that is written per item of every split. Entries are kept until the
     * budget is spent and the rest are DROPPED, with {@link #TRUNCATED} saying how many.
     *
     * <p>WHICH entries survive is the iteration order of the map the caller passes, not a
     * ranking: a {@link LinkedHashMap} (or a Jackson-parsed params map, which is one) keeps the
     * order the node built, a {@link java.util.HashMap} keeps hash order. A node whose map can
     * overflow should therefore build it in a LinkedHashMap and put its most telling keys first.
     * Passing a HashMap is not an error and nothing rejects it; it just makes the cut arbitrary.
     */
    public static Map<String, Object> forReport(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return params == null ? new LinkedHashMap<>() : new LinkedHashMap<>(params);
        }
        Map<String, Object> redacted = redactMap(params, 0);

        Map<String, Object> reported = new LinkedHashMap<>();
        long total = 0;
        int dropped = 0;
        for (Map.Entry<String, Object> entry : redacted.entrySet()) {
            if (dropped > 0) {
                dropped++;
                continue;
            }
            total += entry.getKey().length() + weigh(entry.getValue());
            if (total > MAX_TOTAL_WEIGHT) {
                // DROPPED, not described: two thousand descriptions are three hundred
                // kilobytes of their own, so a per-entry rule cannot bound a map by itself.
                // The entries that fit are the ones a reader looks at first; the rest are
                // counted, because a silent cut is worse than a short map.
                dropped = 1;
                continue;
            }
            reported.put(entry.getKey(), entry.getValue());
        }
        if (dropped > 0) {
            if (redacted.containsKey(TRUNCATED)) {
                // A parameter the author happens to have named `paramsTruncated`. The
                // marker wins - a silent cut is the worse outcome - and the cost is stated
                // rather than hidden, as AggregateNode does for its own key collision.
                logger.warn("A reported parameter is named '{}' and is overwritten by the "
                    + "truncation marker; its value is not shown", TRUNCATED);
            }
            reported.put(TRUNCATED, dropped + " more parameter(s) not shown (over "
                + MAX_TOTAL_WEIGHT + " chars)");
        }
        return reported;
    }

    /**
     * The map budget, WITHOUT the key-name rules. For a map whose keys are the author's own
     * labels and whose values cannot be credentials by construction.
     *
     * <p>The two halves of {@link #forReport} are separable and one node needs only one of
     * them. An aggregate reports {@code {<author label>: <configured expression>}}: a
     * {@code {{...}}} template is not a credential whatever the field is called, so reading
     * those labels with the word rules produces only false positives - and a false positive
     * is not cosmetic here, because {@code StepOutputService} publishes every reported key as
     * {@code input.<key>}, so a masked label hands the literal marker string to whatever
     * downstream node reads it.
     *
     * <p>But dropping the masking must not drop the BOUND, which is what happened when the
     * aggregate's two producers were unified: the split path had been going through
     * {@code forReport} and came out with neither. Two hundred fields of a two-thousand
     * character expression is four hundred kilobytes on the row of every item of every split,
     * and without this there is no {@code paramsTruncated} to say it was cut.
     */
    public static Map<String, Object> boundedWithoutMasking(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return params == null ? new LinkedHashMap<>() : new LinkedHashMap<>(params);
        }
        Map<String, Object> reported = new LinkedHashMap<>();
        long total = 0;
        int dropped = 0;
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            if (dropped > 0) {
                dropped++;
                continue;
            }
            Object item = value(entry.getValue());
            total += entry.getKey().length() + weigh(item);
            if (total > MAX_TOTAL_WEIGHT) {
                dropped = 1;
                continue;
            }
            reported.put(entry.getKey(), item);
        }
        if (dropped > 0) {
            reported.put(TRUNCATED, dropped + " more parameter(s) not shown (over "
                + MAX_TOTAL_WEIGHT + " chars)");
        }
        return reported;
    }

    /**
     * One value, bounded. Keeps it as it is - typed - under the inline budget, and describes
     * its shape above it.
     *
     * <p>Bounds only. For a value that can CONTAIN a credential-named field - an upstream
     * row, a list of records - use {@link #reportValue}, which masks as it walks.
     */
    public static Object value(Object value) {
        if (value == null || value instanceof Boolean || value instanceof Number) {
            return value;
        }
        return weigh(value) > MAX_INLINE_WEIGHT ? ResolvedValuePreview.describe(value) : value;
    }

    /**
     * One value, bounded AND masked at every depth.
     *
     * <p>What a node should report for a value that came from somewhere else: the upstream
     * collection a filter filtered, the rows a sort sorted. Those carry whatever the
     * producer put in them, including a field the author called {@code password}, and
     * bounding alone does not stop a small one from being published.
     */
    public static Object reportValue(Object value) {
        return redactValue(value, 0);
    }

    /** What a value pulled from a workspace variable reads as. */
    public static final String WITHHELD_WORKSPACE_VARIABLE = "<withheld: workspace variable>";

    /**
     * Whether an expression references a WORKSPACE variable, asked of the engine's own
     * normalizer rather than modelled here: it knows both author forms ({@code $vars.name},
     * {@code vars:name}) and it leaves an occurrence inside a string literal alone, which is
     * data rather than a reference.
     *
     * <p>A workspace variable can be declared secret, and a declared secret's whole point is
     * that it is never displayed. Everything else an expression can address is the run's own
     * data, already persisted and already shown in the Output column.
     */
    public static boolean referencesWorkspaceVariable(String expression) {
        return expression != null
            && !expression.equals(VarsSyntaxNormalizer.normalize(expression));
    }

    /**
     * One reported value, given the expression that produced it: withheld when that
     * expression pulls a workspace variable and the value is a scalar, masked and bounded
     * otherwise.
     *
     * <p>The SHAPE of a collection or an object is still reported even for a workspace
     * variable: a size and a set of field names carry no secret, and they are what a reader
     * diagnoses with.
     *
     * <p>The non-workspace path goes through {@link #reportValue}, not {@link #value}: what
     * an expression produced is upstream data, and an upstream row carries whatever its
     * producer put in it, including a column the author called {@code password}. Bounding
     * alone let a small one straight through, on the three nodes whose reported value is
     * built here and never passed to {@link #forReport}.
     */
    public static Object valueFrom(String expression, Object value) {
        if (value == null || !referencesWorkspaceVariable(expression)) {
            return reportValue(value);
        }
        // A scalar is withheld outright. Anything structured reports its SHAPE - a size, a
        // set of field names - and never its contents: a workspace variable holding an
        // object of credentials is as secret as one holding a string, and returning the
        // object as it is (which this did) published every value in it.
        return withholdsWorkspaceScalar(expression, value)
            ? WITHHELD_WORKSPACE_VARIABLE
            : ResolvedValuePreview.describe(value);
    }

    /**
     * Whether a value must be WITHHELD because an author's expression pulled it from a
     * workspace variable and it is a scalar.
     *
     * <p>The one rule, in one place. {@link #valueFrom} applies it to a reported value and
     * {@code InterfaceNode.describeResolved} applies it to a variable's resolution, and the
     * two differ in what they do with everything ELSE (one reports the value under the
     * budget, the other always describes) - which is exactly why the shared half has to be
     * shared rather than written twice: two copies of a masking rule diverge silently, and
     * only one of the two directions is visible to whoever breaks it.
     */
    public static boolean withholdsWorkspaceScalar(String expression, Object value) {
        if (value == null || !referencesWorkspaceVariable(expression)) {
            return false;
        }
        return !(value instanceof Collection<?>)
            && !(value instanceof Map<?, ?>)
            && !value.getClass().isArray();
    }

    /**
     * Whether a key's NAME says it HOLDS a credential.
     *
     * <p>Read in five steps, IN THIS ORDER, because each one exists to stop the previous
     * one from going too far:
     *
     * <ol>
     *   <li>a whole-key counter-example the words cannot see ({@code tokens} is a usage
     *       figure, {@code key} is the name of a map entry);</li>
     *   <li>the key IS a credential, whole ({@code token}, {@code sessionId});</li>
     *   <li>a word that only ever means a credential ({@code password}, {@code secret}) -
     *       BEFORE the exemption below, so {@code passwordSource} stays masked, and after a
     *       PURE descriptor suffix, so {@code signatureAlgorithm} ("RS256") does not;</li>
     *   <li>the key describes or references one ({@code apiKeyName}, {@code authType},
     *       {@code credentialId}, {@code sessionTimeout}) - never masked, because a masked
     *       row that cannot say WHICH value was masked is a row that says nothing;</li>
     *   <li>a word that means one only when qualified: {@code token} masked unless a
     *       quantity or cursor word precedes it, {@code key} and {@code session} masked
     *       only when a credential word does.</li>
     * </ol>
     *
     * <p>Matching by substring instead of by WORD is what put {@code maxTokens} behind a
     * mask on every agent node, so the segmentation is the rule here, not an optimisation.
     */
    public static boolean isCredentialKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        List<String> segments = segments(key);
        if (segments.isEmpty()) {
            return false;
        }
        String whole = String.join("", segments);

        // 1. The whole key is a counter-example the word rules cannot see.
        String rawWhole = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (NEVER_CREDENTIAL_KEYS.contains(rawWhole)) {
            return false;
        }
        // 2. The key IS one, whole. `key` is in that set for URL query names only, so it is
        //    excluded here: a map entry called `key` is a name, not a credential.
        if (CREDENTIAL_KEYS.contains(whole) && !"key".equals(whole)) {
            return true;
        }
        String last = segments.get(segments.size() - 1);
        // 3. A word that never means anything else, BEFORE the broad exemption:
        //    `passwordSource` holds a password whatever the suffix says. The exception is a
        //    PURE descriptor - `signatureAlgorithm` is "RS256", not a signature.
        if (!PURE_DESCRIPTOR_SUFFIXES.contains(last)) {
            for (String segment : segments) {
                if (ABSOLUTE_CREDENTIAL_WORDS.contains(segment)) {
                    return true;
                }
            }
        }
        // 3b. A SESSION ID, at any position. `sessionId` is in the whole-key set at step 2,
        //     but a qualified one - `browserSessionId`, `userSessionId` - reaches step 4,
        //     whose `id` suffix answers "this references a credential" and returns false. For
        //     a session that is wrong: the id IS the session, which is why `sessionId` needed
        //     a whole-key entry in the first place. `apiKeyId` and `credentialId` genuinely do
        //     reference a stored secret, so the rule is `session` immediately followed by
        //     `id` and nothing wider. BrowserAgentNode saves exactly this.
        for (int i = 0; i + 1 < segments.size(); i++) {
            //     `ids` as well as `id`: segments() only singularises words longer than 3,
            //     so a plural `sessionIds` kept its `s` and slipped past.
            if ("session".equals(segments.get(i))
                    && ("id".equals(segments.get(i + 1)) || "ids".equals(segments.get(i + 1)))) {
                return true;
            }
        }
        // 4. The key describes or references one.
        if (DESCRIBES_RATHER_THAN_HOLDS.contains(last)) {
            return false;
        }
        // 5. A word that means one unless qualified otherwise. Scanned at EVERY position,
        //    not only the last: `apiKeyValue`, `tokenValue` and `privateKeyPem` all bury it
        //    one word from the end.
        for (int i = 0; i < segments.size(); i++) {
            String segment = segments.get(i);
            String previous = i > 0 ? segments.get(i - 1) : null;
            if ("token".equals(segment)) {
                if (previous == null || !QUANTITY_QUALIFIERS.contains(previous)) {
                    return true;
                }
            } else if (("key".equals(segment) || "session".equals(segment))
                    && previous != null && CREDENTIAL_QUALIFIERS.contains(previous)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a URL QUERY PARAMETER's name carries a credential.
     *
     * <p>Wider than {@link #isCredentialKey} on purpose, and only here: inside a query
     * string the bare names are the credential ones. {@code ?key=} is every Google API,
     * {@code ?sig=} is an Azure SAS signature, {@code ?auth=} is a Firebase token - while
     * in a configuration MAP those same three words are a map entry's name, a signature
     * algorithm and an auth scheme. One predicate cannot serve both contexts.
     */
    public static boolean isCredentialQueryParam(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String whole = String.join("", segments(name));
        return CREDENTIAL_KEYS.contains(whole) || isCredentialKey(name);
    }

    /**
     * A key's words, lower-cased and singularised: {@code jwt_secret_key},
     * {@code jwtSecretKey} and {@code JWT-SECRET-KEY} all read as [jwt, secret, key], and
     * {@code maxTokens} as [max, token].
     */
    private static List<String> segments(String key) {
        String spaced = key
            .replaceAll("([a-z0-9])([A-Z])", "$1 $2")
            .replaceAll("[^A-Za-z0-9]+", " ")
            .toLowerCase(Locale.ROOT)
            .trim();
        List<String> segments = new ArrayList<>();
        for (String word : spaced.split("\\s+")) {
            // Trailing digits dropped first: `apiKey2` and `password1` are the same words
            // with a counter on the end, and a counter must not walk a key past the rule.
            String stripped = word.replaceAll("\\d+$", "");
            if (stripped.isEmpty()) {
                continue;
            }
            // Singularise so `tokens` and `token`, `credentials` and `credential` are one
            // word. Only the trailing s, and never down to a single letter.
            segments.add(stripped.length() > 3 && stripped.endsWith("s") && !stripped.endsWith("ss")
                ? stripped.substring(0, stripped.length() - 1)
                : stripped);
        }
        return segments;
    }

    /**
     * Masks the VALUE of every credential-named query parameter in a url, keeping the url
     * itself readable.
     *
     * <p>A url is the one string that routinely carries a credential inside it: a signed
     * download link, an {@code ?api_key=} the engine appended, a {@code ?token=} the author
     * typed. Reporting it whole published the credential; reporting it not at all would
     * remove the single most diagnostic value an HTTP node has.
     */
    public static String maskUrlSecrets(String url) {
        if (url == null || url.isBlank()) {
            return url;
        }
        int queryStart = url.indexOf('?');
        if (queryStart < 0 || queryStart == url.length() - 1) {
            return url;
        }
        String head = url.substring(0, queryStart + 1);
        String query = url.substring(queryStart + 1);
        String fragment = "";
        int hash = query.indexOf('#');
        if (hash >= 0) {
            fragment = query.substring(hash);
            query = query.substring(0, hash);
        }
        StringBuilder masked = new StringBuilder(head);
        String[] pairs = query.split("&", -1);
        for (int i = 0; i < pairs.length; i++) {
            if (i > 0) {
                masked.append('&');
            }
            String pair = pairs[i];
            int eq = pair.indexOf('=');
            if (eq > 0 && isCredentialQueryParam(pair.substring(0, eq))) {
                masked.append(pair, 0, eq + 1).append(WITHHELD_CREDENTIAL);
            } else {
                masked.append(pair);
            }
        }
        return masked.append(fragment).toString();
    }

    private static Map<String, Object> redactMap(Map<?, ?> params, int depth) {
        Map<String, Object> reported = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : params.entrySet()) {
            // String.valueOf rather than a cast: this runs on the SUCCESS path of a node
            // that has already done its work, and a map with a non-String key would turn a
            // completed tool call into a failed one. A reporting gate must not be able to
            // fail the thing it reports on.
            String key = String.valueOf(entry.getKey());
            if (entry.getValue() instanceof PreGated preGated) {
                // Bounded, never masked by name, at any depth: only a node that holds the
                // wrapper type can put one here, so there is no name for a caller to guess.
                // See PreGated for why reading those keys emptied the panel it feeds.
                reported.put(key, boundPreGated(preGated.value()));
                continue;
            }
            if (isCredentialKey(key)) {
                reported.put(key, WITHHELD_CREDENTIAL);
                continue;
            }
            reported.put(key, redactValue(entry.getValue(), depth));
        }
        return reported;
    }

    /**
     * Bounds a pre-gated value ENTRY BY ENTRY, and then the whole of it.
     *
     * <p>Two levels, because neither alone is enough. Bounding only the whole map would
     * replace a thirty-variable mapping with {@code Map(keys=[...])} the moment it passed
     * the inline budget, which is an ordinary size for an interface and exactly the reader
     * this report is written for. Bounding only each entry does not bound the map: four
     * hundred variables of two hundred characters are individually small and were eighty
     * kilobytes on the row, and the outer budget in {@link #forReport} cannot catch it
     * because {@link #weigh} stops counting at the inline budget.
     *
     * <p>So: every entry is bounded, entries are kept until {@link #PRE_GATED_BUDGET} is
     * spent, and the rest are dropped under the same {@link #TRUNCATED} key the whole map
     * uses - same word, same meaning, one level down.
     */
    private static Object boundPreGated(Object preGated) {
        if (!(preGated instanceof Map<?, ?> map)) {
            return value(preGated);
        }
        Map<String, Object> bounded = new LinkedHashMap<>();
        long total = 0;
        int dropped = 0;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (dropped > 0) {
                dropped++;
                continue;
            }
            String key = String.valueOf(entry.getKey());
            Object item = withheldResolvedLeaf(key, value(entry.getValue()));
            total += key.length() + weigh(item);
            if (total > PRE_GATED_BUDGET) {
                dropped = 1;
                continue;
            }
            bounded.put(key, item);
        }
        if (dropped > 0) {
            bounded.put(TRUNCATED, dropped + " more not shown (over " + PRE_GATED_BUDGET + " chars)");
        }
        return bounded;
    }

    /**
     * Masks the {@code resolved} leaf of a pre-gated entry whose AUTHOR name says credential,
     * and nothing else about it.
     *
     * <p>The exemption above exists so the words rules do not read author labels, and that is
     * right for the entry as a whole: the {@code expression} and the {@code status} are the
     * diagnosis, and replacing the whole record with one string is what emptied the panel.
     * But {@code resolved} is not configuration, it is UPSTREAM DATA - the producer describes
     * it, and {@link ResolvedValuePreview} returns a short string VERBATIM up to its scalar
     * cap. An interface variable an author called {@code token}, wired to
     * {@code {{mcp:auth.output.access_token}}}, therefore put the first 120 characters of that
     * token on the row of every epoch; a {@code ghp_} PAT and most {@code sk_live_} keys fit
     * entirely inside that. Only a {@code $vars} expression was withheld.
     *
     * <p>So: the one leaf that can hold a value is masked when the author's own name says what
     * it holds, and the two that carry the diagnosis are untouched. This costs the panel
     * nothing it is opened for - a variable named {@code token} still shows its wiring and
     * whether it resolved - which is why it is not the whole-entry mask the exemption removed.
     */
    private static Object withheldResolvedLeaf(String key, Object entryValue) {
        if (!(entryValue instanceof Map<?, ?> record) || !isCredentialKey(key)) {
            return entryValue;
        }
        Map<String, Object> masked = new LinkedHashMap<>();
        for (Map.Entry<?, ?> field : record.entrySet()) {
            String name = String.valueOf(field.getKey());
            // Not over a marker the producer already wrote: a variable named `token` AND fed
            // from {{$vars.x}} is already withheld, and the workspace marker is the more
            // precise of the two answers - it says WHY the value is absent.
            boolean alreadyWithheld = WITHHELD_WORKSPACE_VARIABLE.equals(field.getValue())
                || WITHHELD_CREDENTIAL.equals(field.getValue());
            masked.put(name, "resolved".equals(name) && field.getValue() != null && !alreadyWithheld
                ? WITHHELD_CREDENTIAL
                : field.getValue());
        }
        return masked;
    }

    private static Object redactValue(Object value, int depth) {
        if (value == null) {
            // A null parameter is a REPORTABLE fact - `data_input` reports an item whose
            // file resolved to nothing, and that is what the panel is read for - so it is
            // returned as it is. Guarded here rather than in each branch below because the
            // array walk dereferences the value to read its type: adding that walk turned
            // every null parameter into a NullPointerException inside the gate, which is
            // the one thing a reporting gate must never do to the node it reports on.
            return null;
        }
        if (value instanceof PreGated preGated) {
            // Nested rather than at the top of the map. No producer does this today; the
            // branch exists so that if one ever does, the wrapper is unwrapped rather than
            // serialised as `{"value": {...}}` onto the row.
            return boundPreGated(preGated.value());
        }
        if (depth >= MAX_DEPTH) {
            // Deeper than any node's configuration goes. Describing rather than copying,
            // because a structure this deep cannot be walked for credentials any more.
            return ResolvedValuePreview.describe(value);
        }
        if (value instanceof Map<?, ?> map) {
            // Weighed like everything else. Walking a map to mask it is not the same as
            // agreeing to copy it: a JSON request body or a whole trigger payload arrives
            // here as a Map, and an object was the one shape this gate still let through
            // at any size.
            if (weigh(map) > MAX_INLINE_WEIGHT) {
                return ResolvedValuePreview.describe(map);
            }
            return redactMap(map, depth + 1);
        }
        if (value instanceof Collection<?> collection) {
            return redactItems(collection, collection.size(), depth);
        }
        if (value.getClass().isArray()) {
            // Walked like a Collection, and for the same reason. An array fell through to
            // the size-only bound, so an Object[] of fifty rows was RETURNED AS ITSELF -
            // unwalked, unmasked and uncopied - and both of this class's invariants failed
            // for the one type nobody thought to check. `weigh` and `describe` both handle
            // arrays; only the walk did not.
            int length = java.lang.reflect.Array.getLength(value);
            List<Object> items = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                items.add(java.lang.reflect.Array.get(value, i));
            }
            return redactItems(items, length, depth);
        }
        return value(value);
    }

    private static Object redactItems(Iterable<?> items, int size, int depth) {
        if (size > MAX_ITEMS || weigh(items) > MAX_INLINE_WEIGHT) {
            return ResolvedValuePreview.describe(items);
        }
        List<Object> redacted = new ArrayList<>(size);
        for (Object item : items) {
            redacted.add(redactValue(item, depth + 1));
        }
        return value(redacted);
    }

    /**
     * Size estimate: characters for text, and the contents of a collection or map. Stops the
     * moment the running total passes the inline budget, at every depth - this runs on every
     * reported value of every step row, and an estimator that costs as much as the copy it
     * prevents is no saving.
     */
    private static long weigh(Object value) {
        return weigh(value, 0);
    }

    /**
     * Recursive, with the same early exit at every level.
     *
     * <p>It used to charge a nested container a flat {@code 16 + size * 32} whatever it
     * held, and that is not an estimate, it is a blind spot: two hundred entries each
     * {@code {data:{attributes:{html: <1 900 chars>}}}} - an ordinary wrapper shape - each
     * measured about eighty and were each about two thousand. The map budget summed
     * sixteen thousand, passed, and wrote 383 KB to the step row of every item of every
     * split, with no {@code paramsTruncated} to tell the reader. Recursing costs nothing
     * extra in the case the estimate exists for: the walk stops the moment the running
     * total passes the inline budget, at every depth.
     */
    private static long weigh(Object value, int depth) {
        if (value == null) {
            return 8;
        }
        if (value instanceof CharSequence text) {
            return text.length();
        }
        if (depth >= MAX_DEPTH) {
            // Past the depth the gate itself walks: a structure this deep is described, not
            // copied, so a shallow estimate of it is the honest one.
            return shallowWeigh(value);
        }
        if (value instanceof Iterable<?> items) {
            long total = 16;
            for (Object item : items) {
                total += weigh(item, depth + 1);
                if (total > MAX_INLINE_WEIGHT) {
                    return total;
                }
            }
            return total;
        }
        if (value instanceof Map<?, ?> map) {
            long total = 16;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                total += weigh(entry.getKey(), depth + 1) + weigh(entry.getValue(), depth + 1);
                if (total > MAX_INLINE_WEIGHT) {
                    return total;
                }
            }
            return total;
        }
        if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            long total = 16;
            for (int i = 0; i < length; i++) {
                total += weigh(java.lang.reflect.Array.get(value, i), depth + 1);
                if (total > MAX_INLINE_WEIGHT) {
                    return total;
                }
            }
            return total;
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof Character
                || value instanceof Enum<?>) {
            // Small by construction, and the common case: no point rendering them to measure.
            return 32;
        }
        // Any other type - a FileRef record, a Jackson TextNode, a date. Measured by what it
        // would actually WRITE rather than a flat 32, because the flat figure let a value
        // whose toString is large through both budgets: a TextNode holding a megabyte is
        // neither CharSequence nor Iterable nor Map, so it weighed 32 and was copied whole.
        return Math.min(String.valueOf(value).length(), MAX_INLINE_WEIGHT + 1L);
    }

    private static long shallowWeigh(Object value) {
        if (value instanceof CharSequence text) {
            return text.length();
        }
        if (value instanceof Collection<?> collection) {
            return 16L + (long) collection.size() * 32;
        }
        if (value instanceof Map<?, ?> map) {
            return 16L + (long) map.size() * 32;
        }
        return 32;
    }
}
