package com.apimarketplace.catalog.service.execution;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.Collection;
import java.util.Map;

/**
 * Encodes an {@code application/x-www-form-urlencoded} request body for catalog API tools whose
 * {@code execution.request.bodyType=form-urlencoded}.
 *
 * <p>Used for AWS-style query-protocol APIs (SNS, SQS, …) and any legacy HTML-form-style
 * endpoint that wants flat key/value pairs in the body rather than a JSON envelope.
 *
 * <p>Takes the body-filtered parameters map produced by
 * {@code HttpExecutionService.prepareRequestBody} - so parameterType/dataType/bodyPath
 * resolution is already applied - and transforms it into a {@link MultiValueMap} that
 * Spring's {@code FormHttpMessageConverter} serializes as url-encoded form data.
 *
 * <p>Collection values (arrays) are expanded into repeated keys. Null values are skipped.
 * All non-string values are converted via {@link String#valueOf(Object)}.
 */
@Component
@Slf4j
public class FormUrlencodedBodyEncoder {

    /**
     * Build a form-urlencoded body from the already-filtered body map.
     *
     * @param bodyMap flat map produced by {@code prepareRequestBody}; keys are param names
     *                (may include dots for AWS-style flattening, e.g. {@code MessageAttributes.entry.1.Value.DataType})
     * @return a {@link MultiValueMap} suitable to pass as {@link org.springframework.http.HttpEntity} body
     */
    public MultiValueMap<String, String> encode(Map<String, Object> bodyMap) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        if (bodyMap == null || bodyMap.isEmpty()) {
            return body;
        }
        for (Map.Entry<String, Object> entry : bodyMap.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (key == null || key.isBlank() || value == null) {
                continue;
            }
            if (value instanceof Collection<?> coll) {
                for (Object item : coll) {
                    if (item != null) {
                        body.add(key, String.valueOf(item));
                    }
                }
            } else {
                body.add(key, String.valueOf(value));
            }
        }
        return body;
    }
}
