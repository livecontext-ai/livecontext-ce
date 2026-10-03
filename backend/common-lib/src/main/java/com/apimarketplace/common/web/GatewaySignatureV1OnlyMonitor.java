package com.apimarketplace.common.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

/**
 * Makes "a caller still signs v1 only" observable, so {@code gateway.signature.accept-v1} can be
 * turned off on evidence (CASA LC-035).
 *
 * <p>While accept-v1 is true, {@link GatewayAuthenticationFilter} accepts a request that carries
 * NO {@code X-Gateway-Signature-V2} header silently: nothing in the logs distinguishes it from a
 * v2 request. The absence of the "Gateway v2 signature mismatch" WARN therefore proves nothing
 * about v1-only callers; they are exactly the ones that never send a v2 header to mismatch. This
 * monitor is told about every such request once its v1 signature has VERIFIED, and:
 * <ul>
 *   <li>increments the Micrometer counter {@value #METRIC} (Prometheus
 *       {@code gateway_signature_v1_only_total}) tagged {@code path} (the route prefix, see
 *       {@link #route}) and {@code caller} (the {@code X-Provider-ID} when it is a fixed service
 *       name, {@code other} otherwise), once a registry is bound by the auto-configuration. At
 *       most {@value #MAX_SERIES} series are created; every later new pair is counted in the
 *       single {@code path="other",caller="other"} series, and handles are cached per series;</li>
 *   <li>logs ONE WARN per (path, caller) tag pair per process, capped at {@value #MAX_LOGGED_KEYS}
 *       distinct pairs (one further line says the cap was reached), never one line per request.</li>
 * </ul>
 * It changes no decision: it is called after the request is already accepted.
 *
 * <p>Cardinality is bounded because only a request whose v1 HMAC verified reaches it, i.e. a
 * holder of the gateway secret, and both tags are reduced to a closed vocabulary.
 */
public final class GatewaySignatureV1OnlyMonitor {

    private static final Logger log = LoggerFactory.getLogger(GatewaySignatureV1OnlyMonitor.class);

    /** Micrometer meter name; Prometheus exposes it as {@code gateway_signature_v1_only_total}. */
    public static final String METRIC = "gateway.signature.v1.only";

    /** Distinct (path, caller) tag pairs logged per process before logging stops. */
    static final int MAX_LOGGED_KEYS = 256;

    /** Tag value for a provider id that is not a fixed service name (a user id, a token). */
    static final String OTHER_CALLER = "other";

    /** A fixed internal provider id: lowercase words joined by '-', no digits (so no UUID, no user id). */
    private static final Pattern SERVICE_CALLER = Pattern.compile("[a-z]+(-[a-z]+)*");

    /** (path, caller) pairs already logged; read lock-free, written only under {@link #logLock}. */
    private final Set<String> logged = ConcurrentHashMap.newKeySet();

    /** Makes "not yet logged, and still under the cap" one atomic decision. */
    private final Object logLock = new Object();

    /**
     * Set once, under {@link #logLock}, when the cap line has been written. Volatile so that, from
     * then on, a never-logged pair is dropped without taking the lock.
     */
    private volatile boolean capLogged;

    private enum LogSlot { FIRST, CAP_REACHED, SILENT }

    /**
     * Claim the right to log {@code key}: FIRST for exactly one caller per new key while under
     * the cap, CAP_REACHED for exactly one caller once the cap is hit, SILENT otherwise. Only
     * reached for a key not yet logged, so after warm-up the hot path never takes the lock.
     */
    private LogSlot claimLogSlot(String key) {
        synchronized (logLock) {
            if (logged.contains(key)) {
                return LogSlot.SILENT;
            }
            if (logged.size() < MAX_LOGGED_KEYS) {
                logged.add(key);
                return LogSlot.FIRST;
            }
            if (!capLogged) {
                capLogged = true;
                return LogSlot.CAP_REACHED;
            }
            return LogSlot.SILENT;
        }
    }

    /** Distinct (path, caller) metric series before new pairs fold into {@link #OVERFLOW_TAG}. */
    static final int MAX_SERIES = 256;

    /** path AND caller tag of the single series every pair past {@link #MAX_SERIES} is counted in. */
    static final String OVERFLOW_TAG = "other";

    private static final String OVERFLOW_KEY = OVERFLOW_TAG + " " + OVERFLOW_TAG;

    private static final Runnable NO_OP = () -> { };

    /**
     * Makes a counter handle for one (path tag, caller tag) series. Called once per series, never
     * per request; a no-op until the auto-configuration binds a registry.
     */
    private volatile BiFunction<String, String, Runnable> counterFactory = (path, caller) -> NO_OP;

