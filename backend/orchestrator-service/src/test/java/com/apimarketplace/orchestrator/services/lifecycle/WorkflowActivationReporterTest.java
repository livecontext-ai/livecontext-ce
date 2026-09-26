package com.apimarketplace.orchestrator.services.lifecycle;

import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.common.web.AppEditionProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("WorkflowActivationReporter - best-effort, after-commit activation signal")
class WorkflowActivationReporterTest {

    private static final Executor SAME_THREAD = Runnable::run;

    private final AuthClient authClient = mock(AuthClient.class);

    private static final AppEditionProvider CLOUD = edition(false);

    private static AppEditionProvider edition(boolean selfHosted) {
        AppEditionProvider p = mock(AppEditionProvider.class);
        when(p.isSelfHosted()).thenReturn(selfHosted);
        return p;
    }

    @Test
    @DisplayName("a self-hosted edition (CE) makes no activation call at all")
    void selfHostedIsNoOp() {
        WorkflowActivationReporter reporter = new WorkflowActivationReporter(authClient, SAME_THREAD, edition(true));

        reporter.workflowCreated("42");

        verifyNoInteractions(authClient);
    }

    @Test
    @DisplayName("the cloud edition reports")
    void cloudReports() {
        when(authClient.reportActivation("42")).thenReturn(true);
        new WorkflowActivationReporter(authClient, SAME_THREAD, CLOUD).workflowCreated("42");

        verify(authClient).reportActivation("42");
    }

    @AfterEach
    void clearSync() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("reports the user once; after an acknowledgement later creations make no call")
    void reportsOnceThenRemembers() {
        when(authClient.reportActivation("42")).thenReturn(true);
        WorkflowActivationReporter reporter = new WorkflowActivationReporter(authClient, SAME_THREAD, CLOUD);

        reporter.workflowCreated("42");
        reporter.workflowCreated("42");

        verify(authClient, times(1)).reportActivation("42");
    }

    @Test
    @DisplayName("an unacknowledged report (auth down) is retried on the next creation")
    void failedReportIsRetried() {
        when(authClient.reportActivation("42")).thenReturn(false, true);
        WorkflowActivationReporter reporter = new WorkflowActivationReporter(authClient, SAME_THREAD, CLOUD);

        reporter.workflowCreated("42");
        reporter.workflowCreated("42");
        reporter.workflowCreated("42");

        verify(authClient, times(2)).reportActivation("42");
    }

    @Test
    @DisplayName("inside a transaction nothing is sent before commit, and a rollback sends nothing")
    void waitsForCommit() {
        when(authClient.reportActivation("42")).thenReturn(true);
        WorkflowActivationReporter reporter = new WorkflowActivationReporter(authClient, SAME_THREAD, CLOUD);
        TransactionSynchronizationManager.initSynchronization();

        reporter.workflowCreated("42");

        verify(authClient, never()).reportActivation(anyString());
        for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
            s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }
        verify(authClient, never()).reportActivation(anyString());
        for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
            s.afterCommit();
        }
        verify(authClient).reportActivation("42");
    }

    @Test
    @DisplayName("a blank user is ignored and a full queue never throws")
    void blankAndFullQueue() {
        WorkflowActivationReporter blank = new WorkflowActivationReporter(authClient, SAME_THREAD, CLOUD);
        blank.workflowCreated(" ");
        blank.workflowCreated(null);
        verifyNoInteractions(authClient);

        Executor full = r -> { throw new RejectedExecutionException("full"); };
        new WorkflowActivationReporter(authClient, full, CLOUD).workflowCreated("42");
        verifyNoInteractions(authClient);
    }
}
