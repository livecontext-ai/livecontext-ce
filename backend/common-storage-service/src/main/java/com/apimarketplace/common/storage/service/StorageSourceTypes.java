package com.apimarketplace.common.storage.service;

/**
 * Canonical {@code source_type} values stamped onto {@code storage.storage} rows.
 *
 * <p>The {@code source_type} column classifies WHERE a stored row came from so the Storage
 * Explorer / Files panel can group and filter. These were previously inlined as bare string
 * literals at each call site; centralising them here keeps the producer and the consumer
 * (StorageExplorerController, FilesToolsProvider) in agreement on the exact spelling.</p>
 *
 * <p><b>Workflow vs generic:</b> {@link #STEP_OUTPUT} and {@link #INTERFACE_SCREENSHOT} are
 * produced by workflow nodes and carry real run context (epoch/spawn/itemIndex). {@link #S3_FILE}
 * is the neutral default used for generic / non-workflow uploads (catalog binaries, image-gen,
 * avatars, run clones) which stay on the epoch-0 path with no run coordinates.</p>
 */
public final class StorageSourceTypes {

    private StorageSourceTypes() {
    }

    /** A binary/file row indexed from object storage (default for generic, non-workflow uploads). */
    public static final String S3_FILE = "S3_FILE";

    /** A file uploaded by a user through the chat composer. */
    public static final String CHAT_ATTACHMENT = "CHAT_ATTACHMENT";

    /** A manual folder row (V313) - a directory sentinel with no payload. */
    public static final String FOLDER = "FOLDER";

    /** A file produced by a workflow step (Download File, Compression, Convert To File, SFTP, file tools). */
    public static final String STEP_OUTPUT = "STEP_OUTPUT";

    /** A PNG capture of a rendered interface, produced by an interface node. */
    public static final String INTERFACE_SCREENSHOT = "INTERFACE_SCREENSHOT";

    /** A PDF rendering of an interface, produced by an interface node (generatePdf=true). */
    public static final String INTERFACE_PDF = "INTERFACE_PDF";

    /** An MP4 recording of a rendered interface's animation, produced by an interface node (generateVideo=true). */
    public static final String INTERFACE_VIDEO = "INTERFACE_VIDEO";
}
