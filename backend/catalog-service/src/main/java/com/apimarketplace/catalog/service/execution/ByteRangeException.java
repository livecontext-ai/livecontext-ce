package com.apimarketplace.catalog.service.execution;

/**
 * Raised when an endpoint declares a byte range and the call cannot satisfy it.
 *
 * <p>It fails the CALL rather than the part, and that is the whole point. Declaring the
 * bounds {@code required: true} in a seed only guards authoring time - {@code is_required}
 * is read by the node creator and the workflow validators, never by the execution path -
 * so a step SAVED before an endpoint gained its range keeps running and would otherwise
 * upload a zero-byte part for ever, answering 2xx each time.
 *
 * <p>The alternatives were both worse. Sending the whole file where a slice was asked for
 * corrupts the upload and is only discovered at finalize time or on playback. Sending an
 * empty part leaves the provider to decide what a zero-byte segment means, which is a
 * guess about someone else's API. A failed call names the two parameters that are missing
 * and stops there.
 *
 * <p>Modelled on {@link FileAttachmentException}, which refuses the same "green run, file
 * gone" shape on the JSON-body path.
 */
public class ByteRangeException extends RuntimeException {

    public ByteRangeException(String message) {
        super(message);
    }
}
