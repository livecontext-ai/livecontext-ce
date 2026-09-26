package com.apimarketplace.orchestrator.services.approvalchannel;

import com.apimarketplace.orchestrator.services.channel.ChatChannelConnector;
import com.apimarketplace.orchestrator.services.channel.ChatChannelConnectorRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("ApprovalChannelNotifierRegistry")
class ApprovalChannelNotifierRegistryTest {

    @Test
    @DisplayName("a dedicated notifier wins; a connector alone is served by the generic notifier; the rest is empty")
    void resolution() {
        ApprovalChannelNotifier telegram = mock(ApprovalChannelNotifier.class);
        when(telegram.channelId()).thenReturn("telegram");
        ChatChannelConnector telegramConnector = mock(ChatChannelConnector.class);
        when(telegramConnector.channelId()).thenReturn("telegram");
        ChatChannelConnector slack = mock(ChatChannelConnector.class);
        when(slack.channelId()).thenReturn("slack");
        ConnectorApprovalNotifier generic = mock(ConnectorApprovalNotifier.class);
        ApprovalChannelNotifier boundSlack = mock(ApprovalChannelNotifier.class);
        when(generic.boundTo(slack)).thenReturn(boundSlack);

        ApprovalChannelNotifierRegistry registry = new ApprovalChannelNotifierRegistry(List.of(telegram),
                new ChatChannelConnectorRegistry(List.of(telegramConnector, slack)), generic);

        // Telegram keeps its own notifier (photos), even though it also has a connector.
        assertThat(registry.forChannel("TELEGRAM")).containsSame(telegram);
        // A provider added as a connector is an approval channel with nothing else to register.
        assertThat(registry.forChannel("slack")).containsSame(boundSlack);
        assertThat(registry.forChannel("fax")).isEmpty();
        assertThat(registry.forChannel(" ")).isEmpty();
        assertThat(registry.forChannel(null)).isEmpty();
    }
}
