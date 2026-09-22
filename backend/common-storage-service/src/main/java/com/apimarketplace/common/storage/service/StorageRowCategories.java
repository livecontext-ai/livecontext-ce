package com.apimarketplace.common.storage.service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Which storage breakdown category a {@code storage.storage} row belongs to.
 *
 * <p><b>Why this class exists.</b> The rule used to be written three times and the three copies
 * disagreed. {@code StorageService} credited every object-storage row to {@code FILES} on save,
 * debited it from {@code STEP_OUTPUTS} on delete (it looked at the source type only), and the
 * nightly reconciliation counted it in NEITHER category, because its predicate tested
 * {@code source_type IN ('S3_FILE', ...)} while {@code S3_FILE} is a <b>storage type</b>. Only
 * rows whose source type happened to be the literal {@code S3_FILE} (generic uploads) were
 * matched, so every file a workflow step or an interface node produced was invisible.
 *
 * <p>Measured on production on 2026-09-18, for the busiest tenant: 21 GB of ACTIVE rows, of which
 * the breakdown accounted for 4.9 GB. The missing 16 GB were 2105 {@code S3_FILE/STEP_OUTPUT}
 * rows plus the interface video, screenshot and PDF rows. Nothing errored: reconciliation is an
 * absolute set, so every night it quietly wrote the wrong total back over the correct one that
 * the incremental save path had maintained during the day.
 *
 * <p><b>Correcting an under-count RAISES a billed number, so the blast radius was measured, not
 * assumed.</b> The tenant gauge is the sum of these categories, so it moves from the under-counted
 * figure to the real one the first time a page is opened, not at the next nightly pass. Checked
 * against the whole production fleet on 2026-09-18, tenant by tenant and workspace by workspace:
 * the largest goes from 5,128 MB to 21 GB against a 100 GB cap, and NO tenant and NO workspace
 * crosses its hard limit, so the correction refuses no write that used to be allowed. One
 * workspace goes DOWN (1,864 kB to 648 kB) because its FILES row had been inflated by the
 * asymmetric delete described above. Trend charts snapshot the breakdown daily, so they show a
 * step on the day this ships; that is the correction becoming visible, not an upload.
 *
 * <p><b>The two categories are complements, deliberately.</b> {@code STEP_OUTPUTS} is defined as
 * "not a file row", not as "a JSON row". That makes total coverage a property of the code: a
 * storage type nobody has heard of yet is counted (mislabelled, visibly) instead of silently
 * dropped, which is the failure this class was written to end. A new file-shaped storage type
 * belongs in {@link #FILE_STORAGE_TYPES}, and that is the only edit it needs.
 *
 * <p><b>The SQL and the Java must agree</b>, so both are produced here: the reconciler builds its
 * predicates with {@link #filesSqlPredicate(String)} / {@link #stepOutputsSqlPredicate(String)}
 * from the same lists {@link #categoryFor(String, String)} reads.
 */
public final class StorageRowCategories {

    private StorageRowCategories() {
    }

    /** Files the user owns: uploads, chat attachments, and anything a node produced as a file. */
    public static final String FILES = "FILES";

    /** Step journal: the in-database payloads a run leaves behind. */
    public static final String STEP_OUTPUTS = "STEP_OUTPUTS";

    /**
     * {@code storage_type} values that make a row a file whatever its source type.
     *
     * <p>{@code S3_FILE} is the one that was missing and it is the one that carries the bytes:
     * the row is an index entry, the payload lives in object storage.
     */
    public static final List<String> FILE_STORAGE_TYPES = List.of("S3_FILE", "BINARY", "TEXT");

    /**
     * {@code source_type} values that make a row a file whatever its storage type.
     *
     * <p>Needed on top of the storage types because a chat attachment can be stored inline as
     * JSON, and a generic upload is stamped {@code S3_FILE} as a source type too.
     */
    public static final List<String> FILE_SOURCE_TYPES =
            List.of(StorageSourceTypes.S3_FILE, StorageSourceTypes.CHAT_ATTACHMENT);

    /**
     * The category a row is credited to on save and debited from on delete.
     *
     * <p>Both arguments may be null: a row with neither is treated as a step output, which is
     * where legacy rows predating the {@code source_type} column already sit.
     */
    public static String categoryFor(String storageType, String sourceType) {
        return isFileRow(storageType, sourceType) ? FILES : STEP_OUTPUTS;
    }

    /**
     * Whether the row holds a file the user owns, rather than a run's journal payload.
     *
     * <p>The STORAGE type is read first, because it is the fact: it says where the bytes are. The
     * source type only says what produced them, and reading it first is what made an object-storage
     * row look like a step output. The two checks are an OR, so the order does not change any
     * answer; it states which column decides.
     */
    public static boolean isFileRow(String storageType, String sourceType) {
        return (storageType != null && FILE_STORAGE_TYPES.contains(storageType))
                || (sourceType != null && FILE_SOURCE_TYPES.contains(sourceType));
    }

    /**
     * SQL predicate selecting the {@link #FILES} rows of {@code storage.storage}.
     *
     * <p>Both columns are wrapped in {@code COALESCE} so the expression is never NULL, which is
     * what lets {@link #stepOutputsSqlPredicate(String)} be its exact negation. Without it a row
     * with a null source type would evaluate to NULL and be dropped by BOTH predicates, which is
     * a smaller version of the bug this class replaces.
     *
     * @param alias the table alias used in the query, e.g. {@code "s"}
     */
    public static String filesSqlPredicate(String alias) {
        return "(COALESCE(" + alias + ".storage_type, '') IN (" + quotedList(FILE_STORAGE_TYPES) + ")"
                + " OR COALESCE(" + alias + ".source_type, '') IN (" + quotedList(FILE_SOURCE_TYPES) + "))";
    }

    /** SQL predicate selecting the {@link #STEP_OUTPUTS} rows: the exact complement of the above. */
    public static String stepOutputsSqlPredicate(String alias) {
        return "NOT " + filesSqlPredicate(alias);
    }

    /**
     * Renders a list of category constants as an SQL literal list.
     *
     * <p>The values are compile-time constants of this class, never caller input, so the
     * interpolation carries no injection surface. The single-quote escaping is there so a value
     * containing one would break the build's tests rather than the query.
     */
    private static String quotedList(List<String> values) {
        return values.stream()
                .map(v -> "'" + v.replace("'", "''") + "'")
                .collect(Collectors.joining(", "));
    }
}
