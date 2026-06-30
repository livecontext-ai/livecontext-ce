package com.apimarketplace.catalog.service.execution;

import com.apimarketplace.storage.client.StorageClient;
import com.apimarketplace.storage.client.dto.FileRefDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Handles binary responses from catalog API tools (Phase 8 of the typed-execution refactor).
 *
 * When an endpoint declares {@code execution.response.type=binary}, the raw bytes returned
 * by the upstream API are uploaded to MinIO via {@link StorageClient}, and the resulting
 * stored asset is exposed downstream as a {@code FileRef} structured value:
 *
 * <pre>
 * {
 *   "_type":   "file",
 *   "path":    "{tenantId}/general/catalog-binary/{uuid}_{filename}",
 *   "name":    "img.png",
 *   "mimeType":"image/png",
 *   "size":    12345,
 *   "url":     "https://minio.../signed-url"
 * }
 * </pre>
 *
 * The field name in the projected output is taken from the FIRST {@code fileRef}-typed
 * field declared in the tool's {@code output_schema}. If no fileRef field is declared
 * (validator should have caught this) the binary is dropped and an error is logged.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BinaryResponseHandler {

    private static final String CATALOG_BINARY_CATEGORY = "catalog-binary";

    @Autowired(required = false)
    private StorageClient storageClient;

    private final ObjectMapper objectMapper;

    /**
     * Take a raw byte response and produce a typed projected output containing a FileRef.
     *
     * @param rawBytes        the binary body returned by the upstream API
     * @param contentType     content type from the response (used as fallback mime type)
     * @param tenantId        tenant id (X-User-ID); files are scoped per tenant in MinIO
     * @param outputSchemaJson the tool's declared output_schema
     * @param toolSlug        used to generate a default file name when none is suggested
     * @return projected output map { "<fileRefKey>": FileRef } or empty map on failure
     */
    public Map<String, Object> handle(byte[] rawBytes,
                                      String contentType,
                                      String tenantId,
                                      String outputSchemaJson,
                                      String toolSlug) {
        if (rawBytes == null || rawBytes.length == 0) {
            return Map.of();
        }
        if (storageClient == null) {
            log.warn("BinaryResponseHandler: storageClient not available, dropping {} bytes", rawBytes.length);
            return Map.of();
        }

        String fileRefKey = findFirstFileRefKey(outputSchemaJson);
        if (fileRefKey == null) {
            log.warn("BinaryResponseHandler: tool '{}' returned binary but outputSchema declares no fileRef field", toolSlug);
            return Map.of();
        }

        String mimeType = contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType;
        String extension = extensionFromMime(mimeType);
        String fileName = (toolSlug == null ? "asset" : toolSlug) + "_" + UUID.randomUUID().toString().substring(0, 8) + extension;
        String tenant = tenantId == null || tenantId.isBlank() ? "anonymous" : tenantId;

        try {
            FileRefDto uploaded = storageClient.genericUpload(
                tenant, CATALOG_BINARY_CATEGORY, fileName, mimeType, rawBytes);
            if (uploaded == null) {
                log.error("BinaryResponseHandler: storageClient.genericUpload returned null for tool {}", toolSlug);
                return Map.of();
            }

            Map<String, Object> fileRef = buildFileRefMap(uploaded, tenant);
            Map<String, Object> projected = new LinkedHashMap<>();
            projected.put(fileRefKey, fileRef);
            return projected;
        } catch (Exception e) {
            log.error("BinaryResponseHandler: failed to upload binary response for tool {}: {}", toolSlug, e.getMessage());
            return Map.of();
        }
    }

    /**
     * Build the FileRef Map shape consumers expect: the canonical fields
     * ({@code _type}, {@code path}, {@code name}, {@code mimeType}, {@code size})
     * plus the opaque storage-row {@code id}. Deliberately NO {@code url} - the
     * frontend/agent build the opaque {@code /api/proxy/files/by-id/{id}/raw} URL
     * from {@code id} on demand (no presigned-URL TTL to expire in the agent's
     * context, no s3 key / tenant id leaked).
     */
    private Map<String, Object> buildFileRefMap(FileRefDto uploaded, String tenant) {
        // v3.1 - NO presigned `url` in the FileRef response tree. The URL has a
        // 60-min TTL and used to leak into the agent's tool-result context for
        // EVERY catalog tool emitting a FileRef (download_file, image-gen edit,
        // TTS, etc.) - agents that cached or quoted it would 403 later. Frontend
        // consumers (chat card, Files panel) re-resolve via the opaque, id-based
        // file URL ({@code /api/proxy/files/by-id/{id}/raw}), so dropping `url`
        // here doesn't affect display. Same rationale that already drove
        // {@code AGENT_VISIBLE_FILEREF_KEYS} in the orchestrator's image-gen
        // module - now centralised at the producer.
        Map<String, Object> fileRef = new LinkedHashMap<>();
        fileRef.put("_type", "file");
        fileRef.put("path", uploaded.path());
        fileRef.put("name", uploaded.name());
        fileRef.put("mimeType", uploaded.mimeType());
        fileRef.put("size", uploaded.size());
        // Opaque handle the frontend/agent use to build the by-id file URL (no tenant id / s3 key).
        if (uploaded.id() != null) {
            fileRef.put("id", uploaded.id());
        }
        return fileRef;
    }

    /**
     * Walk the output_schema and return the key of the first {@code fileRef}-typed field.
     */
    private String findFirstFileRefKey(String outputSchemaJson) {
        if (outputSchemaJson == null || outputSchemaJson.isBlank()) return null;
        try {
            JsonNode root = objectMapper.readTree(outputSchemaJson);
            if (!root.isArray()) return null;
            for (JsonNode field : root) {
                if ("fileRef".equals(field.path("type").asText())) {
                    String key = field.path("key").asText();
                    if (!key.isBlank()) return key;
                }
            }
        } catch (Exception e) {
            log.debug("BinaryResponseHandler: failed to parse outputSchema: {}", e.getMessage());
        }
        return null;
    }

    private String extensionFromMime(String mimeType) {
        if (mimeType == null) return ".bin";
        String mt = mimeType.toLowerCase();
        int semi = mt.indexOf(';');
        if (semi > 0) mt = mt.substring(0, semi).trim();

        // Image
        if (mt.equals("image/png"))                       return ".png";
        if (mt.equals("image/jpeg") || mt.equals("image/jpg")) return ".jpg";
        if (mt.equals("image/gif"))                       return ".gif";
        if (mt.equals("image/webp"))                      return ".webp";
        if (mt.equals("image/svg+xml"))                   return ".svg";
        if (mt.equals("image/avif"))                      return ".avif";
        if (mt.equals("image/heic"))                      return ".heic";
        if (mt.equals("image/heif"))                      return ".heif";
        if (mt.equals("image/tiff"))                      return ".tiff";
        if (mt.equals("image/bmp"))                       return ".bmp";

        // Audio
        if (mt.equals("audio/mpeg") || mt.equals("audio/mp3")) return ".mp3";
        if (mt.equals("audio/wav") || mt.equals("audio/wave") || mt.equals("audio/x-wav")) return ".wav";
        if (mt.equals("audio/ogg"))                       return ".ogg";
        if (mt.equals("audio/flac"))                      return ".flac";
        if (mt.equals("audio/aac"))                       return ".aac";
        if (mt.equals("audio/mp4") || mt.equals("audio/x-m4a")) return ".m4a";
        if (mt.equals("audio/opus"))                      return ".opus";
        if (mt.equals("audio/webm"))                      return ".weba";
        if (mt.equals("audio/pcm") || mt.startsWith("audio/l16") || mt.startsWith("audio/l24")) return ".pcm";
        if (mt.equals("audio/basic") || mt.equals("audio/ulaw") || mt.equals("audio/x-mulaw") || mt.equals("audio/mulaw")) return ".ulaw";
        if (mt.equals("audio/x-alaw") || mt.equals("audio/alaw")) return ".alaw";

        // Video
        if (mt.equals("video/mp4"))                       return ".mp4";
        if (mt.equals("video/webm"))                      return ".webm";
        if (mt.equals("video/quicktime"))                 return ".mov";
        if (mt.equals("video/x-msvideo"))                 return ".avi";
        if (mt.equals("video/x-matroska"))                return ".mkv";
        if (mt.equals("video/x-m4v"))                     return ".m4v";
        if (mt.equals("video/ogg"))                       return ".ogv";

        // 3D
        if (mt.equals("model/gltf-binary"))               return ".glb";
        if (mt.equals("model/gltf+json"))                 return ".gltf";
        if (mt.equals("model/obj"))                       return ".obj";
        if (mt.equals("model/stl"))                       return ".stl";

        // Documents
        if (mt.equals("application/pdf"))                 return ".pdf";
        if (mt.equals("application/msword"))              return ".doc";
        if (mt.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document")) return ".docx";
        if (mt.equals("application/vnd.ms-excel"))        return ".xls";
        if (mt.equals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) return ".xlsx";

        // Archives
        if (mt.equals("application/zip"))                 return ".zip";
        if (mt.equals("application/x-tar"))               return ".tar";
        if (mt.equals("application/gzip") || mt.equals("application/x-gzip")) return ".gz";
        if (mt.equals("application/x-7z-compressed"))     return ".7z";
        if (mt.equals("application/vnd.rar"))             return ".rar";

        // Text
        if (mt.equals("application/json"))                return ".json";
        if (mt.equals("application/xml") || mt.equals("text/xml")) return ".xml";
        if (mt.equals("text/csv"))                        return ".csv";
        if (mt.equals("text/html"))                       return ".html";
        if (mt.equals("text/markdown"))                   return ".md";
        if (mt.startsWith("text/"))                       return ".txt";

        // Fallback: use subtype after '/' if reasonable
        int slash = mt.indexOf('/');
        if (slash > 0 && slash < mt.length() - 1) {
            String sub = mt.substring(slash + 1);
            // Strip vendor prefixes (x-, vnd.)
            if (sub.startsWith("x-")) sub = sub.substring(2);
            if (sub.startsWith("vnd.")) sub = sub.substring(4);
            // Sanity check: only alphanumeric, short
            if (sub.matches("[a-z0-9]{1,8}")) return "." + sub;
        }
        return ".bin";
    }

    /**
     * Useful for downstream consumers that need a raw List wrapper around a single FileRef.
     */
    public static List<Object> wrapAsList(Map<String, Object> projected) {
        return List.copyOf(projected.values());
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Inline-base64 dehydration (Gemini-style: upstream returns JSON with
    //  a deeply-nested base64 string carrying the binary). Differs from the
    //  raw-binary path above where the upstream sets Content-Type=image/...
    // ════════════════════════════════════════════════════════════════════════

    /** Below this size we leave the string alone (favicons, tiny avatars, etc.). */
    private static final int INLINE_BASE64_MIN_BYTES = 64 * 1024;

    /**
     * Base64 alphabet - accepts BOTH the standard ({@code +/}) and URL-safe
     * ({@code -_}) variants, optional trailing padding. URL-safe form is
     * common for JWT-style tokens and signed-URL payloads - without
     * accepting it, GCP/AWS APIs returning >85 KB URL-safe blobs would leak
     * raw to the LLM. The full {@link Base64#getDecoder} call below treats
     * URL-safe via {@link Base64#getUrlDecoder} fallback in {@link #tryDecodeBase64}.
     */
    private static final Pattern BASE64_ALPHABET = Pattern.compile("^[A-Za-z0-9+/\\-_\\s]+={0,2}$");

    /** Below this Shannon byte-entropy a string is almost certainly natural text
     *  rather than compressed/encrypted/encoded binary. ASCII English averages
     *  ~4.5 bits/byte; uniform-random binary is 8.0; PNG/JPEG/MP3 fall in the
     *  7.0-7.95 range. Threshold 6.0 catches ASCII text false-positives without
     *  excluding any real binary format we care about. */
    private static final double MIN_BYTE_ENTROPY = 6.0;

    /**
     * Walks a JSON-shaped tool response (Map / List / scalar) and replaces every
     * leaf string that looks like a large inline base64 binary with a
     * {@code FileRef} {@code Map} (same shape this handler already emits for raw
     * binary upstream responses). Returns the rewritten root and a list of the
     * uploaded {@link DehydratedAsset}s for caller-side metadata stamping.
     *
     * <p>Detection is purely heuristic in v1: size threshold {@value #INLINE_BASE64_MIN_BYTES}
     * bytes AND base64-alphabet match AND successful decode. Schema-flag-driven
     * detection (e.g. {@code format:"base64"}) is deferred to v2 so we can
     * retroactively tag known-binary fields without blocking this PR.
     *
     * <p>Mime sniffing is best-effort from the decoded bytes' magic header
     * (PNG, JPEG, GIF, PDF, MP3 ID3, WebP, ZIP, …). Falls back to
     * {@code application/octet-stream} when unknown - agent + frontend treat
     * unknowns as a download chip rather than a typed preview.
     *
     * <p>Fail-safe: any upload failure leaves the original string in place
     * (better degradation than corrupting the response). The dehydrator never
     * throws to its caller.
     */
    public DehydrationResult dehydrateInlineBase64(Object root, String tenantId, String toolSlug) {
        if (root == null) {
            return DehydrationResult.NOOP;
        }
        if (storageClient == null) {
            // Without storage we can still detect, but uploading is a no-op:
            // returning NOOP avoids partial state where some leaves are
            // dehydrated and others aren't if storage flakes mid-walk.
            // Logged at WARN so silent regressions (missing bean, broken
            // wiring) are visible in prod logs - this used to round-trip the
            // raw base64 back to the agent unnoticed.
            log.warn("BinaryResponseHandler.dehydrateInlineBase64: storageClient is null, skipping dehydration for tool {} (tenant {}). Inline base64 leaves will be returned as-is.",
                    toolSlug, tenantId);
            return DehydrationResult.NOOP;
        }
        String tenant = tenantId == null || tenantId.isBlank() ? "anonymous" : tenantId;
        List<DehydratedAsset> uploaded = new ArrayList<>();
        Object rewritten = walk(root, "", tenant, toolSlug, uploaded);
        return new DehydrationResult(rewritten, uploaded);
    }

    @SuppressWarnings("unchecked")
    private Object walk(Object value, String currentPath, String tenant, String toolSlug,
                        List<DehydratedAsset> collected) {
        if (value == null) return null;
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                String childPath = currentPath.isEmpty() ? key : currentPath + "." + key;
                result.put(key, walk(entry.getValue(), childPath, tenant, toolSlug, collected));
            }
            return result;
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            for (int i = 0; i < list.size(); i++) {
                result.add(walk(list.get(i), currentPath + "[" + i + "]", tenant, toolSlug, collected));
            }
            return result;
        }
        if (value instanceof String str) {
            // Diagnostic for the silent "walker found no assets" case: when a
            // string is >= 64 KB but fails one of the heuristic gates, log
            // at INFO so prod regressions (cache pollution, alphabet drift,
            // low-entropy false negatives) are visible. Below threshold we
            // stay silent - short strings would spam logs.
            if (str.length() >= INLINE_BASE64_MIN_BYTES) {
                if (!looksLikeLargeBase64(str)) {
                    log.info("BinaryResponseHandler.walk: tool={} path={} len={} REJECTED at alphabet gate",
                            toolSlug, currentPath, str.length());
                    return value;
                }
                byte[] bytes = tryDecodeBase64(str);
                if (bytes == null) {
                    log.info("BinaryResponseHandler.walk: tool={} path={} len={} REJECTED at decode (both std + url-safe failed)",
                            toolSlug, currentPath, str.length());
                    return value;
                }
                if (bytes.length < INLINE_BASE64_MIN_BYTES) {
                    log.info("BinaryResponseHandler.walk: tool={} path={} decodedBytes={} REJECTED at decoded-size gate",
                            toolSlug, currentPath, bytes.length);
                    return value;
                }
                // Early accept on known magic bytes - sniffMime returns a real
                // MIME (image/png, audio/mpeg, application/pdf, …) only when
                // the payload starts with a recognised binary signature. In
                // that case we don't need the entropy fallback (which exists
                // ONLY to reject ASCII-text false positives that decoded
                // successfully but aren't binary). OpenAI's gpt-image-1
                // prefixes PNGs with a C2PA JUMB metadata box of low-entropy
                // ASCII, so the first-4 KB entropy sample drops below 6.0
                // and silently rejected real PNGs - fixed by trusting the
                // magic-byte verdict here.
                String detectedMime = sniffMime(bytes);
                boolean knownBinary = detectedMime != null && !"application/octet-stream".equals(detectedMime);
                if (!knownBinary) {
                    double entropy = byteEntropy(bytes);
                    if (entropy < MIN_BYTE_ENTROPY) {
                        log.info("BinaryResponseHandler.walk: tool={} path={} decodedBytes={} entropy={} REJECTED at entropy gate (min {}, mime=unknown)",
                                toolSlug, currentPath, bytes.length, entropy, MIN_BYTE_ENTROPY);
                        return value;
                    }
                }
                Map<String, Object> fileRef = uploadAndBuildFileRef(bytes, tenant, toolSlug, currentPath);
                if (fileRef != null) {
                    collected.add(new DehydratedAsset(currentPath, fileRef));
                    return fileRef;
                }
                // upload failure already WARN-logged in uploadAndBuildFileRef
            }
        }
        return value;
    }

    /** Cheap pre-check before decoding - string length and alphabet only. */
    private static boolean looksLikeLargeBase64(String str) {
        // base64 expansion is ~4/3 → a 64KB binary is ~85KB string; require >= ~85KB.
        if (str == null || str.length() < (INLINE_BASE64_MIN_BYTES * 4 / 3)) return false;
        // Sample first 1024 chars for the alphabet check to avoid full regex on
        // multi-megabyte strings; full decode below is the authoritative test.
        int sampleLen = Math.min(1024, str.length());
        return BASE64_ALPHABET.matcher(str.substring(0, sampleLen).replaceAll("\\s", "")).matches();
    }

    private static byte[] tryDecodeBase64(String str) {
        String stripped = str.replaceAll("\\s", "");
        // Try standard alphabet first; fall back to URL-safe alphabet
        // when the standard decoder rejects (covers JWT-style / GCP /
        // AWS signed-URL payloads using `-` and `_`).
        try {
            return Base64.getDecoder().decode(stripped);
        } catch (IllegalArgumentException e1) {
            try {
                return Base64.getUrlDecoder().decode(stripped);
            } catch (IllegalArgumentException e2) {
                return null;
            }
        }
    }

    /**
     * Shannon byte-level entropy of the decoded bytes. Used to reject
     * "decoded successfully but is actually ASCII text" false positives -
     * e.g. a 100 KB scraped article whose alphanumerics happen to satisfy
     * the base64 alphabet. Compressed / encrypted / true binary always
     * exceeds {@value #MIN_BYTE_ENTROPY} bits/byte.
     */
    private static double byteEntropy(byte[] bytes) {
        // Sample first 4096 bytes to bound runtime on multi-MB blobs;
        // entropy of a representative prefix tracks the whole.
        int n = Math.min(bytes.length, 4096);
        if (n == 0) return 0.0;
        int[] freq = new int[256];
        for (int i = 0; i < n; i++) freq[bytes[i] & 0xFF]++;
        double h = 0.0;
        for (int c : freq) {
            if (c == 0) continue;
            double p = (double) c / n;
            h -= p * (Math.log(p) / Math.log(2));
        }
        return h;
    }

    private Map<String, Object> uploadAndBuildFileRef(byte[] bytes, String tenant, String toolSlug,
                                                       String sourcePath) {
        String mimeType = sniffMime(bytes);
        String extension = extensionFromMime(mimeType);
        String fileName = (toolSlug == null ? "asset" : sanitiseSlug(toolSlug))
                + "_" + UUID.randomUUID().toString().substring(0, 8) + extension;
        try {
            FileRefDto uploaded = storageClient.genericUpload(
                tenant, CATALOG_BINARY_CATEGORY, fileName, mimeType, bytes);
            if (uploaded == null) {
                log.warn("BinaryResponseHandler.dehydrate: genericUpload returned null for tool={} path={} ({} bytes) - leaving inline",
                        toolSlug, sourcePath, bytes.length);
                return null;
            }
            Map<String, Object> fileRef = buildFileRefMap(uploaded, tenant);
            // Keep the response-shape provenance so downstream tooling can map
            // back to the original API field if needed (debug, audit).
            fileRef.put("source_path", sourcePath);
            return fileRef;
        } catch (Exception e) {
            log.warn("BinaryResponseHandler.dehydrate: upload failed for tool={} path={}: {} - leaving inline",
                    toolSlug, sourcePath, e.getMessage());
            return null;
        }
    }

    private static String sanitiseSlug(String slug) {
        return slug.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    /**
     * Best-effort MIME detection from magic bytes. Covers the formats that
     * actually show up in inline-base64 catalog responses today (image-gen
     * APIs, audio APIs, PDF generators). Unknown → {@code application/octet-stream}.
     */
    private static String sniffMime(byte[] bytes) {
        if (bytes == null || bytes.length < 4) return "application/octet-stream";
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if (bytes[0] == (byte) 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
            return "image/png";
        }
        // JPEG: FF D8 FF
        if (bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xD8 && bytes[2] == (byte) 0xFF) {
            return "image/jpeg";
        }
        // GIF: 47 49 46 38 ("GIF8")
        if (bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == '8') {
            return "image/gif";
        }
        // WebP: "RIFF....WEBP"
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return "image/webp";
        }
        // PDF: "%PDF"
        if (bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F') {
            return "application/pdf";
        }
        // ZIP / docx / xlsx: 50 4B 03 04
        if (bytes[0] == 'P' && bytes[1] == 'K' && bytes[2] == 0x03 && bytes[3] == 0x04) {
            return "application/zip";
        }
        // MP3 ID3: "ID3"
        if (bytes[0] == 'I' && bytes[1] == 'D' && bytes[2] == '3') {
            return "audio/mpeg";
        }
        // MP3 (no ID3, raw frame): FF FB / FF F3 / FF F2
        if (bytes[0] == (byte) 0xFF && (bytes[1] == (byte) 0xFB || bytes[1] == (byte) 0xF3 || bytes[1] == (byte) 0xF2)) {
            return "audio/mpeg";
        }
        // WAV: "RIFF....WAVE"
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'A' && bytes[10] == 'V' && bytes[11] == 'E') {
            return "audio/wav";
        }
        // OGG: "OggS"
        if (bytes[0] == 'O' && bytes[1] == 'g' && bytes[2] == 'g' && bytes[3] == 'S') {
            return "audio/ogg";
        }
        // ISO BMFF: bytes[4..7] = "ftyp", brand is bytes[8..11]. Must
        // disambiguate - without the brand box, HEIC photos / AVIF stills /
        // M4A audio would all mis-typed as video/mp4 and the frontend would
        // render an HEIC inside <video>, showing a broken player.
        if (bytes.length >= 12 && bytes[4] == 'f' && bytes[5] == 't' && bytes[6] == 'y' && bytes[7] == 'p') {
            String brand = new String(bytes, 8, 4);
            switch (brand) {
                case "heic": case "heix": case "mif1": case "msf1":
                    return "image/heic";
                case "heif":
                    return "image/heif";
                case "avif": case "avis":
                    return "image/avif";
                case "M4A ":
                    return "audio/mp4";
                case "M4V ": case "mp42": case "isom": case "iso2":
                    return "video/mp4";
                case "qt  ":
                    return "video/quicktime";
                case "3gp4": case "3gp5": case "3g2a":
                    return "video/3gpp";
                default:
                    // Unknown brand - assume video/mp4 (safest catchall, the
                    // browser can play most undeclared MP4 variants via the
                    // video codecs registry; iOS HEIC has been disambiguated
                    // above so this fallback won't bite the common case).
                    return "video/mp4";
            }
        }
        // Matroska / WebM: 1A 45 DF A3 EBML header
        if (bytes[0] == (byte) 0x1A && bytes[1] == (byte) 0x45 && bytes[2] == (byte) 0xDF && bytes[3] == (byte) 0xA3) {
            return "video/webm";
        }
        // FLAC: "fLaC"
        if (bytes[0] == 'f' && bytes[1] == 'L' && bytes[2] == 'a' && bytes[3] == 'C') {
            return "audio/flac";
        }
        // BMP: "BM"
        if (bytes[0] == 'B' && bytes[1] == 'M') {
            return "image/bmp";
        }
        // TIFF: "II*\0" (little-endian) or "MM\0*" (big-endian)
        if ((bytes[0] == 'I' && bytes[1] == 'I' && bytes[2] == 0x2A && bytes[3] == 0x00)
                || (bytes[0] == 'M' && bytes[1] == 'M' && bytes[2] == 0x00 && bytes[3] == 0x2A)) {
            return "image/tiff";
        }
        // GLB (binary glTF): "glTF"
        if (bytes[0] == 'g' && bytes[1] == 'l' && bytes[2] == 'T' && bytes[3] == 'F') {
            return "model/gltf-binary";
        }
        // gzip: 1F 8B
        if (bytes[0] == (byte) 0x1F && bytes[1] == (byte) 0x8B) {
            return "application/gzip";
        }
        // bzip2: "BZh"
        if (bytes[0] == 'B' && bytes[1] == 'Z' && bytes[2] == 'h') {
            return "application/x-bzip2";
        }
        // 7-Zip: 37 7A BC AF 27 1C
        if (bytes.length >= 6 && bytes[0] == (byte) 0x37 && bytes[1] == (byte) 0x7A
                && bytes[2] == (byte) 0xBC && bytes[3] == (byte) 0xAF) {
            return "application/x-7z-compressed";
        }
        // RAR: "Rar!"
        if (bytes[0] == 'R' && bytes[1] == 'a' && bytes[2] == 'r' && bytes[3] == '!') {
            return "application/vnd.rar";
        }
        return "application/octet-stream";
    }

    /** A single uploaded asset with the response path it was extracted from. */
    public record DehydratedAsset(String sourcePath, Map<String, Object> fileRef) {}

    /**
     * Result of a dehydration walk: the rewritten response root and the list of
     * uploaded assets (caller stamps these onto {@code metadata.attachments[]}
     * and the textual visualization marker).
     */
    public record DehydrationResult(Object root, List<DehydratedAsset> assets) {
        public static final DehydrationResult NOOP = new DehydrationResult(null, List.of());
        public boolean hasAssets() { return assets != null && !assets.isEmpty(); }
    }
}
