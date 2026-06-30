package com.apimarketplace.auth.ce;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CeInstallStateRepository extends JpaRepository<CeInstallState, Short> {

    /**
     * Pessimistic read of the singleton row. Used by
     * {@link CeInstallStateService#markBootstrapped} to serialize concurrent
     * "Finish wizard" writes - the second writer sees the already-bootstrapped
     * row and no-ops without stamping a fresh timestamp.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM CeInstallState s WHERE s.id = com.apimarketplace.auth.ce.CeInstallState.SINGLETON_ID")
    Optional<CeInstallState> findSingletonForUpdate();
}