    /**
     * Counter handle per series key ("path caller"), at most {@link #MAX_SERIES} admitted keys plus
     * the overflow key, so neither the registry nor this map can grow without bound.
     */
    private final ConcurrentHashMap<String, Runnable> counters = new ConcurrentHashMap<>();

    /** Guards admission into {@link #counters} so the bound is exact under concurrency. */
    private final Object counterLock = new Object();

    /**
     * The overflow series' handle, published (under {@link #counterLock}) once the series are
     * full. Non-null means "full": a pair with no series of its own then goes straight to it,
     * without the lock.
     */
    private volatile Runnable overflowHandle;

    /**
     * Count every accepted v1-only request through handles made by {@code factory}, one handle per
     * (path, caller) series, cached. Rebinding drops the handles made by the previous factory.
     */
    public void bindCounter(BiFunction<String, String, Runnable> factory) {
        synchronized (counterLock) {
            this.counterFactory = factory == null ? (path, caller) -> NO_OP : factory;
            counters.clear();
            overflowHandle = null;
        }
    }

    /** The cached handle of this series, or of the overflow series once {@link #MAX_SERIES} is reached. */
    private Runnable counterFor(String route, String caller) {
        String key = route + ' ' + caller;
        Runnable cached = counters.get(key);
        if (cached != null) {
            return cached;
        }
        Runnable overflow = overflowHandle;
        if (overflow != null) {
            return overflow;
        }
        synchronized (counterLock) {
            cached = counters.get(key);
            if (cached != null) {
                return cached;
            }
            if (overflowHandle == null && counters.size() < MAX_SERIES) {
                Runnable handle = counterFactory.apply(route, caller);
                counters.put(key, handle);
                return handle;
            }
            if (overflowHandle == null) {
                Runnable handle = counterFactory.apply(OVERFLOW_TAG, OVERFLOW_TAG);
                counters.put(OVERFLOW_KEY, handle);
                overflowHandle = handle;
            }
            return overflowHandle;
        }
    }

    /**
     * Record one request that authenticated with v1 and carried no v2 header. Never throws: a
     * metrics or logging failure must not fail a request that is already accepted.
     */
    public void record(String method, String path, String providerId) {
        try {
            // Masked first: a capability token in the path must reach neither a tag nor a log line.
            String safePath = LogSafePath.of(path);
            String route = route(safePath);
            String caller = callerTag(providerId);
            counterFor(route, caller).run();
            // Keyed on the two BOUNDED tags, never the raw provider id: id-like providers all fold
            // into "other", so they cannot use up the slots a new service caller needs to be seen.
            String key = route + ' ' + caller;
            // Lock-free for a pair already logged, and for any new pair once the cap line is out.
            if (logged.contains(key) || capLogged) {
                return;
            }
            // Only the bounded caller tag is logged: on v1 the provider id can come from the
            // providerId query parameter and be a user id.
            switch (claimLogSlot(key)) {
                case FIRST -> log.warn("Gateway v1-only signature accepted: {} {} carries no {} and "
                                + "will be refused once gateway.signature.accept-v1=false (metric "
                                + "gateway_signature_v1_only_total, path={}, caller={}; logged once per path and caller)",
                        method, safePath, GatewaySignatureV2.HEADER, route, caller);
                case CAP_REACHED -> log.warn("Gateway v1-only signature: {} distinct (path, caller) pairs already logged, "
                                + "further new pairs are only counted in gateway_signature_v1_only_total "
                                + "(first unlogged: path={}, caller={})",
                        MAX_LOGGED_KEYS, route, caller);
                case SILENT -> { }
            }
        } catch (RuntimeException e) {
            log.debug("Could not record a v1-only gateway request: {}", e.getMessage());
        }
    }

    /**
     * The route prefix a v1-only request is counted under: its first two path segments, three
     * under {@code /api/internal} (where the second segment names nothing). Ids and tokens sit
     * deeper than that on every route, so the tag stays a short, closed list.
     */
    static String route(String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }
        String[] segments = path.split("/");
        StringBuilder out = new StringBuilder();
        int kept = 0;
        int wanted = 2;
        for (String segment : segments) {
            if (segment.isEmpty()) {
                continue;
            }
            out.append('/').append(segment);
            kept++;
            if (kept == 2 && "internal".equals(segment)) {
                wanted = 3;
            }
            if (kept >= wanted) {
                break;
            }
        }
        return out.length() == 0 ? "/" : out.toString();
    }

    static String callerTag(String providerId) {
        if (providerId != null && providerId.length() <= 64 && SERVICE_CALLER.matcher(providerId).matches()) {
            return providerId;
        }
        return OTHER_CALLER;
    }
}
