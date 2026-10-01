package com.apimarketplace.auth.integration;

import com.apimarketplace.auth.domain.PartnerApplication;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.PartnerApplicationRepository;
import com.apimarketplace.auth.repository.RewardCodeRepository;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.auth.service.PartnerProgramAdminService;
import com.apimarketplace.auth.service.PartnerProgramService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * V553 on a real Postgres, through the real service and its real transaction: the guarantee the
 * optimistic lock exists for.
 *
 * <p>An admin approves; while that approval is between creating the partner code and recording
 * the decision, another admin rejects the same application and commits. The approval must fail,
 * and the partner code it had already inserted must be rolled back with it: otherwise the
 * applicant would hold a live code (and the badge) on an application that says REJECTED.
 *
 * <p>The concurrent reject runs in its own REQUIRES_NEW transaction on another connection, inserted
 * exactly at that point by a spy on the code creation, so the interleaving is deterministic.
 */
@SpringBootTest
@DisplayName("Partner application: a concurrent reject rolls the approval back, code included (real Postgres)")
class PartnerApplicationApproveRaceTest extends AuthScratchPostgresSpringTest {

    @Autowired private PartnerProgramService service;
    @Autowired private PartnerApplicationRepository applicationRepository;
    @Autowired private RewardCodeRepository codeRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoSpyBean private PartnerProgramAdminService adminService;

    @BeforeEach
    void reset() {
        // ddl-auto leaves created_at to the database (insertable = false); the migrations default it.
        jdbcTemplate.execute("ALTER TABLE auth.reward_code ALTER COLUMN created_at SET DEFAULT now()");
        jdbcTemplate.execute("ALTER TABLE auth.partner_application ALTER COLUMN created_at SET DEFAULT now()");
        applicationRepository.deleteAll();
        codeRepository.deleteAll();
    }

    private Long pendingApplicationFor(String email) {
        User u = new User();
        u.setEmail(email);
        u.setUsername(email);
        u.setEnabled(true);
        Long userId = userRepository.save(u).getId();
        PartnerApplication a = new PartnerApplication();
        a.setUserId(userId);
        a.setCompanyName("Acme");
        return applicationRepository.saveAndFlush(a).getId();
    }

    @Test
    @DisplayName("the approval throws, no partner code survives, and the rejection stands")
    void concurrentRejectRollsBackTheApproval() {
        Long id = pendingApplicationFor("race-approve@x.io");
        TransactionTemplate concurrent = new TransactionTemplate(transactionManager);
        concurrent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        doAnswer(inv -> {
            Object created = inv.callRealMethod();
            // The other admin, on another connection, decides first and commits.
            concurrent.executeWithoutResult(st -> {
                PartnerApplication other = applicationRepository.findById(id).orElseThrow();
                other.setStatus(PartnerApplication.Status.REJECTED);
                applicationRepository.saveAndFlush(other);
            });
            return created;
        }).when(adminService).createPartnerCode(any());

        assertThatThrownBy(() -> service.approve(id, 1L, null, null, false))
                .isInstanceOf(OptimisticLockingFailureException.class);

        Long userId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM auth.partner_application WHERE id = ?", Long.class, id);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM auth.reward_code WHERE owner_user_id = ?", Long.class, userId))
                .as("the partner code created by the losing approval must be rolled back")
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status || ':' || coalesce(reward_code_id::text, 'none') FROM auth.partner_application WHERE id = ?",
                String.class, id)).isEqualTo("REJECTED:none");
    }

    @Test
    @DisplayName("without a concurrent decision the same path approves and keeps the code (control case)")
    void uncontendedApprovalCommits() {
        Long id = pendingApplicationFor("race-control@x.io");

        var out = service.approve(id, 1L, null, null, false);

        assertThat(out.success()).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM auth.partner_application WHERE id = ?", String.class, id)).isEqualTo("APPROVED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM auth.reward_code WHERE id = ?", Long.class, out.application().getRewardCodeId()))
                .isEqualTo(1);
    }
}
