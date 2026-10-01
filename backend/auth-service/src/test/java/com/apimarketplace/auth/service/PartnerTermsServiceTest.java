package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.PartnerTermsAcceptance;
import com.apimarketplace.auth.domain.PartnerTermsAcceptance.Source;
import com.apimarketplace.auth.repository.PartnerTermsAcceptanceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/** V557: the Partner Program Terms, their version, and the acceptances that bind a partner. */
class PartnerTermsServiceTest {

    private static final String VERSION = "2026-10-01";
    private static final long USER = 7L;

    private PartnerTermsAcceptanceRepository repository;
    private PartnerTermsService service;

    @BeforeEach
    void setUp() {
        repository = mock(PartnerTermsAcceptanceRepository.class);
        service = new PartnerTermsService(repository, VERSION);
    }

    @Test
    @DisplayName("nothing ticked is terms_not_accepted, another version is terms_outdated, the current one is accepted")
    void refusal() {
        assertThat(service.refusal(null)).isEqualTo("terms_not_accepted");
        assertThat(service.refusal("  ")).isEqualTo("terms_not_accepted");
        assertThat(service.refusal("2026-01-01")).isEqualTo("terms_outdated");
        assertThat(service.refusal(VERSION)).isNull();
        assertThat(service.refusal("  " + VERSION + " ")).isNull();
    }

    @Test
    @DisplayName("accepting the current version records it with the source and the evidence of the click")
    void acceptRecords() {
        when(repository.record(anyLong(), anyString(), anyString(), any(), anyString(), any(), any())).thenReturn(1);

        String refused = service.accept(USER, VERSION, Source.APPLICATION,
                new PartnerTermsService.Evidence(" 203.0.113.7 ", "Mozilla/5.0"));

        assertThat(refused).isNull();
        verify(repository).record(eq(USER), eq(VERSION), eq(PartnerTermsService.CURRENT_FINGERPRINT), any(Instant.class), eq("APPLICATION"),
                eq("203.0.113.7"), eq("Mozilla/5.0"));
    }

