package com.apimarketplace.auth.repository;

import com.apimarketplace.auth.domain.InvitationStatus;
import com.apimarketplace.auth.domain.OrganizationInvitation;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrganizationInvitationRepository extends JpaRepository<OrganizationInvitation, UUID> {

    @Override
    @EntityGraph(attributePaths = {"organization", "invitedBy"})
    Optional<OrganizationInvitation> findById(UUID id);

    @EntityGraph(attributePaths = {"organization", "invitedBy"})
    Optional<OrganizationInvitation> findByToken(String token);

    @EntityGraph(attributePaths = {"organization", "invitedBy"})
    List<OrganizationInvitation> findByOrganization_IdAndStatus(UUID organizationId, InvitationStatus status);

    @EntityGraph(attributePaths = {"organization", "invitedBy"})
    List<OrganizationInvitation> findByEmailAndStatus(String email, InvitationStatus status);

    long countByOrganization_IdAndStatus(UUID organizationId, InvitationStatus status);

    boolean existsByOrganization_IdAndEmailAndStatus(UUID organizationId, String email, InvitationStatus status);

    // Rate-limit counters (S-2). createdAt comes from the entity constructor -
    // we count rows actually persisted, not attempts. The earlier guards in
    // inviteMember (already-member, pending-exists) short-circuit before save,
    // so they don't pad these counts.
    long countByInvitedBy_IdAndCreatedAtAfter(Long inviterId, LocalDateTime since);

    long countByOrganization_IdAndCreatedAtAfter(UUID organizationId, LocalDateTime since);
}
