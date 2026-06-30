package com.apimarketplace.auth.bootstrap;

import com.apimarketplace.auth.ce.CeInstallStateService;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.common.web.AppEditionProvider;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Centralized first-user-admin bootstrap policy.
 *
 * <p>The CE community-edition contract says "the first user to log in becomes
 * the platform admin". This MUST NOT happen in cloud deployments - even when
 * the {@code users} table is empty (DB snapshot restore, fresh tenant
 * onboarding, staging URL leaked to prod). The fix gates the promotion on
 * three independent signals:
 *
 * <ol>
 *   <li><b>Edition</b>: only fires when {@link AppEditionProvider#isCe()}.
 *       Cloud never enters the branch regardless of {@code users} count.</li>
 *   <li><b>Bootstrap state</b>: once the CE install wizard is marked complete
 *       (via {@code CeInstallController}), the auto-admin branch is a no-op -
 *       even if the {@code users} table is later emptied. This decouples the
 *       security boundary from a fragile row-count.</li>
 *   <li><b>Race protection</b>: parallel registrants are serialized via a
 *       Postgres advisory lock so the {@code count() == 0} read and the
 *       subsequent {@code save()} are atomic w.r.t. other writers.</li>
 * </ol>
 *
 * <p>Both {@code OAuthUserProcessor} and {@code PasswordAuthService} call this
 * helper - the gate must apply to both registration paths.
 */
@Component
public class FirstAdminBootstrap {

    private static final Logger log = LoggerFactory.getLogger(FirstAdminBootstrap.class);

    /**
     * Stable identifier for the Postgres advisory lock that serializes the
     * count→save window. {@code hashtext} hashes to a {@code bigint} key; a
     * single literal collision is irrelevant for a one-key namespace.
     */
    static final String LOCK_KEY = "auth.first-user-admin";

    private final EntityManager em;
    private final AppEditionProvider edition;
    private final CeInstallStateService installState;
    private final UserRepository userRepository;

    public FirstAdminBootstrap(EntityManager em,
                               AppEditionProvider edition,
                               CeInstallStateService installState,
                               UserRepository userRepository) {
        this.em = em;
        this.edition = edition;
        this.installState = installState;
        this.userRepository = userRepository;
    }

    /**
     * Returns {@code true} iff the caller MUST grant {@code ADMIN} to the user
     * it is about to create. The decision is atomic w.r.t. other concurrent
     * registrants in the same Postgres database.
     *
     * <p>Must be invoked from inside an existing transaction
     * ({@link Propagation#MANDATORY}) so the advisory lock is auto-released on
     * commit/rollback of the caller's transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean claimFirstAdminSlot() {
        // I1: Cloud never auto-promotes - primary defense against snapshot/wipe scenarios.
        if (!edition.isCe()) {
            return false;
        }
        // I3: Once the CE wizard marks the install bootstrapped, no further auto-promotion
        // ever - even if users are deleted afterwards. Security boundary is the install
        // state, not the row count.
        if (installState.getStatus().bootstrapped()) {
            return false;
        }
        // E1: Serialize parallel registrants on a constant key. Released on commit/rollback.
        em.createNativeQuery("SELECT pg_advisory_xact_lock(hashtext(:key))")
                .setParameter("key", LOCK_KEY)
                .getSingleResult();
        // After lock: re-check inside the critical section.
        boolean isFirst = userRepository.count() == 0;
        if (isFirst) {
            log.info("[bootstrap] first-user-admin slot claimed (CE, pre-bootstrap)");
        }
        return isFirst;
    }
}