    @Test
    @DisplayName("an outdated or missing version is refused and nothing is recorded")
    void acceptRefusedRecordsNothing() {
        assertThat(service.accept(USER, "2026-01-01", Source.DASHBOARD, PartnerTermsService.Evidence.none()))
                .isEqualTo("terms_outdated");
        assertThat(service.accept(USER, null, Source.DASHBOARD, PartnerTermsService.Evidence.none()))
                .isEqualTo("terms_not_accepted");
        verify(repository, never()).record(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("accepting a version already accepted is a success: the first acceptance stands (the insert keeps it)")
    void acceptTwiceIsIdempotent() {
        when(repository.record(anyLong(), anyString(), anyString(), any(), anyString(), any(), any())).thenReturn(0);

        assertThat(service.accept(USER, VERSION, Source.DASHBOARD, null)).isNull();
        verify(repository).record(eq(USER), eq(VERSION), eq(PartnerTermsService.CURRENT_FINGERPRINT), any(Instant.class), eq("DASHBOARD"), isNull(), isNull());
    }

    @Test
    @DisplayName("an oversized address or browser string is cut to the column size, never refused")
    void evidenceIsCapped() {
        service.accept(USER, VERSION, Source.DASHBOARD,
                new PartnerTermsService.Evidence("1".repeat(100), "u".repeat(400)));

        verify(repository).record(eq(USER), eq(VERSION), eq(PartnerTermsService.CURRENT_FINGERPRINT), any(Instant.class), eq("DASHBOARD"),
                eq("1".repeat(PartnerTermsService.MAX_IP)), eq("u".repeat(PartnerTermsService.MAX_USER_AGENT)));
    }

    @Test
    @DisplayName("status: the latest acceptance and whether it is of the current version")
    void status() {
        PartnerTermsAcceptance old = acceptance(1L, "2026-01-01", "2026-01-02T00:00:00Z");
        when(repository.findFirstByUserIdOrderByAcceptedAtDescIdDesc(USER)).thenReturn(Optional.of(old));
        when(repository.existsByUserIdAndTermsVersion(USER, VERSION)).thenReturn(false);

        PartnerTermsService.Status s = service.status(USER);

        assertThat(s.currentVersion()).isEqualTo(VERSION);
        assertThat(s.latest().version()).isEqualTo("2026-01-01");
        assertThat(s.acceptedAny()).isTrue();
        assertThat(s.acceptedCurrent()).isFalse();
    }

    @Test
    @DisplayName("status of someone who never accepted: no acceptance, not current, not bound")
    void statusNever() {
        when(repository.findFirstByUserIdOrderByAcceptedAtDescIdDesc(USER)).thenReturn(Optional.empty());

        PartnerTermsService.Status s = service.status(USER);

        assertThat(s.latest()).isNull();
        assertThat(s.acceptedAny()).isFalse();
        assertThat(s.acceptedCurrent()).isFalse();
    }

    @Test
    @DisplayName("hasAcceptedAny: any version binds the partner; no user id is never bound")
    void hasAcceptedAny() {
        when(repository.existsByUserId(USER)).thenReturn(true);

        assertThat(service.hasAcceptedAny(USER)).isTrue();
        assertThat(service.hasAcceptedAny(8L)).isFalse();
        assertThat(service.hasAcceptedAny(null)).isFalse();
    }

    @Test
    @DisplayName("latestFor keeps each partner's most recent acceptance, whatever the order the rows come back in")
    void latestFor() {
        PartnerTermsAcceptance newer = acceptance(2L, VERSION, "2026-10-02T00:00:00Z");
        PartnerTermsAcceptance older = acceptance(1L, "2026-01-01", "2026-01-02T00:00:00Z");
        PartnerTermsAcceptance other = acceptance(3L, VERSION, "2026-10-03T00:00:00Z");
        other.setUserId(8L);
        when(repository.findByUserIdIn(List.of(USER, 8L))).thenReturn(List.of(newer, other, older));

        Map<Long, PartnerTermsService.Acceptance> latest = service.latestFor(List.of(USER, 8L));

        assertThat(latest.get(USER).version()).isEqualTo(VERSION);
        assertThat(latest.get(8L).acceptedAt()).isEqualTo(Instant.parse("2026-10-03T00:00:00Z"));
        assertThat(service.latestFor(List.of())).isEmpty();
    }

    private static PartnerTermsAcceptance acceptance(long id, String version, String at) {
        PartnerTermsAcceptance a = new PartnerTermsAcceptance();
        ReflectionTestUtils.setField(a, "id", id);
        a.setUserId(USER);
        a.setTermsVersion(version);
        a.setAcceptedAt(Instant.parse(at));
        a.setSource(Source.APPLICATION);
        return a;
    }

    @Test
    @DisplayName("the agreement rule: a partner without the current version is asked to accept; one without any version is not paid")
    void requiredAndPayoutsBlocked() {
        PartnerTermsService.Status never = new PartnerTermsService.Status(VERSION, null, false);
        PartnerTermsService.Status older = new PartnerTermsService.Status(VERSION,
                new PartnerTermsService.Acceptance("2026-01-01", Instant.parse("2026-01-02T00:00:00Z")), false);
        PartnerTermsService.Status current = new PartnerTermsService.Status(VERSION,
                new PartnerTermsService.Acceptance(VERSION, Instant.parse("2026-10-02T00:00:00Z")), true);

        assertThat(never.required(true)).isTrue();
        assertThat(never.payoutsBlocked(true)).isTrue();
        // An older version still binds the partner: asked to accept the new one, but still paid.
        assertThat(older.required(true)).isTrue();
        assertThat(older.payoutsBlocked(true)).isFalse();
        assertThat(current.required(true)).isFalse();
        assertThat(current.payoutsBlocked(true)).isFalse();
        // Someone without a partner code has nothing to accept and nothing to be paid.
        assertThat(never.required(false)).isFalse();
        assertThat(never.payoutsBlocked(false)).isFalse();
    }

    @Test
    @DisplayName("the version and the fingerprint of its text are constants: a blank or oversized version refuses to start")
    void versionIsAConstant() {
        assertThat(new PartnerTermsService(repository).currentVersion()).isEqualTo(PartnerTermsService.CURRENT_VERSION);
        assertThat(new PartnerTermsService(repository).currentFingerprint()).isEqualTo(PartnerTermsService.CURRENT_FINGERPRINT);
        assertThat(PartnerTermsService.CURRENT_FINGERPRINT).matches("sha256:[0-9a-f]{64}");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new PartnerTermsService(repository, " "))
                .isInstanceOf(IllegalStateException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new PartnerTermsService(repository, "x".repeat(33)))
                .isInstanceOf(IllegalStateException.class);
    }

}
