package com.apimarketplace.auth.repository;

import com.apimarketplace.auth.domain.OrganizationSamlConnection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrganizationSamlConnectionRepository extends JpaRepository<OrganizationSamlConnection, UUID> {

    Optional<OrganizationSamlConnection> findByOrganization_Id(UUID organizationId);

    Optional<OrganizationSamlConnection> findByIdpAlias(String idpAlias);

    boolean existsByIdpAlias(String idpAlias);
}
