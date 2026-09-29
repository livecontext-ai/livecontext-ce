package com.apimarketplace.storage.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ByteRanges.singleRange - the one Range shape the signed proxy serves")
class ByteRangesTest {

    @Test
    @DisplayName("the three single-range forms a video element sends are forwarded, normalised")
    void singleRanges() {
        assertThat(ByteRanges.singleRange("bytes=0-")).isEqualTo("bytes=0-");
        assertThat(ByteRanges.singleRange("bytes=100-199")).isEqualTo("bytes=100-199");
        assertThat(ByteRanges.singleRange("bytes=-500")).isEqualTo("bytes=-500");
        assertThat(ByteRanges.singleRange(" bytes = 5 - 9 ")).isEqualTo("bytes=5-9");
    }

    @ParameterizedTest(name = "\"{0}\" is not served as a range")
    @ValueSource(strings = {"", "bytes=", "bytes=-", "bytes=0-1,5-9", "items=0-9", "bytes=9-1", "bytes=-0",
            "bytes=a-b", "bytes=99999999999999999999-99999999999999999999"})
    void everythingElseIsNull(String header) {
        assertThat(ByteRanges.singleRange(header)).isNull();
    }

    @Test
    @DisplayName("no header, no range")
    void absent() {
        assertThat(ByteRanges.singleRange(null)).isNull();
    }
}
