package com.apimarketplace.orchestrator.services.persistence;

import com.apimarketplace.common.storage.domain.StorageEntity;
import com.apimarketplace.common.storage.repository.StorageRepository;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.common.storage.service.StorageService;
import com.apimarketplace.common.storage.service.api.MappingOperations;
import com.apimarketplace.common.storage.service.api.QuotaOperations;
import com.apimarketplace.common.storage.util.JsonSkeletonGenerator;
import com.apimarketplace.common.storage.util.StorageUtils;
import com.apimarketplace.orchestrator.domain.workflow.Step;
import com.apimarketplace.orchestrator.domain.workflow.StepExecutionResult;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.persistence.StepDataNativeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * THE POISON-SAFETY PIN for layer (d) of the output-loss fix.
 *
 * <p>The bounded retry in {@link StepPayloadService} is only safe because each
 * storage attempt runs in a FRESH transaction: {@code StorageService} is
 * class-level {@code @Transactional} (REQUIRED) and nothing up-stack of the
 * retry loop ({@code StepDataPersistenceService.recordStep},
 * {@code WorkflowPersistenceService.recordStep},
 * {@code StepCompletionOrchestrator.complete}) is transactional - so a failed
 * first attempt rolls back ITS OWN transaction and cannot poison the retry
 * (the prod incident: a 22P05 poisoned the enclosing tx, and every subsequent
 * statement failed with "current transaction is aborted").
 *
 * <p>This test runs the REAL Spring transaction plumbing (a real
 * {@link DataSourceTransactionManager} over embedded H2, real
 * {@code @Transactional} proxying of {@code StorageService}) and injects a
 * first-attempt failure. It asserts the retry succeeds AND that the two
 * attempts ran in DIFFERENT transactions. If someone later adds
 * {@code @Transactional} up-stack (recordStep or the completion orchestrator),
 * both attempts join ONE outer transaction: the connection holder becomes
 * identical across attempts and this test FAILS - by design.
 */
@DisplayName("StepPayloadService retry - fresh transaction per attempt (poison-safety pin)")
class StepPayloadRetryFreshTransactionIntegrationTest {

    private AnnotationConfigApplicationContext ctx;

    /** Per-attempt observations captured INSIDE the repository save call. */
    static final List<Boolean> txActivePerAttempt = new ArrayList<>();
    static final List<Object> connectionHolderPerAttempt = new ArrayList<>();
    static final List<Boolean> txReadOnlyPerAttempt = new ArrayList<>();

    // proxyTargetClass: StorageService implements StorageOperations, so the
    // default JDK proxy could not be injected as the concrete class the
    // StepPayloadService constructor requires. CGLIB subclass proxying keeps
    // the REAL @Transactional interception semantics.
    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            return new EmbeddedDatabaseBuilder()
                    .setType(EmbeddedDatabaseType.H2)
                    .generateUniqueName(true)
                    .build();
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        StorageRepository storageRepository(DataSource dataSource) {
            StorageRepository repository = mock(StorageRepository.class);
            when(repository.save(any(StorageEntity.class))).thenAnswer(inv -> {
                // Observe the transaction each attempt runs under - the REAL
                // @Transactional proxy on StorageService opened it.
                txActivePerAttempt.add(TransactionSynchronizationManager.isActualTransactionActive());
                txReadOnlyPerAttempt.add(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
                connectionHolderPerAttempt.add(TransactionSynchronizationManager.getResource(dataSource));
                if (txActivePerAttempt.size() == 1) {
                    throw new RuntimeException("injected first-attempt storage failure");
                }
                StorageEntity e = inv.getArgument(0);
                e.setId(UUID.randomUUID());
                return e;
            });
            return repository;
        }

        @Bean
        StorageService storageService(StorageRepository storageRepository) {
            StorageUtils storageUtils = mock(StorageUtils.class);
            when(storageUtils.calculateSize(any())).thenReturn(10);
            when(storageUtils.calculateChecksum(any())).thenReturn("checksum");
            return new StorageService(storageRepository,
                    mock(QuotaOperations.class), mock(MappingOperations.class),
                    storageUtils, mock(JsonSkeletonGenerator.class),
                    new ObjectMapper(), mock(StorageBreakdownService.class));
        }

        @Bean
        StepPayloadService stepPayloadService(StorageService storageService) {
            StepPayloadService service = new StepPayloadService(storageService, mock(OutputSchemaMapper.class));
            service.setRetryBackoffMs(0);
            return service;
        }

        @Bean
        StepDataPersistenceService stepDataPersistenceService(StepPayloadService stepPayloadService) {
            StepDataNativeRepository nativeRepository = mock(StepDataNativeRepository.class);
            when(nativeRepository.insertIgnoringDuplicate(any())).thenReturn(true);
            StepMetadataBuilder metadataBuilder = mock(StepMetadataBuilder.class);
            when(metadataBuilder.buildMetadata(any(), any(), any(), any(), any())).thenReturn(new HashMap<>());
            WorkflowEntityResolverService entityResolverService = mock(WorkflowEntityResolverService.class);
            UUID wfRunId = UUID.randomUUID();
            when(entityResolverService.resolveWorkflowRunId(any())).thenReturn(Optional.of(wfRunId));
            when(entityResolverService.getCurrentEpochFromRun(wfRunId)).thenReturn(1);
            when(entityResolverService.getCurrentSpawnFromRun(wfRunId)).thenReturn(0);
            when(entityResolverService.getOrganizationIdFromRun(any())).thenReturn(Optional.empty());
            return new StepDataPersistenceService(nativeRepository, stepPayloadService,
                    metadataBuilder, entityResolverService);
        }
    }

