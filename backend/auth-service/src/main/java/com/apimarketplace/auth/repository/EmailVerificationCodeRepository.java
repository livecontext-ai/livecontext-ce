package com.apimarketplace.auth.repository;

import com.apimarketplace.auth.domain.EmailVerificationCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface EmailVerificationCodeRepository extends JpaRepository<EmailVerificationCode, Long> {

    Optional<EmailVerificationCode> findTopByUserIdAndVerifiedFalseOrderByCreatedAtDesc(Long userId);

    long countByEmailAndCreatedAtAfter(String email, LocalDateTime since);

    void deleteByUserIdAndVerifiedFalse(Long userId);
}
