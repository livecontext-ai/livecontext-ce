package com.apimarketplace.auth.repository;

import com.apimarketplace.auth.domain.OrganizationSsoDomain;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrganizationSsoDomainRepository extends JpaRepository<OrganizationSsoDomain, UUID> {

    List<OrganizationSsoDomain> findByOrganization_IdOrderByCreatedAtAsc(UUID organizationId);

    Optional<OrganizationSsoDomain> findByIdAndOrganization_Id(UUID id, UUID organizationId);

    boolean existsByOrganization_IdAndDomain(UUID organizationId, String domain);

    long countByOrganization_Id(UUID organizationId);

    /** The one workspace (partial unique index) that has PROVEN ownership of {@code domain}. */
    @Query("SELECT d FROM OrganizationSsoDomain d WHERE d.domain = :domain AND d.verifiedAt IS NOT NULL")
    Optional<OrganizationSsoDomain> findVerifiedByDomain(@Param("domain") String domain);

    @Query("SELECT COUNT(d) > 0 FROM OrganizationSsoDomain d "
            + "WHERE d.organization.id = :orgId AND d.domain = :domain AND d.verifiedAt IS NOT NULL")
    boolean isVerifiedForOrganization(@Param("orgId") UUID orgId, @Param("domain") String domain);
}
