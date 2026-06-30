package com.apimarketplace.conversation.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * HTTP client for communicating with conversation-service stream endpoints
 * Refactored to use direct service calls instead of RestTemplate
 */
@Service
public class StreamApiClient {

    private static final Logger logger = LoggerFactory.getLogger(StreamApiClient.class);

    @Autowired
    private StreamService streamService;

    /**
     * Create a new stream
     */
    public boolean createStream(String streamId, String conversationId, String userId) {
        try {
            var stream = streamService.createStream(conversationId, streamId, userId);

            if (stream != null) {
                logger.info("✅ [STREAM API] Created stream {} for conversation {}", streamId, conversationId);
                return true;
            } else {
                // null = the conversation no longer exists (deleted / never-persisted / out-of-scope);
                // the stream row is skipped on purpose, not a server error. The chat is unaffected.
                logger.debug("[STREAM API] Stream {} not registered: conversation {} no longer exists",
                        streamId, conversationId);
                return false;
            }

        } catch (Exception e) {
            logger.error("❌ [STREAM API] Error creating stream {}: {}", streamId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Mark stream as completed
     */
    public boolean markStreamAsCompleted(String streamId) {
        try {
            streamService.markStreamAsCompleted(streamId);
            logger.info("✅ [STREAM API] Stream {} marked as completed", streamId);
            return true;
        } catch (Exception e) {
            logger.error("❌ [STREAM API] Error marking stream {} as completed: {}", streamId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Mark stream as stopped
     */
    public boolean markStreamAsStopped(String streamId) {
        try {
            streamService.markStreamAsStopped(streamId);
            logger.info("✅ [STREAM API] Stream {} marked as stopped", streamId);
            return true;
        } catch (Exception e) {
            logger.error("❌ [STREAM API] Error marking stream {} as stopped: {}", streamId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Mark stream as error
     */
    public boolean markStreamAsError(String streamId, String errorMessage) {
        try {
            streamService.markStreamAsError(streamId, errorMessage);
            logger.info("✅ [STREAM API] Stream {} marked as error", streamId);
            return true;
        } catch (Exception e) {
            logger.error("❌ [STREAM API] Error marking stream {} as error: {}", streamId, e.getMessage(), e);
            return false;
        }
    }


}
