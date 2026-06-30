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
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

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

        Files.writeString(privateKeyPath, privateKeyPem);
        Files.writeString(publicKeyPath, publicKeyPem);

        // Restrict permissions on private key (best effort on non-POSIX systems)
        try {
            privateKeyPath.toFile().setReadable(false, false);
            privateKeyPath.toFile().setReadable(true, true);
            privateKeyPath.toFile().setWritable(false, false);
            privateKeyPath.toFile().setWritable(true, true);
        } catch (Exception ignored) {
            // Non-POSIX (Windows) - acceptable for CE self-hosted
        }
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
