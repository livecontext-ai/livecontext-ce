package com.apimarketplace.auth.web.version;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for {@link VersionComparator#isUpdateAvailable}. */
class VersionComparatorTest {

    @Test
    @DisplayName("a newer latest core is an available update")
    void newerIsAvailable() {
        assertThat(VersionComparator.isUpdateAvailable("0.1.0", "0.2.0")).isTrue();
        assertThat(VersionComparator.isUpdateAvailable("1.2.3", "1.2.4")).isTrue();
        assertThat(VersionComparator.isUpdateAvailable("1.2", "1.2.1")).isTrue(); // missing patch defaults to 0
    }

    @Test
    @DisplayName("equal or older latest is not an update")
    void equalOrOlderIsNot() {
        assertThat(VersionComparator.isUpdateAvailable("0.2.0", "0.2.0")).isFalse();
        assertThat(VersionComparator.isUpdateAvailable("0.2.0", "0.1.0")).isFalse();
        assertThat(VersionComparator.isUpdateAvailable("2.0.0", "1.9.9")).isFalse();
    }

    @Test
    @DisplayName("comparison is numeric per component, not lexical")
    void numericNotLexical() {
        assertThat(VersionComparator.isUpdateAvailable("1.9.0", "1.10.0")).isTrue();  // 10 > 9
        assertThat(VersionComparator.isUpdateAvailable("1.10.0", "1.9.0")).isFalse();
    }

    @Test
    @DisplayName("a pre-release/build suffix and a leading v are ignored")
    void stripsSuffixAndVPrefix() {
        // SNAPSHOT of 0.1.0 is not behind the 0.1.0 release (same core).
        assertThat(VersionComparator.isUpdateAvailable("0.1.0-SNAPSHOT", "0.1.0")).isFalse();
        assertThat(VersionComparator.isUpdateAvailable("0.1.0-SNAPSHOT", "0.2.0")).isTrue();
        assertThat(VersionComparator.isUpdateAvailable("v1.2.3", "v1.2.4")).isTrue();
        assertThat(VersionComparator.isUpdateAvailable("1.2.3+abc", "1.2.4+def")).isTrue();
    }

    @Test
    @DisplayName("blank or unparseable versions are never flagged as behind")
    void unparseableIsConservative() {
        assertThat(VersionComparator.isUpdateAvailable(null, "1.0.0")).isFalse();
        assertThat(VersionComparator.isUpdateAvailable("1.0.0", null)).isFalse();
        assertThat(VersionComparator.isUpdateAvailable("", "1.0.0")).isFalse();
        assertThat(VersionComparator.isUpdateAvailable("dev", "1.0.0")).isFalse();
        assertThat(VersionComparator.isUpdateAvailable("1.0.0", "latest")).isFalse();
        assertThat(VersionComparator.isUpdateAvailable("1.0.0.0", "1.0.0.1")).isFalse(); // 4 components rejected
    }
}
