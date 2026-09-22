package com.apimarketplace.common.storage.retention;

import com.apimarketplace.common.storage.service.StorageRowCategories;
import com.apimarketplace.common.storage.service.StorageSourceTypes;

import java.util.Set;

/**
 * Which {@code storage.storage} rows execution-log retention is allowed to delete.
 *
 * <p><b>Allow-list, never deny-list.</b> A row class nobody has explicitly cleared
 * is kept. This is the same reflex as the {@code adoptRunContext} whitelist: the
 * cost of forgetting to allow a class is that some journal is retained too long,
 * and the cost of forgetting to deny one is that customer data is destroyed. Those
 * are not comparable, so the default has to be "keep". A source type introduced
 * next year is therefore safe on the day it ships, without anyone remembering
 * this file exists.
 *
 * <p><b>Why this table is not just "source type".</b> {@code STEP_OUTPUT} names
 * two entirely different things. With {@code storage_type = JSON} it is a step's
 * output payload, pure journal, held in the database. With
 * {@code storage_type = S3_FILE} it is a file the workflow PRODUCED: a generated
 * mp3, image or video that the user still owns and may still be using. Measured
 * in production on 2026-09-01: 40,848 of the former and 2,285 of the latter, the
 * latter carrying about 16 GB of object storage. Keying on the source type alone
 * would delete both.
 *
 * <p><b>A non-null {@code s3_key} is an absolute veto</b>, checked before anything
 * else. Object bytes live outside the database, so deleting such a row would
 * either orphan the object (paid for, unreachable) or, if the object went too,
 * destroy a deliverable. Nothing this class returns true for has an external
 * object, which makes "the purge never touches object storage" a property of the
 * code rather than a promise in a comment.
 *
 * <p><b>Quota accounting is NOT uniform across these classes, and assuming it was
 * would inflate a billed number permanently.</b> A JSON payload books
 * {@code STEP_OUTPUTS} and a TEXT row books {@code FILES}, so debiting one flat
 * category on delete would leave the FILES credit of every purged agent payload in
 * place forever, and {@code QuotaService.updateUsage} recomputes the tenant's
 * {@code used_bytes} from that breakdown, on a paid plan dimension.
 * {@link #breakdownCategoryFor} delegates to {@link StorageRowCategories}, which is
 * where that rule lives for the save path, the delete path and the nightly
 * reconciliation alike. It used to restate the rule here instead, and a second
 * spelling of a rule is a second answer waiting to happen: that is precisely how
 * the save path came to credit {@code FILES} for an object-storage row while the
 * delete path debited {@code STEP_OUTPUTS}.
 */
public final class ExecutionLogRowClasses {

    private ExecutionLogRowClasses() {
    }

    /** Row produced when a node was skipped: journal only, payload in the database. */
    public static final String SKIPPED_NODE = "SKIPPED_NODE";

    /** Out-of-row payloads written by AgentObservabilityService, excluded from the file browser. */
    public static final Set<String> AGENT_PAYLOAD_FILE_NAMES =
            Set.of("agent_message.txt", "tool_call_result.txt");

    private static final String JSON = "JSON";
    private static final String TEXT = "TEXT";

    /**
     * The breakdown category this row was CREDITED to when it was written, which
     * is the only category it may be debited from.
     *
     * <p>Answers with {@link StorageRowCategories#categoryFor}, the same call the save and delete
     * paths make, so the purge can never debit a bucket the write path did not credit. Debiting
     * the wrong one does not error: it leaves the original credit standing and inflates the
     * tenant's billed {@code used_bytes} for good.
     *
     * <p>The allow-list still gates the answer. A row class this file does not permit returns
     * null, so a future storage type cannot start being purged just because the classifier has an
     * opinion about which bucket it would belong to.
     *
     * <p>Note the argument order: {@code (sourceType, storageType)} here, the reverse of
     * {@link StorageRowCategories#categoryFor(String, String)}. Both take two Strings, so a swap
     * compiles; {@code ExecutionLogRowClassesTest} is what catches it, because a swapped call
     * answers null for every row this class allows.
     *
     * @return the category, or {@code null} for a row this class does not allow
     */
    public static String breakdownCategoryFor(String sourceType, String storageType) {
        if (TEXT.equals(storageType) && sourceType == null) {
            return StorageRowCategories.categoryFor(storageType, sourceType);
        }
        if (JSON.equals(storageType)) {
            return StorageRowCategories.categoryFor(storageType, sourceType);
        }
        return null;
    }

    /**
     * Whether retention may delete this row.
     *
     * @param sourceType  {@code source_type}, may be null (agent payloads carry none)
     * @param storageType {@code storage_type}
     * @param fileName    {@code file_name}, only consulted for the TEXT class
     * @param s3Key       {@code s3_key}; any non-blank value vetoes the row outright
     */
    public static boolean isPurgeableExecutionLog(String sourceType, String storageType,
                                                  String fileName, String s3Key) {
        if (s3Key != null && !s3Key.isBlank()) {
            // Veto. The bytes are in object storage, so this row is a file, whatever
            // else it claims to be.
            return false;
        }
        if (storageType == null) {
            return false;
        }
        if (JSON.equals(storageType)) {
            return SKIPPED_NODE.equals(sourceType)
                    || StorageSourceTypes.STEP_OUTPUT.equals(sourceType);
        }
        if (TEXT.equals(storageType)) {
            // Agent message / tool-result overflow. These have no source type at all,
            // so the file name is the only handle, and it must be an exact match: a
            // user-uploaded "agent_message.txt" would be a TEXT row WITH an s3_key,
            // already vetoed above.
            return sourceType == null && fileName != null
                    && AGENT_PAYLOAD_FILE_NAMES.contains(fileName);
        }
        return false;
    }
}
