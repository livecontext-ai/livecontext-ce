package com.apimarketplace.agent.tools.authz;

import com.apimarketplace.agent.tools.authz.ToolAuthorizationScope.QuestionReach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where a question can be put, per kind of execution.
 *
 * <p>Every row here is a real context the platform produces, written in the shape it really
 * has rather than the shape it is convenient to assume. That matters more for this predicate
 * than for most: the reason it exists at all is that a scheduled run is byte-identical to
 * somebody typing, as far as the credentials go, and the one honest signal is a marker the
 * producer sets.
 */
@DisplayName("ToolAuthorizationScope.questionReach - where a question can go")
class ToolAuthorizationScopeQuestionReachTest {

    @Nested
    @DisplayName("IN_APP: somebody is watching")
    class InApp {

        @Test
        @DisplayName("a person typing in the general chat")
        void interactiveChat() {
            assertThat(ToolAuthorizationScope.questionReach(watchedChat())).isEqualTo(QuestionReach.IN_APP);
        }

        @Test
        @DisplayName("a person typing in an agent's own chat")
        void agentBackedChat() {
            Map<String, Object> creds = watchedChat();
            // An agent-backed chat is exempt from the AUTHORIZATION card by product rule, and
            // that rule has nothing to do with this one: somebody is still sitting there.
            creds.put("__agentId__", "agent-1");

            assertThat(ToolAuthorizationScope.questionReach(creds)).isEqualTo(QuestionReach.IN_APP);
        }
    }

    @Nested
    @DisplayName("CHANNEL: nobody is watching, but a reply can still land")
    class Channel {

        @Test
        @DisplayName("a scheduled run, which looks exactly like somebody typing")
        void scheduledRun() {
            Map<String, Object> creds = watchedChat();
            // The sync path mints a stream id unconditionally, so a schedule carries a
            // conversation AND a stream and no task id. Only the marker tells them apart,
            // which is the entire reason this predicate does not try to derive it.
            creds.put("__unattendedRun__", true);

            assertThat(ToolAuthorizationScope.questionReach(creds)).isEqualTo(QuestionReach.CHANNEL);
        }

        @Test
        @DisplayName("a task run, which is not promptable and still has somewhere for an answer to land")
        void taskRun() {
            Map<String, Object> creds = new HashMap<>();
            creds.put("conversationId", "conv-1");
            creds.put("__taskId__", "task-1");
            creds.put("__unattendedRun__", true);

            // The widening worth naming: a task agent CAN ask its owner, because a task keeps a
            // conversation and a reply arriving tomorrow still continues the right thread.
            assertThat(ToolAuthorizationScope.questionReach(creds)).isEqualTo(QuestionReach.CHANNEL);
        }

        @Test
        @DisplayName("a webhook run with no stream at all")
        void noStreamButAConversation() {
            Map<String, Object> creds = new HashMap<>();
            creds.put("conversationId", "conv-1");
            creds.put("__unattendedRun__", true);

            // A card needs a stream; a channel message does not. Requiring one here would rule
            // out the runs the channel exists to serve.
            assertThat(ToolAuthorizationScope.questionReach(creds)).isEqualTo(QuestionReach.CHANNEL);
        }
    }

    @Nested
    @DisplayName("NONE: asking would take an answer and drop it")
    class None {

        @Test
        @DisplayName("a sub-agent, whose parent has moved on by the time anyone replies")
        void subAgent() {
            Map<String, Object> creds = watchedChat();
            creds.put("__agent_depth__", 1);
            creds.put("__unattendedRun__", true);

            assertThat(ToolAuthorizationScope.questionReach(creds)).isEqualTo(QuestionReach.NONE);
        }

        @Test
        @DisplayName("a workflow node, whose run has completed by then")
        void workflowNode() {
            Map<String, Object> creds = watchedChat();
            creds.put("__workflowRunId__", "run-1");
            creds.put("__unattendedRun__", true);

            assertThat(ToolAuthorizationScope.questionReach(creds)).isEqualTo(QuestionReach.NONE);
        }

        @Test
        @DisplayName("an execution with no conversation, so a reply has no thread to return to")
        void noConversation() {
            Map<String, Object> creds = new HashMap<>();
            creds.put("__unattendedRun__", true);

            assertThat(ToolAuthorizationScope.questionReach(creds)).isEqualTo(QuestionReach.NONE);
        }

        @Test
        @DisplayName("no credentials at all")
        void nullCredentials() {
            assertThat(ToolAuthorizationScope.questionReach(null)).isEqualTo(QuestionReach.NONE);
        }
    }

    @Test
    @DisplayName("a sub-agent is refused even while its parent chat is being watched")
    void depthIsRefusedEvenOnAWatchedChat() {
        Map<String, Object> creds = watchedChat();
        creds.put("__agent_depth__", 1);

        // The parent chat IS on screen, and this still answers NONE, through the first rule
        // rather than the second: isUserPromptable already excludes depth, so a sub-agent
        // never reaches IN_APP and then meets its own clause. Both paths agree, which is what
        // makes the ordering safe to read either way round.
        assertThat(ToolAuthorizationScope.isUserPromptable(creds)).isFalse();
        assertThat(ToolAuthorizationScope.questionReach(creds)).isEqualTo(QuestionReach.NONE);
    }

    /** A person typing: a conversation and a live stream, nothing marked. */
    private static Map<String, Object> watchedChat() {
        Map<String, Object> creds = new HashMap<>();
        creds.put("conversationId", "conv-1");
        creds.put("__streamId__", "stream-1");
        return creds;
    }
}
