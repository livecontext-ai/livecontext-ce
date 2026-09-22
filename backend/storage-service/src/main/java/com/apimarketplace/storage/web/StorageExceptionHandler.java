package com.apimarketplace.storage.web;

import com.apimarketplace.common.storage.exception.QuotaExceededException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Turns a storage quota refusal into 413 for every storage-service endpoint.
 *
 * <p>storage-service had no exception handler at all, so {@link QuotaExceededException}, a
 * RuntimeException, fell through to Spring's default and came back as a bare 500. Only the
 * PUBLIC {@code FileController} avoided that, by catching it inline on two of its methods. Every
 * other writer was told "server error" when the real answer was "this account is full":
 * {@code InternalFileController.upload} (workflow step outputs, the largest consumer of storage
 * by volume), {@code /upload-bytes} (which caught nothing at all), and
 * {@code /generic-upload} (media generation, chat attachments, any tool returning binary).
 *
 * <p>Why an advice rather than three more catch blocks: the next upload endpoint added here
 * inherits the rule instead of rediscovering it, which is how {@code /upload-bytes} came to catch
 * nothing at all.
 *
 * <p><b>Scope, stated plainly:</b> this fixes the WIRE. It does not yet reach the caller, because
 * {@code StorageClient.genericUpload} still collapses every failure, 413 included, into
 * {@code null}, so a caller still cannot tell a full account from a broken bucket. Making that
 * distinction travel is a separate change with its own blast radius (five call sites across three
 * services, two of which swallow exceptions today). What this buys now is that the truthful status
 * exists on the wire for that change to read, and that operators stop seeing 500s for a condition
 * that is not a server fault.
 *
 * <p>The body matches what the public {@code FileController} already returns for the same
 * condition, so both routes look identical to a client.
 *
 * <p><b>Microservice only, and that is not an oversight.</b> The CE monolith component-scans both
 * {@code com.apimarketplace.storage} and {@code com.apimarketplace.orchestrator}, and the latter's
 * {@code GlobalExceptionHandler} already maps this exception to 413 with its own body shape.
 * Registering a second, unordered advice for the same exception there would make the winner a
 * matter of bean ordering and could silently change the body every orchestrator caller reads. CE
 * therefore keeps the handler it already had; this one covers the deployment that had none.
 */
@RestControllerAdvice
@ConditionalOnProperty(name = "deployment.mode", havingValue = "microservice", matchIfMissing = true)
public class StorageExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(StorageExceptionHandler.class);

    @ExceptionHandler(QuotaExceededException.class)
    public ResponseEntity<Map<String, Object>> handleQuotaExceeded(QuotaExceededException e) {
        logger.warn("Storage write refused by quota: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Map.of("error", "Storage quota exceeded"));
    }
}
