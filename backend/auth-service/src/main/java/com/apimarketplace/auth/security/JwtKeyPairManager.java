package com.apimarketplace.auth.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Set;

/**
 * Manages RSA key pair for JWT signing in embedded auth mode (CE).
 * Generates a 2048-bit RSA key pair on first startup and persists it to disk.
 * On subsequent starts, loads the existing key pair.
 *
 * Activated by: auth.mode=embedded
 */
@Component
@ConditionalOnProperty(name = "auth.mode", havingValue = "embedded")
public class JwtKeyPairManager {

    private static final Logger logger = LoggerFactory.getLogger(JwtKeyPairManager.class);
    private static final String KEY_ALGORITHM = "RSA";
    private static final int KEY_SIZE = 2048;
    private static final String PRIVATE_KEY_FILE = "jwt-private.key";
    private static final String PUBLIC_KEY_FILE = "jwt-public.key";
    /** rw------- : the private key must be readable by the service user only (CASA LC-088). */
    static final Set<PosixFilePermission> OWNER_ONLY =
            EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    @Value("${auth.jwt.keys-path:./data/keys}")
    private String keysPath;

    private KeyPair keyPair;

    @PostConstruct
    public void init() {
        try {
            Path keysDir = Path.of(keysPath);
            Path privateKeyPath = keysDir.resolve(PRIVATE_KEY_FILE);
            Path publicKeyPath = keysDir.resolve(PUBLIC_KEY_FILE);

            if (Files.exists(privateKeyPath) && Files.exists(publicKeyPath)) {
                keyPair = loadKeyPair(privateKeyPath, publicKeyPath);
                restrictExistingPrivateKey(privateKeyPath);
                logger.info("Loaded existing RSA key pair from {}", keysDir);
            } else {
                Files.createDirectories(keysDir);
                keyPair = generateKeyPair();
                saveKeyPair(keyPair, privateKeyPath, publicKeyPath);
                logger.info("Generated new RSA key pair and saved to {}", keysDir);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize JWT key pair", e);
        }
    }

    public PrivateKey getPrivateKey() {
        return keyPair.getPrivate();
    }

    public PublicKey getPublicKey() {
        return keyPair.getPublic();
    }

    public RSAPublicKey getRsaPublicKey() {
        return (RSAPublicKey) keyPair.getPublic();
    }

    /**
     * Returns the key ID used in JWKS. Derived from public key hash for stability.
     */
    public String getKeyId() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(keyPair.getPublic().getEncoded());
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash).substring(0, 8);
        } catch (NoSuchAlgorithmException e) {
            return "default-rsa";
        }
    }

    private KeyPair generateKeyPair() throws NoSuchAlgorithmException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance(KEY_ALGORITHM);
        generator.initialize(KEY_SIZE, new SecureRandom());
        return generator.generateKeyPair();
    }

    private void saveKeyPair(KeyPair kp, Path privateKeyPath, Path publicKeyPath) throws IOException {
        String privateKeyPem = "-----BEGIN PRIVATE KEY-----\n" +
                Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(kp.getPrivate().getEncoded()) +
                "\n-----END PRIVATE KEY-----\n";
        String publicKeyPem = "-----BEGIN PUBLIC KEY-----\n" +
                Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(kp.getPublic().getEncoded()) +
                "\n-----END PUBLIC KEY-----\n";

        writeOwnerOnly(privateKeyPath, privateKeyPem);
        Files.writeString(publicKeyPath, publicKeyPem);
    }

    /**
     * Creates the private key file owner-only AT CREATION on a POSIX filesystem, so there is no
     * window where the process umask (typically 0644) exposes the key, and writes it. A leftover
     * file (only the public half existed) is replaced. On a non-POSIX filesystem the file is
     * restricted best-effort and any failure is logged, never swallowed.
     */
    static void writeOwnerOnly(Path privateKeyPath, String content) throws IOException {
        Files.deleteIfExists(privateKeyPath);
        if (supportsPosix(privateKeyPath)) {
            Files.createFile(privateKeyPath, PosixFilePermissions.asFileAttribute(OWNER_ONLY));
        } else {
            Files.createFile(privateKeyPath);
            java.io.File file = privateKeyPath.toFile();
            boolean restricted = file.setReadable(false, false) && file.setReadable(true, true)
                    && file.setWritable(false, false) && file.setWritable(true, true);
            if (!restricted) {
                logger.warn("Could not restrict the JWT private key {} to its owner on this non-POSIX filesystem; "
                        + "restrict access to the keys directory yourself", privateKeyPath);
            }
        }
        Files.writeString(privateKeyPath, content, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    /**
     * Tightens a private key written by an earlier version (default umask, typically 0644) to
     * owner-only. A failure (file owned by another uid, read-only volume) is logged, not thrown:
     * the key is still usable and refusing to start would lock every user out.
     */
    static void restrictExistingPrivateKey(Path privateKeyPath) {
        if (!supportsPosix(privateKeyPath)) {
            return;
        }
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(privateKeyPath);
            if (!OWNER_ONLY.containsAll(perms)) {
                Files.setPosixFilePermissions(privateKeyPath, OWNER_ONLY);
                logger.warn("JWT private key {} was {}; restricted to rw-------",
                        privateKeyPath, PosixFilePermissions.toString(perms));
            }
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            logger.warn("JWT private key {} could not be restricted to its owner: {}", privateKeyPath, e.toString());
        }
    }

    private static boolean supportsPosix(Path path) {
        Path probe = path.toAbsolutePath().getParent();
        return probe != null && Files.getFileAttributeView(probe, PosixFileAttributeView.class) != null;
    }

    private KeyPair loadKeyPair(Path privateKeyPath, Path publicKeyPath) throws Exception {
        String privateKeyPem = Files.readString(privateKeyPath);
        String publicKeyPem = Files.readString(publicKeyPath);

        byte[] privateKeyBytes = Base64.getMimeDecoder().decode(
                privateKeyPem
                        .replace("-----BEGIN PRIVATE KEY-----", "")
                        .replace("-----END PRIVATE KEY-----", "")
                        .replaceAll("\\s", "")
        );
        byte[] publicKeyBytes = Base64.getMimeDecoder().decode(
                publicKeyPem
                        .replace("-----BEGIN PUBLIC KEY-----", "")
                        .replace("-----END PUBLIC KEY-----", "")
                        .replaceAll("\\s", "")
        );

        KeyFactory keyFactory = KeyFactory.getInstance(KEY_ALGORITHM);
        PrivateKey privateKey = keyFactory.generatePrivate(new PKCS8EncodedKeySpec(privateKeyBytes));
        PublicKey publicKey = keyFactory.generatePublic(new X509EncodedKeySpec(publicKeyBytes));

        return new KeyPair(publicKey, privateKey);
    }
}
