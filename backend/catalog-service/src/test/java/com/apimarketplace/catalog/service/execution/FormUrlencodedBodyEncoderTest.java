package com.apimarketplace.catalog.service.execution;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.MultiValueMap;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates that {@link FormUrlencodedBodyEncoder} correctly:
 * - transforms a flat body map into a Spring MultiValueMap
 * - expands collections into repeated keys
 * - preserves AWS-style dotted keys verbatim
 * - skips null values and handles empty input
 */
class FormUrlencodedBodyEncoderTest {

    private FormUrlencodedBodyEncoder encoder;

    @BeforeEach
    void setUp() {
        encoder = new FormUrlencodedBodyEncoder();
    }

    @Test
    @DisplayName("encodes a flat map with string values as single-entry MultiValueMap")
    void encodesFlatMap() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("Action", "Publish");
        input.put("Message", "hello world");
        input.put("TopicArn", "arn:aws:sns:us-east-1:1:MyTopic");

        MultiValueMap<String, String> body = encoder.encode(input);

        assertEquals(3, body.size());
        assertEquals("Publish", body.getFirst("Action"));
        assertEquals("hello world", body.getFirst("Message"));
        assertEquals("arn:aws:sns:us-east-1:1:MyTopic", body.getFirst("TopicArn"));
    }

    @Test
    @DisplayName("preserves AWS-style dotted keys verbatim (MessageAttribute.1.Value.DataType)")
    void preservesAwsDottedKeys() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("MessageAttribute.1.Name", "my_attr");
        input.put("MessageAttribute.1.Value.DataType", "String");
        input.put("MessageAttribute.1.Value.StringValue", "my_value");

        MultiValueMap<String, String> body = encoder.encode(input);

        assertEquals("my_attr", body.getFirst("MessageAttribute.1.Name"));
        assertEquals("String", body.getFirst("MessageAttribute.1.Value.DataType"));
        assertEquals("my_value", body.getFirst("MessageAttribute.1.Value.StringValue"));
    }

    @Test
    @DisplayName("expands Collection values into repeated keys")
    void expandsCollectionsIntoRepeatedKeys() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("tag", List.of("red", "green", "blue"));

        MultiValueMap<String, String> body = encoder.encode(input);

        assertEquals(3, body.get("tag").size());
        assertEquals("red",   body.get("tag").get(0));
        assertEquals("green", body.get("tag").get(1));
        assertEquals("blue",  body.get("tag").get(2));
    }

    @Test
    @DisplayName("converts non-string scalars to string via String.valueOf")
    void convertsNonStringScalars() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("count", 42);
        input.put("enabled", true);
        input.put("ratio", 0.75);

        MultiValueMap<String, String> body = encoder.encode(input);

        assertEquals("42",   body.getFirst("count"));
        assertEquals("true", body.getFirst("enabled"));
        assertEquals("0.75", body.getFirst("ratio"));
    }

    @Test
    @DisplayName("skips null values and blank keys")
    void skipsNullsAndBlanks() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("valid", "x");
        input.put("null_value", null);
        input.put("", "blank_key");

        MultiValueMap<String, String> body = encoder.encode(input);

        assertEquals(1, body.size());
        assertEquals("x", body.getFirst("valid"));
    }

    @Test
    @DisplayName("empty or null input map returns empty MultiValueMap")
    void emptyInput() {
        assertTrue(encoder.encode(null).isEmpty());
        assertTrue(encoder.encode(new LinkedHashMap<>()).isEmpty());
    }
}
