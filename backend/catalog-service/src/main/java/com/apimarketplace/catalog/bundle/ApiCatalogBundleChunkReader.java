package com.apimarketplace.catalog.bundle;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Reads a stored bundle payload in slices, so serving it costs a buffer instead
 * of the whole blob.
 *
 * <p><b>Why slices and not one read.</b> The payload is ~24 MB of gzip. Loading
 * it whole puts a humongous allocation straight into G1's old generation on
 * every request: measured in production, all 25 GC pauses of a 3-hour-old pod
 * were young-only, old gen sat at 605 MB of unexamined promoted garbage, and the
 * working set reached 1206 MiB of a 1280 MiB limit. Slicing keeps the cost
 * bounded by the slice, whatever the catalog grows to.
 *
 * <p><b>What a mid-response failure looks like.</b> The reads now happen after
 * the response has been committed, so a database error partway through
 * truncates the body instead of producing a clean 500 - the status line and the
 * {@code ETag} have already gone out. That is a real change from serving bytes
 * held in hand, and it is the price of not holding them. It is safe because the
 * envelope is signed and every client verifies it, so a short payload is
 * rejected rather than applied; and {@code writeBinary} refuses to under-run the
 * length it declared, so the truncation cannot pass as a complete bundle.
 *
 * <p><b>Why not stream straight from the database.</b> A single streamed
 * {@code getBinaryStream} would hold its connection for the whole response
 * write, which is exactly the multi-minute connection pinning that
 * {@code open-in-view: false} was set to prevent. Each slice here is its own
 * short statement, so the connection is returned between slices and a slow
 * client cannot hold one.
 *
 * <p><b>On the schema.</b> These statements qualify the table, like every other
 * hand-written statement in this package. Leaving them unqualified would have
 * worked, but only by resting on how each environment happens to pin the schema
 * on the connection - {@code currentSchema} in the local JDBC URL, PgBouncer's
 * {@code connect_query} in the cloud, {@code search_path} on the CE monolith -
 * three different mechanisms, none of them visible from here. The qualifier is
 * read from the schema Hibernate is configured with, falling back to
 * {@code catalog}: that is the one value that is already correct in every
 * environment, including the CE monolith, which deliberately leaves it blank and
 * spans a dozen schemas.
 *
 * <p><b>Why this is consistent.</b> {@code payload_gz} is write-once: it is set
 * when a bundle row is built and never updated afterwards (activation only
 * flips {@code is_active}). Slices of one version therefore always compose back
 * into the same bytes, verified against the live row: reassembling the slices
 * reproduced the payload's exact MD5 and length.
 *
 * <p><b>What the cost model rests on.</b> A slice is only a direct read of the
 * chunks it spans while the value is TOASTed and NOT compressed; a compressed
 * one would have to be decompressed from its start for every slice, making ~96
 * sequential reads quadratic. That used to hold by accident - the column was
 * EXTENDED and Postgres merely failed to compress bytes that are already gzip -
 * which made it a property of the data rather than of the schema. {@code V482}
 * sets the column to EXTERNAL, so it is now guaranteed. Nothing here, and
 * nothing in any test, could have told the difference: it would have shown up
 * as a slow endpoint in production and nowhere else.
 */
@Component
public class ApiCatalogBundleChunkReader {

    /**
     * Slice size, deliberately a quarter of a megabyte.
     *
     * <p>G1 sizes its regions at heap/2048, clamped to [1 MB, 32 MB]; a
     * production pod's ~896 MB heap therefore gives 1 MB regions, and any
     * allocation over half a region - 512 KB - is humongous. A 1 MB slice would
     * have been humongous itself, allocated straight into the old generation on
     * every read, which is the behaviour this class exists to remove. 256 KB
     * stays an ordinary young-generation array while keeping the number of round
     * trips modest: ~96 reads for a 24 MB payload, ~0.3 ms each.
     */
    static final int CHUNK_BYTES = 256 * 1024;

    /** Unquoted identifiers Postgres folds to lower case without complaint. */
    private static final java.util.regex.Pattern SAFE_SCHEMA =
            java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final JdbcTemplate jdbcTemplate;
    private final String schema;

    ApiCatalogBundleChunkReader(
            JdbcTemplate jdbcTemplate,
            @Value("${spring.jpa.properties.hibernate.default_schema:}") String configuredSchema) {
        this.jdbcTemplate = jdbcTemplate;
        // Blank on the CE monolith, which spans a dozen schemas and resolves them
        // through search_path instead. The table lives in `catalog` there too.
        String resolved = configuredSchema == null || configuredSchema.isBlank()
                ? "catalog"
                : configuredSchema.trim();
        // An identifier goes into the statement text, where a placeholder cannot
        // stand in for it. Only an operator can set this, so this is not about
        // injection: it is that a value needing quotes would otherwise produce a
        // statement that fails at the first download rather than at startup.
        if (!SAFE_SCHEMA.matcher(resolved).matches()) {
            throw new IllegalArgumentException(
                    "hibernate.default_schema must be a plain SQL identifier, got: " + resolved);
        }
        this.schema = resolved;
    }

    /**
     * Byte length of a version's stored payload, or 0 when the row has none.
     *
     * <p>Cheap on purpose: {@code octet_length} answers from the TOAST pointer
     * header without detoasting (measured: 0.036 ms, one buffer touched), so
     * this never pulls the payload it is measuring.
     *
     * <p>No {@code @Transactional}: it joins whatever the caller has open, or
     * runs on its own. An earlier {@code REQUIRES_NEW} here suspended the
     * service's read-only transaction and took a SECOND connection for the
     * probe, so every download held two at once - and a fleet polling in phase
     * would have filled the pool with outer transactions each waiting the full
     * connection timeout for an inner one.
     */
    public long payloadLength(long version) {
        // queryForList, not queryForObject: an unknown version is a 404 upstream,
        // not an exception. queryForObject throws on an empty result set, which
        // would turn "no such bundle" into a 500.
        List<Long> found = jdbcTemplate.queryForList(
                "SELECT COALESCE(octet_length(payload_gz), 0) FROM " + schema + ".api_catalog_bundles WHERE version = ?",
                Long.class, version);
        return found.isEmpty() || found.get(0) == null ? 0L : found.get(0);
    }

    /**
     * One slice, starting at {@code offset} bytes (0-based) and at most
     * {@code length} bytes long. Returns an empty array past the end.
     */
    public byte[] readChunk(long version, long offset, int length) {
        if (offset < 0 || length <= 0) {
            throw new IllegalArgumentException("offset must be >= 0 and length > 0, got " + offset + "/" + length);
        }
        if (offset > Integer.MAX_VALUE - 1L) {
            // octet_length is an int, so a payload can never reach here. Fail
            // rather than wrap silently if that ever stops being true.
            throw new IllegalArgumentException("offset beyond addressable payload: " + offset);
        }
        // Two details a mock cannot check, both found against a real database:
        // substring() on bytea is 1-based, and its position/length arguments are
        // int - binding them as bigint fails function resolution outright.
        List<byte[]> found = jdbcTemplate.queryForList(
                "SELECT substring(payload_gz from ? for ?) FROM " + schema + ".api_catalog_bundles WHERE version = ?",
                byte[].class, (int) (offset + 1), length, version);
        return found.isEmpty() || found.get(0) == null ? new byte[0] : found.get(0);
    }
}
