package com.apimarketplace.conversation.service.ai;

import java.util.List;
import java.util.Map;

/** Test-only bridge to the package-private {@code ConversationAgentService.turnSensitivity}. */
public final class ConversationAgentServiceTurnSensitivityAccess {

    private ConversationAgentServiceTurnSensitivityAccess() {
    }

    public static String turnSensitivity(List<Map<String, Object>> toolResults) {
        return ConversationAgentService.turnSensitivity(toolResults).name();
    }
}