    @BeforeEach
    void setUp() {
        txActivePerAttempt.clear();
        connectionHolderPerAttempt.clear();
        txReadOnlyPerAttempt.clear();
        ctx = new AnnotationConfigApplicationContext(TestConfig.class);
    }

    @AfterEach
    void tearDown() {
        if (ctx != null) {
            ctx.close();
        }
    }

    private WorkflowExecution mockExecution() {
        WorkflowExecution execution = mock(WorkflowExecution.class);
        WorkflowPlan plan = mock(WorkflowPlan.class);
        lenient().when(execution.getPlan()).thenReturn(plan);
        lenient().when(execution.getRunId()).thenReturn("run-fresh-tx");
        lenient().when(plan.getTenantId()).thenReturn("tenant-1");
        lenient().when(plan.getId()).thenReturn("wf-1");
        lenient().when(plan.findStep(any())).thenReturn(Optional.<Step>empty());
        return execution;
    }

    @Test
    @DisplayName("first attempt fails, retry commits in a FRESH usable transaction - recordStep returns a stored payload")
    void retryRunsInFreshTransactionAndSucceeds() {
        StepDataPersistenceService recordStepEntry = ctx.getBean(StepDataPersistenceService.class);

        StepExecutionResult result = StepExecutionResult.success(
                "mcp:step", Map.of("data", "value", "item_index", 0), 100L);

        StepPersistenceResult persistence = recordStepEntry.recordStep(
                mockExecution(), "mcp:step", "alias", "graph", result, 1, "trigger:start");

        // The retry succeeded: the row landed as a normal PERSISTED row with a storage id.
        assertThat(persistence.persisted()).isTrue();
        assertThat(persistence.payloadLost()).isFalse();
        assertThat(persistence.storageId()).isNotNull();

        // Exactly two attempts reached the repository.
        assertThat(txActivePerAttempt).hasSize(2);

        // Each attempt ran under a REAL active read-write transaction opened by
        // StorageService's class-level @Transactional...
        assertThat(txActivePerAttempt).containsExactly(true, true);
        assertThat(txReadOnlyPerAttempt).containsExactly(false, false);

        // ...and the two attempts used DIFFERENT transactions (different
        // connection holders). If @Transactional is ever added up-stack
        // (StepDataPersistenceService.recordStep / WorkflowPersistenceService
        // .recordStep / StepCompletionOrchestrator.complete), both attempts
        // join ONE outer transaction, the holders become identical, and this
        // assertion fails - the poison-safety contract is broken.
        assertThat(connectionHolderPerAttempt.get(0))
                .as("attempt 1 must own a transaction (connection holder bound)")
                .isNotNull();
        assertThat(connectionHolderPerAttempt.get(1))
                .as("attempt 2 must own a transaction (connection holder bound)")
                .isNotNull();
        assertThat(connectionHolderPerAttempt.get(0))
                .as("each attempt must run in a FRESH transaction - a shared holder means "
                        + "an enclosing @Transactional was added up-stack and the first "
                        + "failure would poison the retry")
                .isNotSameAs(connectionHolderPerAttempt.get(1));
    }

    @Test
    @DisplayName("static pin: no @Transactional on the recordStep/complete chain above the retry loop")
    void noTransactionalUpStack() throws Exception {
        assertNoTransactional(StepDataPersistenceService.class, "recordStep");
        assertNoTransactional(com.apimarketplace.orchestrator.services.WorkflowPersistenceService.class, "recordStep");
        assertNoTransactional(com.apimarketplace.orchestrator.services.completion.StepCompletionOrchestrator.class, "complete");
        assertNoTransactional(StepPayloadService.class, "persistStepPayloadOutcome");
    }

    private static void assertNoTransactional(Class<?> type, String methodName) {
        assertThat(type.isAnnotationPresent(Transactional.class))
                .as("%s must NOT be class-level @Transactional - it would wrap the payload "
                        + "retry in one poisonable transaction", type.getSimpleName())
                .isFalse();
        for (Method m : type.getDeclaredMethods()) {
            if (m.getName().equals(methodName)) {
                assertThat(m.isAnnotationPresent(Transactional.class))
                        .as("%s.%s must NOT be @Transactional (poison-safety contract)",
                                type.getSimpleName(), methodName)
                        .isFalse();
            }
        }
    }
}
