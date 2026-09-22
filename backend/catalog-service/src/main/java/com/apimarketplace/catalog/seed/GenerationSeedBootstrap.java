package com.apimarketplace.catalog.seed;

import com.apimarketplace.catalog.bundle.ApiCatalogGenerationPriceApplier;
import com.apimarketplace.catalog.repository.ApiCatalogBundleRepository;
import com.apimarketplace.catalog.service.generation.GenerationSpec;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Boot-time seed of the generation catalog (descriptors + their starting
 * prices) from a versioned classpath resource
 * ({@code catalog-seeds/generation-seed.json}), shipped inside the image so a
 * self-hosted install that has never reached the network still has generation
 * models it can call and be charged for.
 *
 * <p><b>The gap this closes.</b> A fresh self-hosted install boots its catalog
 * from {@code catalog-data.sql.gz}, whose {@code catalog.api_tools} COPY carries
 * no {@code generation_spec} column at all, so every endpoint arrives with a
 * NULL descriptor and {@code GenerationRegistry} resolves nothing. The starting
 * prices are worse off still: they are published by {@code catalog-service-import},
 * which is not a dependency of the monolith, so the bootstrap that mints them
 * never runs there. Both halves therefore travel in the image.
 *
 * <p><b>CE-only.</b> Gated on {@code catalog.generation.seed.enabled=true}, set
 * in the CE profile only. The cloud gets both halves from the importer, which is
 * the authoritative path, and a cloud install must never receive a price row
 * stamped as anything other than its own decision.
 *
 * <p><b>It defers to the bundle instead of competing with it.</b> When a signed
 * API-catalog bundle has ever been applied here ({@code api_catalog_bundles} has
 * an active row) the seed does nothing at all, for either half. That single
 * check is what makes seed-then-bundle converge rather than oscillate: the
 * bundle applier re-offers its prices on every sync tick, so a seed that kept
 * speaking would push the shipped price back over the cloud's on every restart
 * and each flip would mint an immutable pricing version. After the first bundle,
 * the cloud owns the generation catalog and the seed is over.
 *
 * <p><b>Descriptors: versioned, and only on rows the seed owns.</b> The
 * descriptors re-apply only when the shipped {@code version} is greater than the
 * marker in {@code catalog.generation_seed_state}, so an unchanged release is a
 * pure no-op and a bumped one refreshes exactly once. They are written only to
 * endpoints whose owning API is still {@code source='import'}, the dump-seeded
 * rows the seed is responsible for. An API the bundle has touched is
 * {@code source='bundle'} and an API created here is {@code source='custom'};
 * neither is the seed's to rewrite. That is the same provenance test
 * {@code ApiCatalogMergeService} already applies, checked up front AND repeated
 * in the UPDATE, rather than a second rule to keep in step.
 *
 * <p><b>Prices: {@code source='bundle'}, through the bundle's own path.</b> The
 * rows go to {@link ApiCatalogGenerationPriceApplier}, so they land through
 * {@code PlatformCredentialPricingService.applyBundlePrices} and are stamped
 * {@code bundle}, exactly like a cloud-carried price. That value is the accurate
 * one: a shipped starting price is precisely a price this install did not
 * decide. It buys the two properties that matter. A price an administrator here
 * publishes becomes {@code admin} and is preserved against the seed and against
 * every future bundle. And the seeded row stays replaceable, so the first bundle
 * that arrives supersedes it. Stamping it {@code admin} would do the opposite:
 * it would freeze the shipped number on the install forever, with nobody having
 * chosen it.
 *
 * <p>Prices are re-offered on every boot (while unbundled), which is deliberate
 * and free: {@code applyBundlePrices} publishes nothing when nothing changed.
 * The case it exists for is the operator who pastes a provider key a week after
 * the seed first ran, when there was no credential for a price to hang off.
 *
 * <p>Failures are swallowed (logged). A malformed seed must never block startup;
 * the install simply keeps whatever catalog it has.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "catalog.generation.seed.enabled", havingValue = "true")
public class GenerationSeedBootstrap {

    private static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() {};

    /** Stamped on the published pricing version so the history says what wrote it. */
    static final String PRICE_ORIGIN = "generation-seed";

