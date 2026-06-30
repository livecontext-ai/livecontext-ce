package com.apimarketplace.conversation.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("StreamConfig")
class StreamConfigTest {

    private StreamConfig createConfig(int timeoutMinutes)
            throws Exception {
        StreamConfig config = new StreamConfig();
        setField(config, "timeoutMinutes", timeoutMinutes);
        return config;
    }

    private void setField(Object obj, String fieldName, Object value) throws Exception {
        Field field = obj.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(obj, value);
    }

    @Nested
    @DisplayName("Getters")
    class Getters {

        @Test
        @DisplayName("should return timeoutMinutes")
        void shouldReturnTimeoutMinutes() throws Exception {
            StreamConfig config = createConfig(30);
            assertThat(config.getTimeoutMinutes()).isEqualTo(30);
        }
    }
}
