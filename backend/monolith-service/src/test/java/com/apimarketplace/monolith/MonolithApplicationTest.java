package com.apimarketplace.monolith;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("MonolithApplication")
class MonolithApplicationTest {

    @Test
    @DisplayName("repairs known failed pgvector migration before migrating in CE monolith")
    void repairsKnownFailedPgvectorMigrationBeforeMigratingInCeMonolith() {
        MonolithApplication application = new MonolithApplication();
        FlywayMigrationStrategy strategy = application.repairKnownPgvectorFailureThenMigrate();
        Flyway flyway = mock(Flyway.class);
        MigrationInfoService infoService = mock(MigrationInfoService.class);
        MigrationInfo failedV75 = migration("75", MigrationState.FAILED);
        when(flyway.info()).thenReturn(infoService);
        when(infoService.all()).thenReturn(new MigrationInfo[] { failedV75 });

        strategy.migrate(flyway);

        var inOrder = inOrder(flyway);
        inOrder.verify(flyway).repair();
        inOrder.verify(flyway).migrate();
    }

    @Test
    @DisplayName("does not repair Flyway history when the failure is unrelated to pgvector")
    void doesNotRepairUnrelatedFlywayFailures() {
        MonolithApplication application = new MonolithApplication();
        FlywayMigrationStrategy strategy = application.repairKnownPgvectorFailureThenMigrate();
        Flyway flyway = mock(Flyway.class);
        MigrationInfoService infoService = mock(MigrationInfoService.class);
        MigrationInfo failedUnrelated = migration("240", MigrationState.FAILED);
        when(flyway.info()).thenReturn(infoService);
        when(infoService.all()).thenReturn(new MigrationInfo[] { failedUnrelated });

        strategy.migrate(flyway);

        verify(flyway, never()).repair();
        verify(flyway).migrate();
    }

    @Test
    @DisplayName("does not repair Flyway history when pgvector migrations are already successful")
    void doesNotRepairSuccessfulPgvectorHistory() {
        MonolithApplication application = new MonolithApplication();
        FlywayMigrationStrategy strategy = application.repairKnownPgvectorFailureThenMigrate();
        Flyway flyway = mock(Flyway.class);
        MigrationInfoService infoService = mock(MigrationInfoService.class);
        MigrationInfo successfulV74 = migration("74", MigrationState.SUCCESS);
        MigrationInfo successfulV75 = migration("75", MigrationState.SUCCESS);
        when(flyway.info()).thenReturn(infoService);
        when(infoService.all()).thenReturn(new MigrationInfo[] { successfulV74, successfulV75 });

        strategy.migrate(flyway);

        verify(flyway, never()).repair();
        verify(flyway).migrate();
    }

    private static MigrationInfo migration(String version, MigrationState state) {
        MigrationInfo migration = mock(MigrationInfo.class);
        when(migration.getVersion()).thenReturn(MigrationVersion.fromVersion(version));
        when(migration.getState()).thenReturn(state);
        return migration;
    }
}
