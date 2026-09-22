package com.apimarketplace.catalog.repository;

import com.apimarketplace.catalog.domain.ApiCatalogBundleEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface ApiCatalogBundleRepository extends JpaRepository<ApiCatalogBundleEntity, Long> {

    Optional<ApiCatalogBundleEntity> findByVersion(Long version);

    Optional<ApiCatalogBundleEntity> findFirstByActiveTrue();

    Optional<ApiCatalogBundleEntity> findTopByOrderByVersionDesc();

    /**
     * Identity of the active bundle WITHOUT its payload.
     *
     * <p>{@code payload_gz} is an eager {@code byte[]} on the entity, so every
     * {@link #findFirstByActiveTrue()} drags ~24 MB of gzip into heap. Answering
     * "has the bundle changed?" needs three scalars, so this projection selects
     * only those - the column list deliberately omits {@code payload_gz} and the
     * blob is never read. Used by the conditional-GET path on
     * {@code /api/catalog/public/bundles/latest}.
     *
     * <p>Returns a list rather than an {@code Optional} on purpose: the partial
     * unique index {@code idx_api_catalog_bundles_one_active} already guarantees
     * at most one row, and a list cannot throw
     * {@code IncorrectResultSizeDataAccessException} if that invariant is ever
     * broken by a manual fix-up. {@code ORDER BY version DESC} makes the pick
     * deterministic in that case rather than arbitrary.
     */
    @Query("""
            SELECT b.version AS version,
                   b.checksum AS checksum,
                   CASE WHEN b.payloadGz IS NULL THEN 0 ELSE 1 END AS servable,
                   CASE WHEN b.generationPrices IS NULL THEN 0 ELSE 1 END AS pricesStored
            FROM ApiCatalogBundleEntity b
            WHERE b.active = true
            ORDER BY b.version DESC
            """)
    List<ActiveBundleMeta> findActiveMetadata();

    /**
     * Every bundle row for the admin list, WITHOUT its payload.
     *
     * <p>{@code findAll()} here would load one ~24 MB {@code payload_gz} per
     * stored bundle into an 896 MB heap, and the admin view renders none of
     * those bytes. Same reason as {@link #findActiveMetadata()}: the column
     * list omits the blob.
     */
    @Query("""
            SELECT b.id AS id,
                   b.version AS version,
                   b.schemaVersion AS schemaVersion,
                   b.checksum AS checksum,
                   b.signingKeyId AS signingKeyId,
                   b.issuer AS issuer,
                   b.apiCount AS apiCount,
                   b.toolCount AS toolCount,
                   b.rawBytesSize AS rawBytesSize,
                   b.active AS active,
                   b.importedAt AS importedAt,
                   b.activatedAt AS activatedAt
            FROM ApiCatalogBundleEntity b
            ORDER BY b.version DESC
            """)
    List<BundleSummary> findAllSummariesNewestFirst();

    /**
     * The active bundle's version and its stored prices, without the payload.
     *
     * <p>Same ordering as {@link #findActiveMetadata()} on purpose: the
     * validator and the price re-offer must agree on which row is "the" active
     * one, or a tick could send row A's checksum and re-offer row B's prices.
     */
    @Query("""
            SELECT b.version AS version, b.generationPrices AS generationPrices
            FROM ApiCatalogBundleEntity b
            WHERE b.active = true
            ORDER BY b.version DESC
            """)
    List<ActiveBundlePrices> findActivePrices();

    /** Payload-free prices of the active row. */
    interface ActiveBundlePrices {
        Long getVersion();

        String getGenerationPrices();
    }

    /** Payload-free view of a bundle row for the admin list. */
    interface BundleSummary {
        Long getId();

        Long getVersion();

        Integer getSchemaVersion();

        String getChecksum();

        String getSigningKeyId();

        String getIssuer();

        Integer getApiCount();

        Integer getToolCount();

        Integer getRawBytesSize();

        boolean getActive();

        Instant getImportedAt();

        Instant getActivatedAt();
    }

    /**
     * Everything the served envelope needs EXCEPT the payload.
     *
     * <p>The download used to load the entity, which drags the ~24 MB
     * {@code payload_gz} into heap as a G1 humongous allocation on every
     * request. The bytes are now read in slices by
     * {@code ApiCatalogBundleChunkReader}, so serving must not touch the column
     * here either - hence a projection whose column list stops at the metadata.
     */
    @Query("""
            SELECT b.version AS version,
                   b.schemaVersion AS schemaVersion,
                   b.checksum AS checksum,
                   b.signature AS signature,
                   b.signingKeyId AS signingKeyId,
                   b.issuer AS issuer,
                   b.apiCount AS apiCount,
                   b.toolCount AS toolCount,
                   b.rawBytesSize AS rawBytesSize
            FROM ApiCatalogBundleEntity b
            WHERE b.active = true
            ORDER BY b.version DESC
            """)
    List<ServingView> findActiveServingView();

    /** As {@link #findActiveServingView()} for one specific version. */
    @Query("""
            SELECT b.version AS version,
                   b.schemaVersion AS schemaVersion,
                   b.checksum AS checksum,
                   b.signature AS signature,
                   b.signingKeyId AS signingKeyId,
                   b.issuer AS issuer,
                   b.apiCount AS apiCount,
                   b.toolCount AS toolCount,
                   b.rawBytesSize AS rawBytesSize
            FROM ApiCatalogBundleEntity b
            WHERE b.version = :version
            """)
    List<ServingView> findServingViewByVersion(long version);

    /** The served envelope's fields, without the payload. */
    interface ServingView {
        Long getVersion();

        Integer getSchemaVersion();

        String getChecksum();

        String getSignature();

        String getSigningKeyId();

        String getIssuer();

        Integer getApiCount();

        Integer getToolCount();

        Integer getRawBytesSize();
    }

    /** Payload-free identity of a bundle row. */
    interface ActiveBundleMeta {
        String getChecksum();

        /** 1 when {@code payload_gz} is present, 0 for a CE-side applied row. */
        Integer getServable();

        /**
         * 1 when this row carries the generation prices its bundle declared.
         *
         * <p>A CE that applied its bundle before those were persisted has 0 here,
         * and must NOT go conditional yet: a 304 carries no payload, so it would
         * have nothing to re-offer and would silently stop pricing integrations
         * whose provider key arrives later. One full fetch stores them (the
         * already-applied path in {@code ApiCatalogBundleApplier}), and every
         * tick after that can be conditional.
         */
        Integer getPricesStored();
    }

    /**
     * Atomically clear the active flag on every row. Callers run this inside
     * the same TX as a subsequent {@code save(newActive)} so the partial
     * unique index {@code idx_api_catalog_bundles_one_active} is never violated.
     */
    @Modifying
    @Query("UPDATE ApiCatalogBundleEntity b SET b.active = false WHERE b.active = true")
    int deactivateAll();
}
