package com.apimarketplace.conversation.service.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The turn started by an answer that came back from a chat is treated like the other runs
 * nobody is watching.
 *
 * <p>{@code CHANNEL_REPLY} has to appear in two independent lists, in two files, that nothing
 * ties together. Missing from either one it fails silently and differently, which is why this
 * exists rather than trusting a reader to notice both.
 *
 * <p>Out of {@code AgentContextBuilder}'s external-source test, the turn is marked as watched.
 * Its own next question is then painted as a card, into a stream with no subscriber, and the
 * conversation that had just been rescued from exactly that failure falls back into it one turn
 * later.
 *
 * <p>Out of {@code CONVERSATION_STREAMING_SYNC_SOURCES}, the run does not stream into the
 * conversation page. It still happens, and somebody who opens the chat after answering on their
 * phone watches nothing at all until it is over.
 */
@DisplayName("CHANNEL_REPLY is an unattended source everywhere it has to be")
class ChannelReplySourceTest {

    private static final String SOURCE = "CHANNEL_REPLY";

    @Test
    @DisplayName("it streams into the conversation, like a schedule does")
    void streamsIntoTheConversation() throws Exception {
        Field field = ConversationAgentService.class
                .getDeclaredField("CONVERSATION_STREAMING_SYNC_SOURCES");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<String> sources = (Set<String>) field.get(null);

        assertThat(sources)
                .as("a turn started by a chat answer is attached to a conversation somebody can "
                        + "open, exactly like a scheduled run, so it has to stream there")
                .contains(SOURCE)
                .contains("SCHEDULE");
    }

    @Test
    @DisplayName("it counts as an external source, so its own questions go back to the chat")
    void countsAsExternal() {
        // Read from the source rather than by running the builder: the flag is computed inside
        // a method that needs a whole agent context to call, and what is being pinned is one
        // membership test. A behavioural test here would assert the same literal through more
        // machinery, and fail for reasons that have nothing to do with it.
        String line = externalSourceLine();

        assertThat(line)
                .as("AgentContextBuilder decides __unattendedRun__ from this list; out of it, a "
                        + "CHANNEL_REPLY turn is treated as watched and paints its next question "
                        + "into a stream nobody reads")
                .contains("\"" + SOURCE + "\".equals(request.getSource())");
    }

    private static String externalSourceLine() {
        // Two candidates because the module runs both from its own directory and from the
        // reactor root, the same pair the other cross-module readers here use.
        return Stream.of("src/main/java/com/apimarketplace/conversation/service/ai/callback/AgentContextBuilder.java",
                        "backend/conversation-service/src/main/java/com/apimarketplace/conversation/service/ai/callback/AgentContextBuilder.java")
                .map(Path::of)
                .filter(Files::exists)
                .findFirst()
                .map(path -> {
                    try {
                        return Files.readString(path).lines()
                                .filter(l -> l.contains("boolean isExternalSource"))
                                .findFirst()
                                .orElseThrow(() -> new IllegalStateException(
                                        "isExternalSource is gone or renamed: this test is what keeps "
                                                + "CHANNEL_REPLY in step with it, so fix it rather than "
                                                + "deleting it"));
                    } catch (Exception e) {
                        throw new IllegalStateException("unreadable: " + path, e);
                    }
                })
                .orElseThrow(() -> new IllegalStateException(
                        "AgentContextBuilder not found from " + Path.of("").toAbsolutePath()));
    }
}
