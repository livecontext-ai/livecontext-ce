package com.apimarketplace.auth.service;

import com.apimarketplace.auth.analytics.AuthAnalyticsEmitter;
import com.apimarketplace.notification.client.NotificationClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * credit_alert_sent is counted once per alert RECORDED as sent: a refused notification (retried
 * next scan) or an insert that lost the race is not a new alert. The distinct_id is the payer.
 */
@DisplayName("CreditAlertScheduler - credit_alert_sent analytics")
class CreditAlertSchedulerAnalyticsTest {

    private static final String INSERT_PREFIX = "INSERT INTO auth.credit_alert_sent";

    private JdbcTemplate jdbc;
    private NotificationClient notifications;
    private AuthAnalyticsEmitter analytics;
    private CreditAlertScheduler scheduler;

    private final CreditAlertScheduler.Candidate candidate = new CreditAlertScheduler.Candidate(
            501L, 77L, BigDecimal.ZERO, "20260901000000:0", "org-personal", 1000L, false, false);

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        notifications = mock(NotificationClient.class);
        analytics = mock(AuthAnalyticsEmitter.class);
        scheduler = new CreditAlertScheduler(jdbc, false, true, new BigDecimal("0.2"));
        ReflectionTestUtils.setField(scheduler, "notificationClient", notifications);
        ReflectionTestUtils.setField(scheduler, "analytics", analytics);
    }

    private void insertWrites(int rows) {
        when(jdbc.update(org.mockito.ArgumentMatchers.startsWith(INSERT_PREFIX), any(), any(), any())).thenReturn(rows);
    }

    @Test
    @DisplayName("a delivered and recorded alert is counted for the payer, level and workspace included")
    void recordedAlertIsCounted() {
        when(notifications.emit(any())).thenReturn(true);
        insertWrites(1);

        scheduler.alert(candidate, CreditAlertScheduler.LEVEL_EXHAUSTED);

        verify(analytics).creditAlertSent(77L, "org-personal", "EXHAUSTED");
    }

    @Test
    @DisplayName("a refused notification is not recorded, so it is not counted either")
    void refusedNotificationIsNotCounted() {
        when(notifications.emit(any())).thenReturn(false);

        scheduler.alert(candidate, CreditAlertScheduler.LEVEL_LOW);

        verify(analytics, never()).creditAlertSent(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("an insert that wrote nothing (already recorded this cycle) is not a second alert")
    void conflictingInsertIsNotCounted() {
        when(notifications.emit(any())).thenReturn(true);
        insertWrites(0);

        scheduler.alert(candidate, CreditAlertScheduler.LEVEL_LOW);

        verify(analytics, never()).creditAlertSent(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("without the analytics bean the alert still goes out and is recorded")
    void noAnalyticsBean() {
        ReflectionTestUtils.setField(scheduler, "analytics", null);
        when(notifications.emit(any())).thenReturn(true);
        insertWrites(1);

        scheduler.alert(candidate, CreditAlertScheduler.LEVEL_LOW);

        verify(jdbc).update(org.mockito.ArgumentMatchers.startsWith(INSERT_PREFIX), eq(501L),
                eq("20260901000000:0"), eq("LOW"));
    }
}