    private final NamedParameterJdbcTemplate jdbc;
    private final ApiCatalogBundleRepository bundleRepository;
    private final ApiCatalogGenerationPriceApplier priceApplier;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;
    private final Resource seedResource;

    public GenerationSeedBootstrap(
            NamedParameterJdbcTemplate jdbc,
            ApiCatalogBundleRepository bundleRepository,
            ApiCatalogGenerationPriceApplier priceApplier,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager,
            @Value("${catalog.generation.seed.resource:classpath:catalog-seeds/generation-seed.json}")
            Resource seedResource) {
        this.jdbc = jdbc;
        this.bundleRepository = bundleRepository;
        this.priceApplier = priceApplier;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(transactionManager);
        this.seedResource = seedResource;
    }

    /**
     * What one boot did, so the caller's log line and the tests can state it.
     *
     * @param version                the shipped seed version, 0 when none was read
     * @param supersededByBundle     a signed bundle owns this catalog, so the seed stood down
     * @param descriptorsAlreadyApplied the marker already held this version or higher
     * @param descriptorsApplied     endpoints whose {@code generation_spec} was written
     * @param descriptorsSkipped     endpoints the seed named but does not own (absent,
     *                               bundle-owned or locally created)
     * @param pricesOffered          price rows handed to auth-service
     */
    public record Result(long version, boolean supersededByBundle, boolean descriptorsAlreadyApplied,
                         int descriptorsApplied, int descriptorsSkipped, int pricesOffered) {

        static Result nothingToDo() {
            return new Result(0, false, false, 0, 0, 0);
        }

        static Result superseded(long version) {
            return new Result(version, true, false, 0, 0, 0);
        }
    }

    /** One eligible endpoint: it exists, and the seed still owns its API. */
    private record Eligible(UUID apiToolId, String integrationName) {}

    /** Never throws. Call it once per boot, after the catalog rows exist. */
    public Result seedOnStartup() {
        try {
            return seedNow();
        } catch (Exception e) {
            log.error("Generation seed failed (non-fatal - keeping the existing catalog): {}",
                    e.getMessage(), e);
            return Result.nothingToDo();
        }
    }

    Result seedNow() throws Exception {
        if (seedResource == null || !seedResource.exists()) {
            log.warn("Generation seed resource missing ({}) - no generation models seeded", seedResource);
            return Result.nothingToDo();
        }

        SeedDoc doc = readSeed();
        if (doc.endpoints().isEmpty()) {
            log.info("Generation seed carries no endpoints - nothing to apply");
            return Result.nothingToDo();
        }
        if (doc.version() <= 0) {
            // Without a version the marker cannot say "already done", so every
            // boot would rewrite the descriptors. Refuse rather than churn.
            log.warn("Generation seed has no positive version - refusing to apply {} endpoint(s)",
                    doc.endpoints().size());
            return Result.nothingToDo();
        }

        // findActiveMetadata, not findFirstByActiveTrue: this only asks WHETHER a
        // bundle owns the catalog, and the entity finder would drag the ~24 MB
        // payload column into heap to answer it, once on every boot.
        if (!bundleRepository.findActiveMetadata().isEmpty()) {
            log.info("Generation seed v{} stood down: a signed API-catalog bundle owns this catalog",
                    doc.version());
            return Result.superseded(doc.version());
        }

        List<SeedEndpoint> parsed = parseDescriptors(doc.endpoints());
        if (parsed.isEmpty()) {
            log.warn("Generation seed v{}: no usable descriptor among {} endpoint(s)",
                    doc.version(), doc.endpoints().size());
            return Result.nothingToDo();
        }

        Map<UUID, Eligible> eligible = findEligible(parsed);

        Long applied = loadAppliedVersion();
        boolean descriptorsDue = applied == null || applied < doc.version();
        int written = 0;
        if (descriptorsDue) {
            written = applyDescriptors(parsed, eligible, doc.version());
        } else {
            log.info("Generation seed v{} descriptors already applied (marker=v{}) - prices re-offered only",
                    doc.version(), applied);
        }

        List<Map<String, Object>> priceRows = buildPriceRows(parsed, eligible);
        priceApplier.apply(priceRows, doc.version(), PRICE_ORIGIN);

        int skipped = parsed.size() - eligible.size();
        if (descriptorsDue) {
            log.info("Generation seed v{} applied (was v{}): descriptors written={} skipped={} of {} "
                            + "endpoint(s), {} starting price(s) offered",
                    doc.version(), applied, written, skipped, parsed.size(), priceRows.size());
        }
        return new Result(doc.version(), false, !descriptorsDue, written, skipped, priceRows.size());
    }

