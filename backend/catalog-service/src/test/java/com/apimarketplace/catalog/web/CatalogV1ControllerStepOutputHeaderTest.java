package com.apimarketplace.catalog.web;

import com.apimarketplace.catalog.domain.dto.ToolExecutionRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CatalogV1Controller - X-Lc-Step-Output")
class CatalogV1ControllerStepOutputHeaderTest {

    @Test
    @DisplayName("the header marks the request as a workflow step's output")
    void headerSetsStepOutput() {
        ToolExecutionRequest request = ToolExecutionRequest.builder().build();

        CatalogV1Controller.applyStepOutputHeader(request, "true");

        assertThat(request.isStepOutput()).isTrue();
    }

    @Test
    @DisplayName("an absent or other header value leaves the request unmarked")
    void absentOrOtherValueLeavesItUnset() {
        ToolExecutionRequest absent = ToolExecutionRequest.builder().build();
        ToolExecutionRequest other = ToolExecutionRequest.builder().build();

        CatalogV1Controller.applyStepOutputHeader(absent, null);
        CatalogV1Controller.applyStepOutputHeader(other, "yes");

        assertThat(absent.isStepOutput()).isFalse();
        assertThat(other.isStepOutput()).isFalse();
    }

    @Test
    @DisplayName("the flag cannot be posted in the request body: it is header-only, like the billing scope")
    void bodyCannotSetStepOutput() throws Exception {
        ToolExecutionRequest request = new ObjectMapper()
            .readValue("{\"parameters\":{},\"stepOutput\":true}", ToolExecutionRequest.class);

        assertThat(request.isStepOutput()).isFalse();
    }
}
