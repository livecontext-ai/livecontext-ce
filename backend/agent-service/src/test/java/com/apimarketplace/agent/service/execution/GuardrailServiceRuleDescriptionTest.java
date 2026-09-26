package com.apimarketplace.agent.service.execution;

import com.apimarketplace.agent.client.dto.execution.GuardrailRequestDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GuardrailService rule descriptions")
class GuardrailServiceRuleDescriptionTest {

    @Test
    @DisplayName("a typed rule without a description tells the model what its type means, not 'Check for this issue'")
    void typedRuleGetsMeaningfulDescription() {
        assertThat(GuardrailService.defaultDescription("toxic_language")).contains("toxic");
        assertThat(GuardrailService.defaultDescription("prompt_injection")).contains("override instructions");
        assertThat(GuardrailService.defaultDescription(null)).isEqualTo("Check for this issue");
    }

    @Test
    @DisplayName("the two-argument rule keeps working: type and action are optional")
    void previousArityStillBuilds() {
        GuardrailRequestDto.RuleDto rule = new GuardrailRequestDto.RuleDto("pii", "No personal data");
        assertThat(rule.type()).isNull();
        assertThat(rule.action()).isNull();
    }
}