    /**
     * Validate each shipped descriptor with the same parser the execution path
     * uses, so a malformed one is refused here instead of surfacing later as a
     * mysterious unknown model on a call the customer already paid for. A bad
     * entry costs itself only: the other providers still land.
     */
    private List<SeedEndpoint> parseDescriptors(List<Map<String, Object>> raw) {
        List<SeedEndpoint> out = new ArrayList<>(raw.size());
        for (Map<String, Object> entry : raw) {
            String rawId = str(entry.get("apiToolId"));
            String name = str(entry.get("endpointName"));
            Object specNode = entry.get("generationSpec");
            if (rawId == null || specNode == null) {
                log.warn("Generation seed: entry '{}' has no apiToolId or no generationSpec - skipped", name);
                continue;
            }
            UUID toolId;
            try {
                toolId = UUID.fromString(rawId);
            } catch (IllegalArgumentException e) {
                log.warn("Generation seed: entry '{}' has an unparseable apiToolId '{}' - skipped", name, rawId);
                continue;
            }
            try {
                JsonNode json = objectMapper.valueToTree(specNode);
                GenerationSpec spec = GenerationSpec.parse(json, name == null ? rawId : name).orElse(null);
                if (spec == null) {
                    log.warn("Generation seed: entry '{}' carries an empty descriptor - skipped", name);
                    continue;
                }
                out.add(new SeedEndpoint(toolId, name, objectMapper.writeValueAsString(json), spec));
            } catch (Exception e) {
                log.warn("Generation seed: descriptor for '{}' ({}) is invalid - skipped: {}",
                        name, toolId, e.getMessage());
            }
        }
        return out;
    }

    /**
     * The endpoints the seed may still speak for: present in the catalog, and
     * owned by an API the SQL dump seeded ({@code source='import'}). A row the
     * bundle has written is {@code 'bundle'} and a row created here is
     * {@code 'custom'}; the seed rewrites neither. The platform-credential name
     * comes back with them because it is the key a price hangs off, on both
     * sides.
     */
    private Map<UUID, Eligible> findEligible(List<SeedEndpoint> parsed) {
        List<UUID> ids = parsed.stream().map(SeedEndpoint::apiToolId).toList();
        List<Eligible> rows = jdbc.query("""
                SELECT t.id AS id, a.platform_credential_name AS integration_name
                FROM catalog.api_tools t
                JOIN catalog.apis a ON a.id = t.api_id
                WHERE t.id IN (:ids) AND a.source = 'import'
                """,
                new MapSqlParameterSource("ids", ids),
                (rs, rowNum) -> new Eligible(
                        (UUID) rs.getObject("id"),
                        trimToNull(rs.getString("integration_name"))));
        Map<UUID, Eligible> byId = new LinkedHashMap<>();
        for (Eligible row : rows) {
            byId.put(row.apiToolId(), row);
        }
        return byId;
    }

    /**
     * Write the descriptors and advance the marker in ONE transaction: a marker
     * that moved without the writes landing would leave the install unseeded
     * until the next release, silently.
     */
    private int applyDescriptors(List<SeedEndpoint> parsed, Map<UUID, Eligible> eligible, long version) {
        Integer written = tx.execute(status -> {
            int count = 0;
            for (SeedEndpoint endpoint : parsed) {
                if (!eligible.containsKey(endpoint.apiToolId())) {
                    log.debug("Generation seed: endpoint {} ({}) is absent or not seed-owned - descriptor skipped",
                            endpoint.apiToolId(), endpoint.endpointName());
                    continue;
                }
                // The source predicate is repeated here on purpose: the SELECT
                // above and this statement are not one atomic read, and a row
                // that became bundle- or user-owned in between must not be
                // rewritten. Defence in depth, exactly as the bundle merge does.
                count += jdbc.update("""
                        UPDATE catalog.api_tools t
                        SET generation_spec = :spec::jsonb,
                            updated_at = EXTRACT(EPOCH FROM NOW()) * 1000
                        FROM catalog.apis a
                        WHERE t.id = :id AND a.id = t.api_id AND a.source = 'import'
                        """,
                        new MapSqlParameterSource()
                                .addValue("id", endpoint.apiToolId())
                                .addValue("spec", endpoint.specJson()));
            }
            saveAppliedVersion(version);
            return count;
        });
        return written == null ? 0 : written;
    }

