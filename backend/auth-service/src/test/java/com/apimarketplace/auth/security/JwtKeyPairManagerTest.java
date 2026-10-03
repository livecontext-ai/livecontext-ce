package com.apimarketplace.auth.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * CASA LC-088: the CE JWT signing key is created owner-only, and a key left world-readable by an
 * earlier version is tightened on the next start. The permission assertions need a POSIX
 * filesystem (Linux CI, the CE container); on Windows they are skipped, the round trip is not.
 */
@DisplayName("JwtKeyPairManager (LC-088)")
class JwtKeyPairManagerTest {

    @TempDir
    Path keysDir;

    private JwtKeyPairManager manager() {
        JwtKeyPairManager m = new JwtKeyPairManager();
        ReflectionTestUtils.setField(m, "keysPath", keysDir.toString());
        m.init();
        return m;
    }

    private boolean posix() {
        return Files.getFileAttributeView(keysDir, PosixFileAttributeView.class) != null;
    }

    @Test
    @DisplayName("a generated key pair is reloaded identically on the next start")
    void roundTrip() {
        String kid = manager().getKeyId();
        assertThat(manager().getKeyId()).isEqualTo(kid);
        assertThat(Files.exists(keysDir.resolve("jwt-private.key"))).isTrue();
    }

    @Test
    @DisplayName("the private key is created rw------- (never readable by group or others)")
    void privateKeyCreatedOwnerOnly() throws Exception {
        assumeTrue(posix(), "needs a POSIX filesystem");
        manager();

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(keysDir.resolve("jwt-private.key"))))
                .isEqualTo("rw-------");
    }

    @Test
    @DisplayName("a private key left 0644 by an earlier version is restricted to rw------- on load")
    void existingWorldReadableKeyIsTightened() throws Exception {
        assumeTrue(posix(), "needs a POSIX filesystem");
        String kid = manager().getKeyId();
        Path key = keysDir.resolve("jwt-private.key");
        Files.setPosixFilePermissions(key, PosixFilePermissions.fromString("rw-r--r--"));

        assertThat(manager().getKeyId()).isEqualTo(kid);
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(key))).isEqualTo("rw-------");
    }
}
