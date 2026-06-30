package com.apimarketplace.conversation.repository;

import com.apimarketplace.conversation.entity.UserChatDefaults;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserChatDefaultsRepository
        extends JpaRepository<UserChatDefaults, UserChatDefaults.Key> {

    Optional<UserChatDefaults> findByUserIdAndOrganizationId(String userId, String organizationId);
}