    /**
     * One price row per declared model, read back out of the descriptor the same
     * boot just validated, so a model and its price cannot disagree. An endpoint
     * whose API has no platform credential name contributes nothing: there is no
     * credential for a price to attach to.
     */
    private List<Map<String, Object>> buildPriceRows(List<SeedEndpoint> parsed,
                                                      Map<UUID, Eligible> eligible) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SeedEndpoint endpoint : parsed) {
            Eligible hit = eligible.get(endpoint.apiToolId());
            if (hit == null || hit.integrationName() == null) {
                continue;
            }
            for (GenerationSpec.Model model : endpoint.spec().models()) {
                GenerationSpec.Price price = model.price() == null
                        ? GenerationSpec.Price.free()
                        : model.price();
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("integrationName", hit.integrationName());
                row.put("apiToolId", endpoint.apiToolId().toString());
                row.put("modelId", model.id());
                row.put("priceUnit", price.unit());
                row.put("baseCredits", plain(price.base()));
                row.put("unitCredits", plain(price.perUnit()));
                if (price.min() != null) row.put("minCredits", plain(price.min()));
                if (price.max() != null) row.put("maxCredits", plain(price.max()));
                rows.add(row);
            }
        }
        return rows;
    }

    private Long loadAppliedVersion() {
        List<Long> found = jdbc.query(
                "SELECT applied_version FROM catalog.generation_seed_state WHERE id = 1",
                new MapSqlParameterSource(),
                (rs, rowNum) -> {
                    long v = rs.getLong("applied_version");
                    return rs.wasNull() ? null : v;
                });
        return found.isEmpty() ? null : found.get(0);
    }

    private void saveAppliedVersion(long version) {
        jdbc.update("""
                INSERT INTO catalog.generation_seed_state (id, applied_version, applied_at)
                VALUES (1, :version, :appliedAt)
                ON CONFLICT (id) DO UPDATE SET
                    applied_version = EXCLUDED.applied_version,
                    applied_at = EXCLUDED.applied_at
                """,
                new MapSqlParameterSource()
                        .addValue("version", version)
                        .addValue("appliedAt", java.sql.Timestamp.from(Instant.now())));
    }

    /** Parsed seed document: the shipped version and the raw endpoint entries. */
    private record SeedDoc(long version, List<Map<String, Object>> endpoints) {}

    /** One validated entry, ready to write and to price. */
    private record SeedEndpoint(UUID apiToolId, String endpointName, String specJson, GenerationSpec spec) {}

    @SuppressWarnings("unchecked")
    private SeedDoc readSeed() throws Exception {
        try (InputStream in = seedResource.getInputStream()) {
            Map<String, Object> doc = objectMapper.readValue(in, JSON_MAP);
            long version = doc.get("version") instanceof Number n ? n.longValue() : 0L;
            Object endpoints = doc.get("endpoints");
            if (!(endpoints instanceof List<?> list)) {
                log.warn("Generation seed has no 'endpoints' array - skipping");
                return new SeedDoc(version, List.of());
            }
            List<Map<String, Object>> out = new ArrayList<>(list.size());
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    out.add((Map<String, Object>) m);
                }
            }
            return new SeedDoc(version, out);
        }
    }

    /**
     * Plain decimal text. The applier re-parses these, and a rate that
     * round-trips through scientific notation is not the rate that was shipped.
     */
    private static String plain(BigDecimal value) {
        return value == null ? "0" : value.toPlainString();
    }

    private static String str(Object value) {
        return value == null ? null : trimToNull(value.toString());
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
