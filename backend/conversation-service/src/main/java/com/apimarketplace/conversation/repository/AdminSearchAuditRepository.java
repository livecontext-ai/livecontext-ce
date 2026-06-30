package com.apimarketplace.conversation.repository;

import com.apimarketplace.conversation.entity.AdminSearchAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AdminSearchAuditRepository extends JpaRepository<AdminSearchAudit, Long> {
}
