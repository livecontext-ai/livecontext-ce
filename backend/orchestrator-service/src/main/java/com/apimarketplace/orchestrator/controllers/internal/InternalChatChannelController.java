package com.apimarketplace.orchestrator.controllers.internal;

import com.apimarketplace.orchestrator.services.channel.AgentAuthorizationChannelService;
import com.apimarketplace.orchestrator.services.channel.AgentAuthorizationChannelService.DeliveryRequest;
import com.apimarketplace.orchestrator.services.channel.AgentAuthorizationChannelService.DeliveryResult;
import com.apimarketplace.orchestrator.services.channel.ChatQuestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The service-to-service entry point of the chat-channel feature: agent-service asking, on
 * behalf of an agent nobody is watching, for something to be put in front of a person.
 *
 * <p>Two things, now. A permission, which is answered with two buttons, and a question, which
 * is answered with a pick or the person's own words. They are separate endpoints rather than
 * one with a kind field because the request bodies have nothing in common but the workspace,
 * and the replies mean different things to the agent.
 *
 * <p>The destination is resolved HERE rather than handed out for the caller to
 * resolve: it is owned by the surface that delivers to it, and a caller holding its
 * own copy would eventually write to a chat the user disconnected yesterday.
 */
@RestController
@RequestMapping("/api/internal/chat-channels")
public class InternalChatChannelController {

    private static final Logger logger = LoggerFactory.getLogger(InternalChatChannelController.class);

    private final AgentAuthorizationChannelService authorizationService;
    private final ChatQuestionService questionService;

    public InternalChatChannelController(AgentAuthorizationChannelService authorizationService,
                                         ChatQuestionService questionService) {
        this.authorizationService = authorizationService;
        this.questionService = questionService;
    }

    /**
     * Ask the workspace's chat for permission on behalf of an agent nobody is
     * watching.
     *
     * <p>Always 200, with the outcome in the body. The caller is a tool call that
     * is about to park: a transport error there would be turned into "the tool
     * failed", when what actually happened is "nobody could be asked", and those
     * two must not read the same to the agent.
     */
    @PostMapping("/authorization-request")
    public ResponseEntity<DeliveryResult> requestAuthorization(@RequestBody DeliveryRequest request) {
        try {
            return ResponseEntity.ok(authorizationService.deliver(request));
        } catch (Exception ex) {
            logger.warn("[chat-auth] delivery raised: {}", ex.getMessage());
            return ResponseEntity.ok(new DeliveryResult(
                    AgentAuthorizationChannelService.DeliveryStatus.FAILED, null, null, null, ex.getMessage()));
        }
    }

    /**
     * Put an agent's questions to the person in the workspace's chat.
     *
     * <p>Always 200 for the same reason the endpoint above is: the caller is deciding what to
     * tell an agent, and "the tool failed" and "nobody could be asked" lead it to completely
     * different behaviour. Only one of them will have happened.
     */
    @PostMapping("/question-request")
    public ResponseEntity<ChatQuestionService.DeliveryResult> requestQuestion(
            @RequestBody ChatQuestionService.QuestionRequest request) {
        try {
            return ResponseEntity.ok(questionService.deliverQuestions(request));
        } catch (Exception ex) {
            logger.warn("[chat-question] delivery raised: {}", ex.getMessage());
            return ResponseEntity.ok(new ChatQuestionService.DeliveryResult(
                    ChatQuestionService.DeliveryStatus.FAILED, null, null, null, ex.getMessage()));
        }
    }
}
