package com.apimarketplace.publication.service;

import com.apimarketplace.common.storage.signing.ShowcaseUrlSigner;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Walks the {@code items[*].data} subtree of a showcase render payload and
 * replaces every FileRef object ({@code {_type:'file', path, name, mimeType,
 * size}}) with a short-lived HMAC-signed URL pointing to the storage-service
 * {@code /api/files/proxy-signed} endpoint. Anonymous marketplace visitors
 * thus see {@code <img src="/api/files/proxy-signed?key=…&exp=…&sig=…">} and
 * the gateway lets the request through (path is in {@code PUBLIC_ENDPOINTS});
 * storage-service then verifies the HMAC and streams the file.
 *
 * <p>Why HMAC and not S3 presigning: the MinIO presigner emits URLs that
 * embed the configured S3 endpoint (an internal address such as {@code http://minio:9000}),
 * which is on the internal VPN and unreachable from a browser. A signed proxy
 * URL goes through Caddy → gateway → storage-service like any other request.
 *
 * <p>Scope is intentionally narrow: only walks {@code items[*].data}, not
 * {@code items[*].triggerData} (which can carry FileRefs uploaded by an
 * acquirer of a different tenant - never expose those publicly) and not the
 * snapshot's {@code runState} subtree (steps' {@code output} maps may carry
 * paths that are not part of the publicly rendered interface).
 *
 * <p>The rewriter refuses to sign keys that don't begin with
 * {@code pub.publisherId + "/"} (defense in depth: even a corrupted snapshot
 * with a foreign-tenant path won't leak). Failures to sign leave the FileRef
 * untouched (rendered as JSON, broken image) rather than aborting the whole
 * render.
 */
@Service
public class ShowcaseFileRefRewriter {

    private static final Logger log = LoggerFactory.getLogger(ShowcaseFileRefRewriter.class);
    private static final String SIGNED_DISPOSITION = "inline";

    private final ShowcaseUrlSigner signer;
    private final ObjectMapper objectMapper;
    private final int presignExpiryMinutes;
    private final Counter presignOkCounter;
    private final Counter presignFailCounter;
    private final Counter presignSkippedForeignKeyCounter;

    // Default expiry is 4 hours (240 minutes) - long enough to cover a typical
    // marketplace browsing session (the previous 15 min default broke images
    // whenever a viewer left the tab open for more than a quarter-hour) while
    // still expiring URLs so a leaked link does not grant indefinite access.
    // The HMAC is bound to the exact key + expiry; leakage of any single signed
    // URL is per-file and time-boxed. Operators can override via
    // `publication.showcase.presign-expiry-minutes` if a stricter or looser
    // window is required.
    public ShowcaseFileRefRewriter(
            ShowcaseUrlSigner signer,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            @Value("${publication.showcase.presign-expiry-minutes:240}") int presignExpiryMinutes) {
        this.signer = signer;
        this.objectMapper = objectMapper;
        this.presignExpiryMinutes = presignExpiryMinutes;
        this.presignOkCounter = Counter.builder("publication_showcase_presign_total")
                .description("Showcase FileRef → signed URL conversions, by outcome")
                .tag("status", "ok")
                .register(meterRegistry);
        this.presignFailCounter = Counter.builder("publication_showcase_presign_total")
                .description("Showcase FileRef → signed URL conversions, by outcome")
                .tag("status", "fail")
                .register(meterRegistry);
        this.presignSkippedForeignKeyCounter = Counter.builder("publication_showcase_presign_total")
                .description("Showcase FileRef → signed URL conversions, by outcome")
                .tag("status", "skipped_foreign_key")
                .register(meterRegistry);
    }

    /**
     * Rewrite FileRefs in {@code items[*].data} of the given render payload.
     * Returns a new {@code items} list with rewritten data maps; the input is
     * not mutated.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> rewriteItems(List<Map<String, Object>> items,
                                                    WorkflowPublicationEntity pub) {
        if (items == null || items.isEmpty()) return items;
        if (!signer.isEnabled()) {
            // No HMAC secret in this environment → leave FileRefs raw. The
            // signer already logs a WARN at startup; the marketplace card just
            // won't render images until the operator wires the secret.
            return items;
        }
        String publisherId = pub.getPublisherId();
        if (publisherId == null || publisherId.isEmpty()) {
            log.warn("[FileRefRewriter] publication {} has no publisherId, skipping rewrite", pub.getId());
            return items;
        }
        String publicationNamespace = "_publications/" + pub.getId() + "/";
        List<Map<String, Object>> rewritten = new ArrayList<>(items.size());
        for (Map<String, Object> item : items) {
            if (item == null) {
                rewritten.add(null);
                continue;
            }
            Map<String, Object> copy = new LinkedHashMap<>(item);
            Object data = copy.get("data");
            if (data instanceof Map<?, ?> dataMap) {
                copy.put("data", rewriteValue(dataMap, publisherId, publicationNamespace));
            }
            rewritten.add(copy);
        }
        return rewritten;
    }

    /**
     * Rewrite FileRefs in a single landing-snapshot payload (returned by
     * {@code GET /by-id/{pubId}/landing-snapshot} for INTERFACE/AGENT/WORKFLOW
     * publications). The landing map carries htmlTemplate / cssTemplate /
     * jsTemplate as strings (no FileRefs) and a {@code data} sub-map that CAN
     * carry FileRefs the interface template references via variable_mapping.
     * Without this rewrite, anonymous marketplace visitors see raw FileRef Maps
     * serialized as JSON inside {@code <img src=...>}, producing broken images.
     *
     * <p>Returns a new map with FileRefs in {@code data} replaced by
     * HMAC-signed URLs; non-FileRef fields are preserved.
     */
    public Map<String, Object> rewriteLanding(Map<String, Object> landing,
                                               WorkflowPublicationEntity pub) {
        if (landing == null || landing.isEmpty()) return landing;
        if (!signer.isEnabled()) return landing;
        String publisherId = pub.getPublisherId();
        if (publisherId == null || publisherId.isEmpty()) {
            log.warn("[FileRefRewriter] publication {} has no publisherId, skipping landing rewrite", pub.getId());
            return landing;
        }
        String publicationNamespace = "_publications/" + pub.getId() + "/";
        Map<String, Object> rewritten = new LinkedHashMap<>(landing);
        // Same scope decision as rewriteItems: only `data` is walked. Templates
        // are strings, actionMappings carry no FileRefs.
        Object data = rewritten.get("data");
        if (data != null) {
            rewritten.put("data", rewriteValue(data, publisherId, publicationNamespace));
        }
        return rewritten;
    }

    /**
     * Sign replacement image S3 keys into HMAC-signed proxy URLs.
     * Used by ShowcaseSnapshotReader to apply AI image replacements at render time.
     */
    public Map<String, String> signReplacementUrls(Map<String, String> replacements,
                                                     WorkflowPublicationEntity pub) {
        if (replacements == null || replacements.isEmpty()) return Map.of();
        if (!signer.isEnabled()) return Map.of();
        String publisherId = pub.getPublisherId();
        if (publisherId == null || publisherId.isEmpty()) return Map.of();
        String publicationNamespace = "_publications/" + pub.getId() + "/";
        Map<String, String> signed = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            String url = mintSignedUrl(publisherId, publicationNamespace, entry.getValue());
            if (url != null) {
                signed.put(entry.getKey(), url);
            }
        }
        return signed;
    }

    @SuppressWarnings("unchecked")
    private Object rewriteValue(Object value, String publisherId, String publicationNamespace) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> m = (Map<String, Object>) map;
            if (isFileRef(m)) {
                String key = String.valueOf(m.get("path"));
                String url = mintSignedUrl(publisherId, publicationNamespace, key);
                if (url != null) return url;
                return m; // leave raw FileRef on failure (better than null)
            }
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : m.entrySet()) {
                result.put(e.getKey(), rewriteValue(e.getValue(), publisherId, publicationNamespace));
            }
            return result;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) out.add(rewriteValue(item, publisherId, publicationNamespace));
            return out;
        }
        // JSON-encoded string that may contain FileRef maps (e.g. postsJson from js_template).
        // Parse, rewrite FileRefs inside, re-serialize to preserve String type for template compat.
        if (value instanceof String s && (s.startsWith("[") || s.startsWith("{")) && s.contains("\"_type\":\"file\"")) {
            try {
                Object parsed = objectMapper.readValue(s, Object.class);
                Object rewritten = rewriteValue(parsed, publisherId, publicationNamespace);
                return objectMapper.writeValueAsString(rewritten);
            } catch (Exception e) {
                // Not valid JSON - leave as-is
                return value;
            }
        }
        return value;
    }

    private static boolean isFileRef(Map<String, Object> m) {
        return "file".equals(m.get("_type"))
                && m.get("path") instanceof String s
                && !s.isEmpty();
    }

    private String mintSignedUrl(String publisherId, String publicationNamespace, String key) {
        // Defense in depth: refuse to sign keys that don't belong either to the
        // publisher's tenant (legacy items that were never namespace-copied) OR
        // to this publication's `_publications/{pubId}/` namespace (the target of
        // `WorkflowPublicationService.copyFileRefsInRunState`). Protects against
        // a corrupted snapshot leaking another tenant's or another publication's
        // file via the marketplace HMAC channel.
        boolean publisherOwned = key.startsWith(publisherId + "/");
        boolean publicationOwned = key.startsWith(publicationNamespace);
        if (!publisherOwned && !publicationOwned) {
            presignSkippedForeignKeyCounter.increment();
            log.warn("[FileRefRewriter] refusing to sign foreign key: publisher={} pubNs={} key={}",
                    publisherId, publicationNamespace, key);
            return null;
        }
        long exp = Instant.now().getEpochSecond() + (long) presignExpiryMinutes * 60L;
        String sig = signer.sign(key, exp, SIGNED_DISPOSITION);
        if (sig == null) {
            presignFailCounter.increment();
            return null;
        }
        presignOkCounter.increment();
        return "/api/files/proxy-signed"
                + "?key=" + URLEncoder.encode(key, StandardCharsets.UTF_8)
                + "&exp=" + exp
                + "&disposition=" + SIGNED_DISPOSITION
                + "&sig=" + sig;
    }
}
