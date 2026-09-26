package com.apimarketplace.auth.credential.repository;

import com.apimarketplace.auth.credential.domain.PricingVersionEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PricingVersionEntryRepository extends JpaRepository<PricingVersionEntry, Long> {

    List<PricingVersionEntry> findByPricingVersionId(Long pricingVersionId);

    /**
     * The TOOL-LEVEL price row, i.e. the one that applies whatever model is
     * called. This is the only shape that existed before V428.
     *
     * <p>Since V428 an endpoint can ALSO carry one row per generation model, so
     * {@code (pricingVersionId, apiToolId)} is no longer unique and can no
     * longer be read into an {@link Optional} without risking a
     * non-unique-result failure on the billing path. Every caller that means
     * "the price of this endpoint" means this method.
     */
    Optional<PricingVersionEntry> findByPricingVersionIdAndApiToolIdAndModelIdIsNull(
            Long pricingVersionId, UUID apiToolId);

    /** The price row for one specific generation model on this endpoint. */
    Optional<PricingVersionEntry> findByPricingVersionIdAndApiToolIdAndModelId(
            Long pricingVersionId, UUID apiToolId, String modelId);

    /**
     * Every row attached to an endpoint, model-specific and tool-level alike.
     * Used by the admin screens to render a whole endpoint's price list.
     */
    List<PricingVersionEntry> findByPricingVersionIdAndApiToolId(
            Long pricingVersionId, UUID apiToolId);

    /**
     * Every (endpoint, model) pair this credential has EVER published a price
     * for, in any version, as {@code [apiToolId, modelId]} rows.
     *
     * <p>"Ever", not "latest", on purpose: it is how a caller tells a model
     * nobody has priced yet from one an administrator priced and then REMOVED.
     * The second is a decision and must stay removed.
     */
    @Query("SELECT DISTINCT e.apiToolId, e.modelId FROM PricingVersionEntry e "
            + "WHERE e.pricingVersionId IN (SELECT v.id FROM PlatformCredentialPricingVersion v "
            + "WHERE v.platformCredentialId = :credentialId)")
    List<Object[]> findEverPublishedKeys(@Param("credentialId") Long credentialId);
}
