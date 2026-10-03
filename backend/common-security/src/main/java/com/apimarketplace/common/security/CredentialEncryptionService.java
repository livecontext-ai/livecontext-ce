package com.apimarketplace.common.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Encrypts and decrypts sensitive credential data at rest, and derives the keyed HMAC used to
 * look capability tokens and API keys up by hash.
 *
 * <h2>Envelope formats</h2>
 * <ul>
 *   <li><b>v2</b>: {@code ENC:v2.<kid>.<base64url(iv|ciphertext|tag)>}. AES-256-GCM, 96-bit random
 *       IV, 128-bit tag, over a data key derived with HKDF-SHA256 from the configured password and
 *       salt under a dedicated info label. The additional authenticated data binds the domain and
 *       the key id, so a value cannot be replayed under another key generation. {@code kid} is 8
 *       hex chars, itself HKDF-derived, so it identifies the material without revealing it.
 *       <br>AAD design decision: the AAD is {@code livecontext:credential:v2|<kid>}, deliberately
 *       NOT the row / tenant / field. Binding the row would need the field name and row key at
 *       every encrypt call site (several encrypt a lone value with no such context) and would make
 *       every value written by the other sites unreadable. Consequence, accepted: an attacker who
 *       can already WRITE the database can transplant a ciphertext between rows or fields and it
 *       still decrypts. Confidentiality and integrity of each value are unaffected.</li>
 *   <li><b>v1 (legacy)</b>: {@code ENC:<hex>}, the historical {@link Encryptors#text} output
 *       (AES-256-CBC, PBKDF2, no integrity). Always readable. The two never collide: a v1 body
 *       is hex and {@code v} is not a hex digit.</li>
 * </ul>
 *
 * <h2>Write version (rollout switch)</h2>
 * {@code credential.encryption.write-version} ({@code 1} or {@code 2}) decides what NEW writes
 * produce: the envelope, and the key of {@link #hmacHash(String)}. Reads accept both at every
 * setting. The default is {@code 1} for the release that introduces the v2 reader: services roll
 * pod by pod and separately, so a replica still on the previous image would fail on a v2 envelope
 * and would not find a token hashed with the new HMAC sub-key. Once every service runs this
 * reader, set {@code CREDENTIAL_ENCRYPTION_WRITE_VERSION=2}: new writes are v2 and each service's
 * startup sweep re-encrypts its own rows in place ({@link SensitiveJsonbBackfill} for JSONB maps
 * and encrypted text columns, {@code PlaintextTokenBackfill} for capability-token columns, which it
 * also re-hashes). API-key hashes are one-way: they move to the new HMAC key when the key is next
 * used (ApiKeyService re-hashes on a lookup that matched a non-current candidate). The
 * single-process CE monolith has no mixed-version window and sets {@code 2} directly.
 *
 * <h2>HMAC key separation</h2>
 * In v2 mode {@link #hmacHash(String)} keys on an HKDF sub-key (its own info label), not on the
 * password that also protects the credential store. Hashes already stored (API keys, capability
 * tokens) were keyed on the raw password; they keep resolving because every lookup goes through
 * {@link #hmacHashCandidates(String)}, which yields both forms (and the previous generation's).
 *
 * <h2>Key rotation</h2>
 * Put the retiring material on {@code credential.encryption.previous-password} /
 * {@code .previous-salt} and the new one on {@code password} / {@code salt}: reads accept either
 * generation (v2 by kid, v1 by trial), writes use the new one, lookups try both. At write-version
 * 2 the sweeps re-seal every v2 value whose kid is not the current one ({@link #needsReencryption}),
 * so a rotation actually finishes; drop the previous material once they have run and every
 * API key has been used (or regenerated) since.
 *
 * <h2>Plaintext reads</h2>
 * A stored value without the {@code ENC:} prefix predates encryption. It is counted
 * ({@link #plaintextReadCount()}, metric {@code credential.plaintext.read}) and warned once per
 * field. {@code credential.encryption.plaintext-reads=deny} makes such a read throw; switch it on
 * only after the sweeps have run and the counter stays at zero.
 *
 * <h2>Legacy (v1) reads</h2>
 * Every caller-facing decrypt of a v1 envelope is counted ({@link #legacyReadCount()}, metric
 * {@code credential.legacy.read}). {@code credential.encryption.legacy-reads=deny} (default allow)
 * refuses them; enable it only at write-version 2 once the sweeps have drained and the counter
 * stays at zero. The sweeps' own re-seal path is exempt so they can still migrate stragglers.
 *
 * <h2>Key presence</h2>
 * A missing password/salt is fatal unless the process is a dev/test run (a {@code dev},
 * {@code local}, {@code e2e} or {@code *test*} profile, no profile at all, or a test framework on
 * the stack) and not on Kubernetes; {@code security.reject-default-secrets} overrides the
 * decision either way. A known default value is also fatal in strict mode, unless
 * {@code credential.encryption.allow-weak-material=true} keeps it in use (an old CE volume whose
 * rows were written under it). No configured value is ever silently replaced by an ephemeral key.
 */
@Service
public class CredentialEncryptionService {

    private static final Logger log = LoggerFactory.getLogger(CredentialEncryptionService.class);

    /**
     * Prefix for encrypted values. Allows distinguishing encrypted from plaintext.
     */
    public static final String ENCRYPTED_PREFIX = "ENC:";

    /** Body marker of the authenticated envelope, right after {@link #ENCRYPTED_PREFIX}. */
    static final String V2_BODY_PREFIX = "v2.";

    static final String LEGACY_DEFAULT_PASSWORD = "change" + "me-in-production";
    static final String LEGACY_DEFAULT_SALT = "deadbeef" + "deadbeef";
    private static final String LEGACY_DEFAULT_PASSWORD_SHORT = "change" + "me";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /** Write version of the convenience constructors and of an unset property, see class doc. */
    static final int DEFAULT_WRITE_VERSION = 1;

    private static final String HKDF_INFO_DATA_KEY = "livecontext/credential-encryption/aes-gcm/v2";
    private static final String HKDF_INFO_LOOKUP_KEY = "livecontext/credential-encryption/lookup-hmac/v2";
    private static final String HKDF_INFO_KEY_ID = "livecontext/credential-encryption/key-id/v2";
    private static final String AAD_DOMAIN = "livecontext:credential:v2";

    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int KEY_BYTES = 32;
    private static final int KEY_ID_BYTES = 4;

    /** Profiles under which absent material is tolerated (an ephemeral key is minted). */
    private static final Set<String> DEV_PROFILES = Set.of("dev", "local", "e2e", "default");

    /**
     * Which keys of a JSONB map hold a secret is decided by {@link SensitiveFieldDetector}: a
     * shape rule (secret / token / key / password tokens, minus descriptor suffixes) that is a
     * strict superset of the 15-name allow-list this class used until 2026-09-17. That list
     * missed every custom-auth field the catalogue writes ({@code secret_access_key},
     * {@code private_key}, {@code api_secret}, ...), which therefore reached the database in
     * clear. See the detector for the rules and the production keys the test pins.
     */
    public static boolean isSensitiveField(String key) {
        return SensitiveFieldDetector.isSensitive(key);
    }

    private final KeyMaterial current;
    private final KeyMaterial previous;
    private final boolean ephemeralMaterial;
    private final boolean writeV2;
    private final boolean denyPlaintextReads;
    private final AtomicLong plaintextReads = new AtomicLong();
    private final boolean denyLegacyReads;
    private final AtomicLong legacyReads = new AtomicLong();
    private final Set<String> plaintextReadFieldsSeen = ConcurrentHashMap.newKeySet();

    /** Convenience constructor (tests, direct wiring): lenient, write version 1, no rotation. */
    public CredentialEncryptionService(String password, String salt) {
        this(password, salt, false);
    }

    /** Convenience constructor: explicit strict flag, write version 1, no rotation. */
    public CredentialEncryptionService(String password, String salt, boolean rejectDefaultSecrets) {
        this(password, salt, "", "", rejectDefaultSecrets, false,
                String.valueOf(DEFAULT_WRITE_VERSION), "allow", "");
    }

    /** Convenience constructor: every knob except legacy-reads, which stays allow. */
    public CredentialEncryptionService(String password, String salt, String previousPassword, String previousSalt,
                                       Boolean rejectDefaultSecrets, boolean allowWeakMaterial, String writeVersion,
                                       String plaintextReadsMode, String activeProfiles) {
        this(password, salt, previousPassword, previousSalt, rejectDefaultSecrets, allowWeakMaterial, writeVersion,
                plaintextReadsMode, "allow", activeProfiles);
    }

    @Autowired
    public CredentialEncryptionService(
            @Value("${credential.encryption.password:}") String password,
            @Value("${credential.encryption.salt:}") String salt,
            @Value("${credential.encryption.previous-password:}") String previousPassword,
            @Value("${credential.encryption.previous-salt:}") String previousSalt,
            @Value("${security.reject-default-secrets:#{null}}") Boolean rejectDefaultSecrets,
            @Value("${credential.encryption.allow-weak-material:false}") boolean allowWeakMaterial,
            @Value("${credential.encryption.write-version:" + DEFAULT_WRITE_VERSION + "}") String writeVersion,
            @Value("${credential.encryption.plaintext-reads:allow}") String plaintextReadsMode,
            @Value("${credential.encryption.legacy-reads:allow}") String legacyReadsMode,
            @Value("${spring.profiles.active:}") String activeProfiles) {
        boolean strict = resolveStrict(rejectDefaultSecrets, activeProfiles,
                System.getenv("KUBERNETES_SERVICE_HOST") != null, runningUnderTestFramework());
        boolean blank = isBlank(password) || isBlank(salt);
        boolean knownDefault = !blank && (isKnownDefaultPassword(password) || LEGACY_DEFAULT_SALT.equals(salt.trim()));

        String resolvedPassword = password;
        String resolvedSalt = salt;
        boolean ephemeral = false;
        if (blank) {
            if (strict) {
                throw new IllegalStateException("credential.encryption.password and credential.encryption.salt "
                        + "must be set outside dev/test (CREDENTIAL_ENCRYPTION_PASSWORD / CREDENTIAL_ENCRYPTION_SALT). "
                        + "Refusing to start on an ephemeral key: everything it encrypts is unreadable after a restart.");
            }
            resolvedPassword = isBlank(password) ? generateEphemeralPassword() : password;
            resolvedSalt = isBlank(salt) ? generateEphemeralSalt() : salt;
            ephemeral = true;
            log.warn("credential.encryption.password/salt is not configured; this process uses an EPHEMERAL "
                    + "in-memory key. Anything encrypted now is lost at restart. Development only.");
        } else if (knownDefault) {
            if (strict && !allowWeakMaterial) {
                throw new IllegalStateException("credential.encryption.password and credential.encryption.salt "
                        + "must be overridden outside dev/test: a published default value is configured. Set "
                        + "credential.encryption.allow-weak-material=true only to keep reading rows written under it "
                        + "while you rotate (previous-password/previous-salt).");
            }
            // Used as configured, never swapped for an ephemeral key: rows already written under it
            // must stay readable, and an ephemeral substitute would lose every new write at restart.
            log.warn("credential.encryption.password/salt is a published default value. It is used as configured "
                    + "so existing credentials stay readable, but it MUST be rotated.");
        }

        this.current = KeyMaterial.of(resolvedPassword, resolvedSalt);
        this.previous = (isBlank(previousPassword) || isBlank(previousSalt))
                ? null : KeyMaterial.of(previousPassword, previousSalt);
        this.ephemeralMaterial = ephemeral;
        this.writeV2 = parseWriteVersion(writeVersion) == 2;
        this.denyPlaintextReads = "deny".equalsIgnoreCase(trim(plaintextReadsMode));
        this.denyLegacyReads = "deny".equalsIgnoreCase(trim(legacyReadsMode));
        log.info("CredentialEncryptionService initialized (kid={}, previousKid={}, writeVersion={}, plaintextReads={}, "
                        + "legacyReads={})",
                current.kid(), previous == null ? "none" : previous.kid(), writeV2 ? 2 : 1,
                denyPlaintextReads ? "deny" : "allow", denyLegacyReads ? "deny" : "allow");
    }

    // ------------------------------------------------------------------
    // Configuration
    // ------------------------------------------------------------------

    /**
     * Whether missing or default material must abort startup.
     *
     * @param configured       explicit {@code security.reject-default-secrets}, null when unset
     * @param activeProfiles   comma-separated {@code spring.profiles.active}
     * @param onKubernetes     whether {@code KUBERNETES_SERVICE_HOST} is set
     * @param underTestRunner  whether a test framework is on the call stack
     */
    static boolean resolveStrict(Boolean configured, String activeProfiles, boolean onKubernetes,
                                 boolean underTestRunner) {
        if (configured != null) {
            return configured;
        }
        if (onKubernetes) {
            return true;
        }
        if (underTestRunner) {
            return false;
        }
        List<String> profiles = activeProfiles == null ? List.of()
                : Arrays.stream(activeProfiles.split(","))
                        .map(p -> p.trim().toLowerCase(Locale.ROOT))
                        .filter(p -> !p.isEmpty())
                        .toList();
        if (profiles.isEmpty()) {
            // A service launched from the IDE or start-all.ps1 carries no profile. Every packaged
            // deployment does (helm: prod, CE entrypoint: ce), so this is the local dev laptop.
            return false;
        }
        for (String profile : profiles) {
            if (DEV_PROFILES.contains(profile) || profile.contains("test")) {
                return false;
            }
        }
        return true;
    }

    private static boolean runningUnderTestFramework() {
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            String className = frame.getClassName();
            if (className.startsWith("org.junit.")
                    || className.startsWith("org.springframework.test.")
                    || className.startsWith("org.springframework.boot.test.")) {
                return true;
            }
        }
        return false;
    }

    static int parseWriteVersion(String raw) {
        String value = trim(raw).toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            return DEFAULT_WRITE_VERSION;
        }
        if (value.startsWith("v")) {
            value = value.substring(1);
        }
        return switch (value) {
            case "1" -> 1;
            case "2" -> 2;
            default -> throw new IllegalStateException(
                    "credential.encryption.write-version must be 1 or 2, got '" + raw + "'");
        };
    }

    private static boolean isKnownDefaultPassword(String password) {
        String value = password.trim();
        return LEGACY_DEFAULT_PASSWORD.equals(value) || LEGACY_DEFAULT_PASSWORD_SHORT.equals(value);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String generateEphemeralPassword() {
        byte[] bytes = new byte[48];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static String generateEphemeralSalt() {
        byte[] bytes = new byte[16];
        SECURE_RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /**
     * True when no usable password/salt was configured and this process runs on random material
     * that dies with it (a dev laptop without the env vars). Anything that REWRITES stored data
     * under the current key must refuse in that state: a row encrypted with an ephemeral key is
     * unreadable after the next restart, which would turn a startup sweep into data loss. New
     * writes still encrypt (same behaviour credentials have always had in that mode).
     */
    public boolean isUsingEphemeralMaterial() {
        return ephemeralMaterial;
    }

    /** True when new writes produce the v2 envelope and the HKDF-keyed HMAC. */
    public boolean isWritingV2() {
        return writeV2;
    }

    /** Key id of the current material (8 hex chars), for rotation diagnostics. */
    public String currentKeyId() {
        return current.kid();
    }

    // ------------------------------------------------------------------
    // Key material
    // ------------------------------------------------------------------

    /**
     * One key generation: HKDF-derived AES-GCM data key and HMAC lookup key, the public key id,
     * the raw password (legacy HMAC key of every hash stored before v2) and the legacy CBC
     * encryptor that reads v1 envelopes written under this generation.
     */
    private record KeyMaterial(byte[] dataKey, byte[] lookupKey, String kid, byte[] ikm, byte[] hkdfSalt,
                               byte[] legacyHmacKey, TextEncryptor legacy) {

        static KeyMaterial of(String password, String salt) {
            byte[] ikm = password.getBytes(StandardCharsets.UTF_8);
            byte[] hkdfSalt = salt.getBytes(StandardCharsets.UTF_8);
            return new KeyMaterial(
                    hkdf(ikm, hkdfSalt, HKDF_INFO_DATA_KEY.getBytes(StandardCharsets.UTF_8), KEY_BYTES),
                    hkdf(ikm, hkdfSalt, HKDF_INFO_LOOKUP_KEY.getBytes(StandardCharsets.UTF_8), KEY_BYTES),
                    HexFormat.of().formatHex(
                            hkdf(ikm, hkdfSalt, HKDF_INFO_KEY_ID.getBytes(StandardCharsets.UTF_8), KEY_ID_BYTES)),
                    ikm, hkdfSalt, ikm, Encryptors.text(password, salt));
        }
    }

    /**
     * HKDF-SHA256 (RFC 5869) extract-then-expand, JDK only. Domain separation: neither the AES
     * data key nor the lookup MAC key is the configured password, and each is derived under its
     * own info label, so one tells nothing about the other.
     */
    static byte[] hkdf(byte[] ikm, byte[] salt, byte[] info, int outputLength) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(salt.length == 0 ? new byte[32] : salt, "HmacSHA256"));
            byte[] prk = mac.doFinal(ikm);
            mac.init(new SecretKeySpec(prk, "HmacSHA256"));
            byte[] output = new byte[outputLength];
            byte[] block = new byte[0];
            int position = 0;
            for (int counter = 1; position < outputLength; counter++) {
                mac.update(block);
                mac.update(info);
                mac.update((byte) counter);
                block = mac.doFinal();
                int take = Math.min(block.length, outputLength - position);
                System.arraycopy(block, 0, output, position, take);
                position += take;
            }
            return output;
        } catch (Exception e) {
            throw new IllegalStateException("HKDF-SHA256 unavailable", e);
        }
    }

    // ------------------------------------------------------------------
    // Encrypt / decrypt
    // ------------------------------------------------------------------

    /**
     * Encrypt a plaintext value.
     *
     * <p>Returns the input unchanged if it is null, blank or already in the envelope this
     * instance writes. In v2 mode a v1 envelope, or a v2 envelope sealed under a key id other than
     * the current one (the previous generation during a rotation), is re-sealed under the current
     * key; if that fails the value is returned untouched, since losing a readable row is worse
     * than leaving it in the old format. This re-seal path is migration, so it is never refused by
     * {@code legacy-reads=deny} and does not count as a legacy read.
     *
     * @param plaintext The value to encrypt
     * @return The encrypted value with "ENC:" prefix, or the input if null/blank/already encrypted
     */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return plaintext;
        }
        if (isEncrypted(plaintext)) {
            if (!writeV2 || isCurrentV2(plaintext)) {
                return plaintext;
            }
            try {
                String body = plaintext.substring(ENCRYPTED_PREFIX.length());
                String clear = body.startsWith(V2_BODY_PREFIX) ? decryptV2(body) : decryptLegacyValue(body);
                return encryptV2(clear);
            } catch (RuntimeException e) {
                log.warn("Could not re-seal a credential envelope under the current key; leaving it as-is: {}",
                        e.getMessage());
                return plaintext;
            }
        }
        return writeV2 ? encryptV2(plaintext) : encryptV1(plaintext);
    }

    private String encryptV1(String plaintext) {
        try {
            return ENCRYPTED_PREFIX + current.legacy().encrypt(plaintext);
        } catch (Exception e) {
            log.error("Failed to encrypt value: {}", e.getMessage());
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    private String encryptV2(String plaintext) {
        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            SECURE_RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(current.dataKey(), "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            cipher.updateAAD(aad(current.kid()));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] envelope = new byte[iv.length + sealed.length];
            System.arraycopy(iv, 0, envelope, 0, iv.length);
            System.arraycopy(sealed, 0, envelope, iv.length, sealed.length);
            return ENCRYPTED_PREFIX + V2_BODY_PREFIX + current.kid() + "."
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(envelope);
        } catch (Exception e) {
            log.error("Failed to encrypt value: {}", e.getMessage());
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    /**
     * Decrypt a value in either envelope format.
     *
     * <p>A value without the "ENC:" prefix is a row that predates encryption: it is counted and
     * returned as-is, or rejected when {@code credential.encryption.plaintext-reads=deny}.
     *
     * @param ciphertext The value to decrypt (may be plaintext for backward compatibility)
     * @return The decrypted value, or the input if null/blank/not encrypted
     */
    public String decrypt(String ciphertext) {
        return decrypt(ciphertext, null);
    }

    private String decrypt(String ciphertext, String fieldName) {
        if (ciphertext == null || ciphertext.isBlank()) {
            return ciphertext;
        }
        if (!isEncrypted(ciphertext)) {
            recordPlaintextRead(fieldName);
            if (denyPlaintextReads) {
                throw new IllegalStateException("Refusing to read an unencrypted credential value"
                        + (fieldName == null ? "" : " for field '" + fieldName + "'")
                        + " (credential.encryption.plaintext-reads=deny)");
            }
            return ciphertext;
        }
        String body = ciphertext.substring(ENCRYPTED_PREFIX.length());
        if (body.startsWith(V2_BODY_PREFIX)) {
            return decryptV2(body);
        }
        legacyReads.incrementAndGet();
        if (denyLegacyReads) {
            throw new IllegalStateException("Refusing to read a legacy (unauthenticated CBC) credential envelope"
                    + (fieldName == null ? "" : " for field '" + fieldName + "'")
                    + " (credential.encryption.legacy-reads=deny)");
        }
        return decryptLegacyValue(body);
    }

    private String decryptV2(String body) {
        int separator = body.indexOf('.', V2_BODY_PREFIX.length());
        if (separator < 0) {
            throw new IllegalStateException("Decryption failed: malformed v2 envelope (no key id separator)");
        }
        String kid = body.substring(V2_BODY_PREFIX.length(), separator);
        KeyMaterial material = current.kid().equals(kid) ? current
                : (previous != null && previous.kid().equals(kid)) ? previous : null;
        if (material == null) {
            throw new IllegalStateException("Decryption failed: no encryption key with id '" + kid + "' is loaded "
                    + "(put the material that wrote it on credential.encryption.previous-password/previous-salt)");
        }
        try {
            byte[] envelope = Base64.getUrlDecoder().decode(body.substring(separator + 1));
            if (envelope.length <= GCM_IV_BYTES) {
                throw new IllegalStateException("envelope too short");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(material.dataKey(), "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, envelope, 0, GCM_IV_BYTES));
            cipher.updateAAD(aad(kid));
            byte[] plain = cipher.doFinal(envelope, GCM_IV_BYTES, envelope.length - GCM_IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Failed to decrypt value (kid={}): {}", kid, e.getMessage());
            throw new IllegalStateException("Decryption failed", e);
        }
    }

    private String decryptLegacyValue(String body) {
        try {
            return current.legacy().decrypt(body);
        } catch (Exception primaryFailure) {
            if (previous != null) {
                try {
                    return previous.legacy().decrypt(body);
                } catch (Exception ignored) {
                    // fall through to the primary failure, which is the actionable one
                }
            }
            log.error("Failed to decrypt value: {}", primaryFailure.getMessage());
            throw new IllegalStateException("Decryption failed", primaryFailure);
        }
    }

    private static byte[] aad(String kid) {
        return (AAD_DOMAIN + "|" + kid).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Check if a value is encrypted (has the "ENC:" prefix, either version).
     *
     * @param value The value to check
     * @return true if the value starts with "ENC:", false otherwise
     */
    public boolean isEncrypted(String value) {
        return value != null && value.startsWith(ENCRYPTED_PREFIX);
    }

    /** True when the value is a v2 (authenticated) envelope. */
    public boolean isV2(String value) {
        return value != null && value.startsWith(ENCRYPTED_PREFIX + V2_BODY_PREFIX);
    }

    /** True when the value is a v2 envelope sealed under the CURRENT key id. */
    boolean isCurrentV2(String value) {
        return isV2(value) && value.startsWith(ENCRYPTED_PREFIX + V2_BODY_PREFIX + current.kid() + ".");
    }

    /**
     * True when a startup sweep should rewrite this stored value: it is plaintext; or this
     * instance writes v2 and the value is a v1 envelope or a v2 envelope under another key id
     * (a rotation is only finished once no row carries the previous kid).
     */
    public boolean needsReencryption(String stored) {
        if (stored == null || stored.isBlank()) {
            return false;
        }
        if (!isEncrypted(stored)) {
            return true;
        }
        return writeV2 && !isCurrentV2(stored);
    }

    // ------------------------------------------------------------------
    // Plaintext-read counter
    // ------------------------------------------------------------------

    /**
     * Number of unencrypted credential values this process has read. Non-zero and growing means
     * rows are still stored in clear; it must stay at zero before plaintext-reads=deny is set.
     */
    public long plaintextReadCount() {
        return plaintextReads.get();
    }

    /**
     * Number of legacy (v1, unauthenticated CBC) envelopes this process has decrypted for a
     * caller (the sweeps' re-seal path is not counted). It must stay at zero after the sweeps
     * have drained before {@code credential.encryption.legacy-reads=deny} is set.
     */
    public long legacyReadCount() {
        return legacyReads.get();
    }

    /**
     * Publishes {@link #plaintextReadCount()} as the counter {@code credential.plaintext.read}
     * (Prometheus: {@code credential_plaintext_read_total}). Setter-injected and optional so a
     * context without a registry still gets a working encryptor.
     *
     * @param registry the meter registry, when the application context has one
     */
    @Autowired(required = false)
    public void bindTo(io.micrometer.core.instrument.MeterRegistry registry) {
        if (registry == null) {
            return;
        }
        io.micrometer.core.instrument.FunctionCounter
                .builder("credential.plaintext.read", plaintextReads, AtomicLong::doubleValue)
                .description("Credential values read without an encryption envelope")
                .register(registry);
        io.micrometer.core.instrument.FunctionCounter
                .builder("credential.legacy.read", legacyReads, AtomicLong::doubleValue)
                .description("Credential values read from the legacy v1 (CBC) envelope")
                .register(registry);
    }

    private void recordPlaintextRead(String fieldName) {
        plaintextReads.incrementAndGet();
        String key = fieldName == null ? "(unnamed)" : fieldName;
        if (plaintextReadFieldsSeen.add(key)) {
            log.warn("credential_plaintext_read: a value for field '{}' is stored unencrypted", key);
        } else {
            log.debug("credential_plaintext_read: field '{}'", key);
        }
    }

    // ------------------------------------------------------------------
    // Map helpers
    // ------------------------------------------------------------------

    /**
     * Encrypt all sensitive string values in a map.
     * Non-sensitive fields and non-string values are left unchanged.
     *
     * @param data The map containing potentially sensitive fields
     * @return A new map with sensitive string values encrypted, or the input if null
     */
    public Map<String, Object> encryptSensitiveFields(Map<String, Object> data) {
        if (data == null) return null;
        Map<String, Object> result = new LinkedHashMap<>(data);
        for (var entry : result.entrySet()) {
            if (isSensitiveField(entry.getKey()) && entry.getValue() instanceof String s) {
                result.put(entry.getKey(), encrypt(s));
            }
        }
        return result;
    }

    /**
     * Decrypt all sensitive string values in a map.
     * Non-sensitive fields and non-string values are left unchanged.
     * Plaintext values (without ENC: prefix) are returned as-is and counted (see class doc).
     *
     * @param data The map containing potentially encrypted sensitive fields
     * @return A new map with sensitive string values decrypted, or the input if null
     */
    public Map<String, Object> decryptSensitiveFields(Map<String, Object> data) {
        if (data == null) return null;
        Map<String, Object> result = new LinkedHashMap<>(data);
        for (var entry : result.entrySet()) {
            if (isSensitiveField(entry.getKey()) && entry.getValue() instanceof String s) {
                result.put(entry.getKey(), decrypt(s, entry.getKey()));
            }
        }
        return result;
    }

    /**
     * True when at least one sensitive string in the map is stored in clear (no
     * {@value #ENCRYPTED_PREFIX} prefix).
     *
     * @param data a raw map as read from the database
     * @return true if a rewrite through {@link #encryptSensitiveFields(Map)} would encrypt something
     */
    public boolean hasPlaintextSensitiveField(Map<String, Object> data) {
        if (data == null) return false;
        for (var entry : data.entrySet()) {
            if (isSensitiveField(entry.getKey()) && entry.getValue() instanceof String s
                    && !s.isBlank() && !isEncrypted(s)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when a rewrite through {@link #encryptSensitiveFields(Map)} would change the map: a
     * sensitive value in clear, or (in v2 mode) a sensitive value still in the v1 envelope. This
     * is what the startup sweeps test, so a row already in the target format is never touched.
     */
    public boolean needsReencryption(Map<String, Object> data) {
        if (data == null) return false;
        for (var entry : data.entrySet()) {
            if (isSensitiveField(entry.getKey()) && entry.getValue() instanceof String s
                    && needsReencryption(s)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // HMAC
    // ------------------------------------------------------------------

    /**
     * HMAC-SHA256 used to store a lookup hash (API keys, capability tokens). In v2 mode it is
     * keyed on an HKDF sub-key; in v1 mode on the raw password (the pre-v2 behaviour). LOOK UP
     * with {@link #hmacHashCandidates(String)}, never with this alone.
     *
     * @param value The value to hash
     * @return Hex-encoded HMAC-SHA256 hash, or the input if null/blank
     */
    public String hmacHash(String value) {
        if (value == null || value.isBlank()) return value;
        return hmacHex(writeV2 ? current.lookupKey() : current.legacyHmacKey(), value);
    }

    /**
     * Every hash a stored value can legitimately carry, the one {@link #hmacHash} writes first:
     * the HKDF-keyed and the password-keyed form, for the current and (when configured) previous
     * material. Without it, switching the write key would orphan every stored API key and token.
     *
     * @param value The value to hash
     * @return distinct hex hashes, current write form first; empty when null or blank
     */
    public List<String> hmacHashCandidates(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        candidates.add(hmacHash(value));
        candidates.add(hmacHex(current.lookupKey(), value));
        candidates.add(hmacHex(current.legacyHmacKey(), value));
        if (previous != null) {
            candidates.add(hmacHex(previous.lookupKey(), value));
            candidates.add(hmacHex(previous.legacyHmacKey(), value));
        }
        return List.copyOf(candidates);
    }

    private static String hmacHex(byte[] key, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC hash failed", e);
        }
    }
}
