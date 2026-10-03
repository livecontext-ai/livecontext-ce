package com.apimarketplace.catalog.config;

import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the commons-io version this module resolves (CASA readiness audit C, finding #7).
 *
 * <p>backend/pom.xml manages commons-compress 1.27.1, which is built against commons-io 2.16.1.
 * commons-io itself was managed by nothing, so catalog-service took 2.15.1 from swagger-parser
 * 2.1.22: an older commons-io under a newer commons-compress. The fix manages commons-io in the
 * parent pom; this test fails if that pin is dropped and the swagger-parser version wins again.
 */
@DisplayName("commons-io resolves at or above the version commons-compress 1.27.1 needs")
class CommonsIoManagedVersionTest {

    private static final int[] MINIMUM = {2, 16, 1};

    @Test
    @DisplayName("the commons-io jar on the classpath is >= 2.16.1, not swagger-parser's 2.15.1")
    void commonsIoIsAtLeastTheVersionCommonsCompressIsBuiltAgainst() {
        String version = IOUtils.class.getPackage().getImplementationVersion();

        assertThat(version).as("commons-io jar manifest Implementation-Version").isNotBlank();
        assertThat(compare(parse(version), MINIMUM))
                .as("commons-io %s on the classpath, commons-compress 1.27.1 needs >= 2.16.1", version)
                .isGreaterThanOrEqualTo(0);
    }

    private static int[] parse(String version) {
        return Arrays.stream(version.split("[.-]"))
                .limit(3)
                .mapToInt(part -> part.chars().allMatch(Character::isDigit) ? Integer.parseInt(part) : 0)
                .toArray();
    }

    private static int compare(int[] actual, int[] minimum) {
        for (int i = 0; i < minimum.length; i++) {
            int a = i < actual.length ? actual[i] : 0;
            if (a != minimum[i]) {
                return Integer.compare(a, minimum[i]);
            }
        }
        return 0;
    }
}
