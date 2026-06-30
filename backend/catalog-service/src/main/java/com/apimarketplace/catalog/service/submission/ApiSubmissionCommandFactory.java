package com.apimarketplace.catalog.service.submission;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

@Component
public class ApiSubmissionCommandFactory {

    public ApiSubmissionCommand from(JsonNode submissionData, String userId) {
        if (submissionData == null) {
            throw new IllegalArgumentException("submissionData is required");
        }
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId is required to process the submission");
        }
        return new ApiSubmissionCommand(submissionData, userId);
    }
}
