package com.apimarketplace.monolith;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.apimarketplace.conversation.client.ConversationClient;
import com.apimarketplace.conversation.controller.internal.InternalTaskConversationController;
import com.apimarketplace.conversation.dto.ConversationDto;
import com.apimarketplace.conversation.service.ConversationCommandService;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.core.type.filter.RegexPatternTypeFilter;
import org.springframework.test.web.client.MockMvcClientHttpRequestFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

/**
 * CASA LC-066 in CE: agent-service's {@link ConversationClient} opens the per-task conversation of a
 * RESTRICTED delegated task on {@code /api/internal/conversations/agent/{a}/task/{t}}, and the CE
 * monolith calls itself for it ({@code services.conversation-url} = the embedded server). The
 * monolith used to exclude the whole conversation-service internal controller package, so the route
 * answered 404, the client returned null and every RESTRICTED delegated task was refused in CE.
 */
@DisplayName("CE monolith: the RESTRICTED task conversation route")
class MonolithTaskConversationRouteTest {

    private static final String INTERNAL_PACKAGE = "com.apimarketplace.conversation.controller.internal";

    @Test
    @DisplayName("regression: the CE component scan mounts InternalTaskConversationController")
    void ceScanMountsTaskConversationController() {
        assertThat(ceScannedControllers(INTERNAL_PACKAGE))
            .as("excluded, ConversationClient#findOrCreateTaskConversation gets a 404 in CE")
            .contains(InternalTaskConversationController.class.getName());
    }

    @Test
    @DisplayName("the CE scan still leaves out the internal controllers that need cloud streaming wiring")
    void ceScanStillExcludesTheStreamingInternalControllers() {
        // Narrowed, not dropped: these are replaced by monolith re-hosts (MonolithInternalChatController,
        // CeConversationStubController); mounting them would map the same paths twice.
        assertThat(ceScannedControllers(INTERNAL_PACKAGE))
            .containsExactly(InternalTaskConversationController.class.getName());
    }

    @Test
    @DisplayName("ConversationClient's task-conversation calls resolve on the controller the CE scan mounts")
    void clientCallsResolveOnTheMountedController() {
        ConversationCommandService commands = mock(ConversationCommandService.class);
        ConversationDto created = new ConversationDto();
        created.setId("task-conv-1");
        when(commands.findOrCreateTaskConversation(eq("user-1"), eq("org-1"), eq("agent-1"), eq("task-1"), any()))
            .thenReturn(created);
        when(commands.findTaskConversation("org-1", "agent-1", "task-1")).thenReturn(Optional.of(created));
        when(commands.findTaskConversation("org-1", "agent-1", "task-2")).thenReturn(Optional.empty());

        MockMvc mvc = MockMvcBuilders.standaloneSetup(new InternalTaskConversationController(commands)).build();
        ConversationClient client = new ConversationClient(
            new RestTemplate(new MockMvcClientHttpRequestFactory(mvc)), "http://localhost:8080");

        assertThat(client.findOrCreateTaskConversation("agent-1", "task-1", "user-1", "Task", "org-1"))
            .isEqualTo("task-conv-1");
        assertThat(client.findTaskConversation("agent-1", "task-1", "user-1", "org-1")).isEqualTo("task-conv-1");
        assertThat(client.findTaskConversation("agent-1", "task-2", "user-1", "org-1"))
            .as("no conversation yet is a 404 the client reads as null, not an error")
            .isNull();
    }

    /** Runs the real CE @ComponentScan filters of MonolithApplication over one package. */
    private static Set<String> ceScannedControllers(String basePackage) {
        ComponentScan scan = MonolithApplication.class.getAnnotation(ComponentScan.class);
        ClassPathScanningCandidateComponentProvider provider = new ClassPathScanningCandidateComponentProvider(false);
        provider.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        Arrays.stream(scan.excludeFilters())
            .filter(filter -> filter.type() == FilterType.REGEX)
            .flatMap(filter -> Arrays.stream(filter.pattern()))
            .forEach(regex -> provider.addExcludeFilter(new RegexPatternTypeFilter(Pattern.compile(regex))));
        return provider.findCandidateComponents(basePackage).stream()
            .map(BeanDefinition::getBeanClassName)
            .collect(Collectors.toSet());
    }
}
