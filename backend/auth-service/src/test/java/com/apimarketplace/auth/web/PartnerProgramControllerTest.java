package com.apimarketplace.auth.web;

import com.apimarketplace.auth.audit.AuditEventTypes;
import com.apimarketplace.auth.audit.AuditLogger;
import com.apimarketplace.auth.domain.PartnerApplication;
import com.apimarketplace.auth.domain.RewardCode;
import com.apimarketplace.auth.service.PartnerProgramAdminService.Amounts;
import com.apimarketplace.auth.service.PartnerProgramService;
import com.apimarketplace.auth.service.PartnerProgramService.Dashboard;
import com.apimarketplace.auth.service.PartnerProgramService.Line;
import com.apimarketplace.auth.service.PartnerProgramService.Outcome;
import com.apimarketplace.auth.service.PartnerProgramService.Terms;
import com.apimarketplace.auth.service.PartnerTermsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** V553 partner-facing HTTP surface: public terms, the dashboard, the application submit, and (V557) the terms acceptance. */
@DisplayName("PartnerProgramController")
class PartnerProgramControllerTest {

    private static final Terms TERMS = new Terms(30.0, 12, 14, 10_000, List.of(
            new com.apimarketplace.auth.service.PartnerTierService.TierTerm(com.apimarketplace.auth.domain.PartnerTier.SILVER, 3000, 0L),
            new com.apimarketplace.auth.service.PartnerTierService.TierTerm(com.apimarketplace.auth.domain.PartnerTier.GOLD, 4000, 500_000L),
            new com.apimarketplace.auth.service.PartnerTierService.TierTerm(com.apimarketplace.auth.domain.PartnerTier.PLATINUM, 5000, 2_500_000L)),
            "usd", 60, Instant.parse("2027-01-01T00:00:00Z"), true);

    private static final String VERSION = "2026-10-01";
    /** A user who never accepted the Partner Program Terms. */
    private static final PartnerTermsService.Status NO_AGREEMENT = new PartnerTermsService.Status(VERSION, null, false);

    private PartnerProgramService service;
    private PartnerTermsService termsService;
    private AuditLogger auditLogger;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(PartnerProgramService.class);
        termsService = mock(PartnerTermsService.class);
        auditLogger = mock(AuditLogger.class, RETURNS_DEEP_STUBS);
        when(service.terms()).thenReturn(TERMS);
        when(termsService.currentVersion()).thenReturn(VERSION);
        mvc = MockMvcBuilders.standaloneSetup(new PartnerProgramController(service, termsService, auditLogger, false)).build();
    }

    @Test
    @DisplayName("GET terms is anonymous and returns the program defaults")
    void termsAnonymous() throws Exception {
        mvc.perform(get("/api/public/partner-program/terms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commission_percent").value(30.0))
                .andExpect(jsonPath("$.commission_months").value(12))
                .andExpect(jsonPath("$.hold_days").value(14))
                .andExpect(jsonPath("$.audience_credits").value(10_000))
                // V556: the three tiers, lower-case, in order, with their rate and threshold.
                .andExpect(jsonPath("$.tiers[0].tier").value("silver"))
                .andExpect(jsonPath("$.tiers[1].commission_percent").value(40.0))
                .andExpect(jsonPath("$.tiers[1].threshold_minor").value(500_000))
                .andExpect(jsonPath("$.tiers[2].tier").value("platinum"))
                .andExpect(jsonPath("$.tier_currency").value("usd"))
                .andExpect(jsonPath("$.tier_settle_days").value(60))
                .andExpect(jsonPath("$.founder_until").value("2027-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.founder_open").value(true))
                // V557: the terms version the application form sends back.
                .andExpect(jsonPath("$.terms_version").value(VERSION));
    }

    @Test
    @DisplayName("self-hosted (credits unlimited): every endpoint answers 503 and touches nothing")
    void ceRefuses() throws Exception {
        MockMvc ce = MockMvcBuilders.standaloneSetup(new PartnerProgramController(service, termsService, auditLogger, true)).build();

        ce.perform(get("/api/public/partner-program/terms")).andExpect(status().isServiceUnavailable());
        ce.perform(get("/api/billing/partner/me").header("X-User-ID", "7")).andExpect(status().isServiceUnavailable());
        ce.perform(post("/api/billing/partner/applications").header("X-User-ID", "7")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"company_name\":\"Acme\"}"))
                .andExpect(status().isServiceUnavailable());
        verify(service, never()).dashboard(any());
        verify(service, never()).apply(any(), any(), any());
    }

    @Test
    @DisplayName("GET me without a user (or a non-numeric one) is 401")
    void meNeedsUser() throws Exception {
        mvc.perform(get("/api/billing/partner/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/billing/partner/me").header("X-User-ID", "abc")).andExpect(status().isUnauthorized());
        verify(service, never()).dashboard(any());
    }

    @Test
    @DisplayName("GET me for a non-partner: state and terms, no partner block")
    void meNone() throws Exception {
        when(service.dashboard(7L)).thenReturn(new Dashboard("none", TERMS, null, null, 0, 0, null, List.of(), null, null, NO_AGREEMENT));

        mvc.perform(get("/api/billing/partner/me").header("X-User-ID", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("none"))
                .andExpect(jsonPath("$.terms.commission_percent").value(30.0))
                .andExpect(jsonPath("$.partner").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    @DisplayName("GET me for a partner: code, per-code terms, totals and lines, never a customer id")
    void mePartner() throws Exception {
        RewardCode c = new RewardCode();
        c.setCode("ACME");
        c.setPayoutBps(4000);
        c.setPayoutMonths(12);
        c.setHoldDays(14);
        c.setBenefitAmount(12_000);
        c.setValidUntil(Instant.parse("2027-01-01T00:00:00Z"));
        c.setCapLimit(25);
        Instant paid = Instant.parse("2026-09-01T10:00:00Z");
        Dashboard d = new Dashboard("active", TERMS, null, c, 5, 2,
                new Amounts(Map.of("eur", 100L), Map.of("eur", 200L), Map.of("eur", 300L), Map.of("eur", 50L)),
                List.of(new Line(paid, "eur", 2000, 800, "on_hold", paid.plusSeconds(86400 * 14), null)),
                new com.apimarketplace.auth.service.PartnerTierService.Standing(com.apimarketplace.auth.domain.PartnerTier.GOLD,
                        false, 600_000L, "usd", com.apimarketplace.auth.domain.PartnerTier.PLATINUM, 2_500_000L, 4000),
                45.0, NO_AGREEMENT);
        when(service.dashboard(7L)).thenReturn(d);

        mvc.perform(get("/api/billing/partner/me").header("X-User-ID", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("active"))
                .andExpect(jsonPath("$.partner.code").value("ACME"))
                // The effective rate (a custom deal above the Gold rate here), not the stored code rate.
                .andExpect(jsonPath("$.partner.commission_percent").value(45.0))
                .andExpect(jsonPath("$.partner.standing.tier").value("gold"))
                .andExpect(jsonPath("$.partner.standing.founder").value(false))
                .andExpect(jsonPath("$.partner.standing.revenue_minor").value(600_000))
                .andExpect(jsonPath("$.partner.standing.next_tier").value("platinum"))
                .andExpect(jsonPath("$.partner.standing.next_threshold_minor").value(2_500_000))
                .andExpect(jsonPath("$.partner.redemptions").value(5))
                .andExpect(jsonPath("$.partner.paying_customers").value(2))
                .andExpect(jsonPath("$.partner.commissions.on_hold.eur").value(100))
                .andExpect(jsonPath("$.partner.commissions.payable.eur").value(200))
                .andExpect(jsonPath("$.partner.commissions.paid.eur").value(300))
                .andExpect(jsonPath("$.partner.commissions.voided.eur").value(50))
                // The code's own values, not the program defaults: this partner has 12,000 credits.
                .andExpect(jsonPath("$.partner.audience_credits").value(12_000))
                .andExpect(jsonPath("$.partner.commission_months").value(12))
                .andExpect(jsonPath("$.partner.hold_days").value(14))
                .andExpect(jsonPath("$.partner.valid_until").value("2027-01-01T00:00:00Z"))
                // The use cap is shown on the partner page too (terms clause 6.1).
                .andExpect(jsonPath("$.partner.max_uses").value(25))
                .andExpect(jsonPath("$.partner.lines[0].due_at").value("2026-09-15T10:00:00Z"))
                .andExpect(jsonPath("$.partner.lines[0].commission_minor").value(800))
                .andExpect(jsonPath("$.partner.lines[0].status").value("on_hold"))
                .andExpect(jsonPath("$.partner.lines[0].customer_user_id").doesNotExist());
    }

    @Test
    @DisplayName("GET me: without an effective rate (older service), the code's own rate is shown")
    void meFallsBackToTheCodeRate() throws Exception {
        RewardCode c = new RewardCode();
        c.setCode("ACME");
        c.setPayoutBps(3500);
        c.setPayoutMonths(12);
        c.setHoldDays(14);
        c.setBenefitAmount(10_000);
        when(service.dashboard(7L)).thenReturn(new Dashboard("active", TERMS, null, c, 0, 0,
                new Amounts(Map.of(), Map.of(), Map.of(), Map.of()), List.of(), null, null, NO_AGREEMENT));

        mvc.perform(get("/api/billing/partner/me").header("X-User-ID", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partner.commission_percent").value(35.0))
                .andExpect(jsonPath("$.partner.standing").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    @DisplayName("POST applications: 201 with the stored application")
    void applyCreated() throws Exception {
        PartnerApplication a = new PartnerApplication();
        a.setId(3L);
        a.setUserId(7L);
        a.setCompanyName("Acme");
        when(service.apply(eq(7L), any(), any())).thenReturn(new Outcome(a, null));

        mvc.perform(post("/api/billing/partner/applications").header("X-User-ID", "7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"company_name\":\"Acme\",\"website\":\"https://acme.io\",\"terms_version\":\"2026-10-01\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.application.status").value("pending"))
                .andExpect(jsonPath("$.application.company_name").value("Acme"));
        verify(service).apply(eq(7L), eq(new PartnerProgramService.ApplicationForm("Acme", "https://acme.io", null, null, VERSION)), any());
    }

    @Test
    @DisplayName("POST applications: refusals map to 409 (duplicate) or 400 (invalid), a lost race to 409")
    void applyRefusals() throws Exception {
        when(service.apply(eq(7L), any(), any())).thenReturn(new Outcome(null, "already_pending"));
        mvc.perform(post("/api/billing/partner/applications").header("X-User-ID", "7")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"company_name\":\"Acme\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("already_pending"));

        when(service.apply(eq(7L), any(), any())).thenReturn(new Outcome(null, "invalid_website"));
        mvc.perform(post("/api/billing/partner/applications").header("X-User-ID", "7")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"company_name\":\"Acme\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_website"));

        when(service.apply(eq(7L), any(), any())).thenThrow(new DataIntegrityViolationException("uq_partner_application_pending_user"));
        mvc.perform(post("/api/billing/partner/applications").header("X-User-ID", "7")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"company_name\":\"Acme\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("already_pending"));
    }

    @Test
    @DisplayName("POST applications: an integrity error on another constraint is a real fault, not dressed up as already_pending")
    void applyOtherIntegrityErrorPropagates() {
        when(service.apply(eq(7L), any(), any()))
                .thenThrow(new DataIntegrityViolationException("x", new RuntimeException("chk_partner_application_status")));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> mvc.perform(post("/api/billing/partner/applications")
                        .header("X-User-ID", "7").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"company_name\":\"Acme\"}")))
                .hasRootCauseMessage("chk_partner_application_status");
    }

    @Test
    @DisplayName("POST applications without a user is 401 and applies nothing")
    void applyNeedsUser() throws Exception {
        mvc.perform(post("/api/billing/partner/applications")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"company_name\":\"Acme\"}"))
                .andExpect(status().isUnauthorized());
        verify(service, never()).apply(any(), any(), any());
    }
    @Test
    @DisplayName("POST applications: the ticked terms version and the evidence of the click (address, browser) reach the service")
    void applyForwardsTermsVersionAndEvidence() throws Exception {
        PartnerApplication a = new PartnerApplication();
        a.setUserId(7L);
        a.setCompanyName("Acme");
        when(service.apply(eq(7L), any(), any())).thenReturn(new Outcome(a, null));

        mvc.perform(post("/api/billing/partner/applications").header("X-User-ID", "7")
                        .header("X-Forwarded-For", "203.0.113.7, 10.0.0.1").header("User-Agent", "Mozilla/5.0 Test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"company_name\":\"Acme\",\"terms_version\":\"2026-10-01\"}"))
                .andExpect(status().isCreated());

        ArgumentCaptor<PartnerTermsService.Evidence> evidence = ArgumentCaptor.forClass(PartnerTermsService.Evidence.class);
        verify(service).apply(eq(7L), eq(new PartnerProgramService.ApplicationForm("Acme", null, null, null, VERSION)),
                evidence.capture());
        org.assertj.core.api.Assertions.assertThat(evidence.getValue().ip()).isEqualTo("203.0.113.7");
        org.assertj.core.api.Assertions.assertThat(evidence.getValue().userAgent()).isEqualTo("Mozilla/5.0 Test");
        // The acceptance that came with the application leaves an audit event.
        verify(auditLogger).eventFromRequest(eq(AuditEventTypes.PARTNER_TERMS_ACCEPTED), any());
    }

    @Test
    @DisplayName("regression: the evidence address is Cloudflare's CF-Connecting-IP, not the X-Forwarded-For entry a client can forge")
    void evidencePrefersCloudflareAddress() throws Exception {
        PartnerApplication a = new PartnerApplication();
        a.setUserId(7L);
        a.setCompanyName("Acme");
        when(service.apply(eq(7L), any(), any())).thenReturn(new Outcome(a, null));

        mvc.perform(post("/api/billing/partner/applications").header("X-User-ID", "7")
                        .header("CF-Connecting-IP", "198.51.100.23")
                        .header("X-Forwarded-For", "6.6.6.6, 198.51.100.23, 10.0.0.1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"company_name\":\"Acme\",\"terms_version\":\"2026-10-01\"}"))
                .andExpect(status().isCreated());

        ArgumentCaptor<PartnerTermsService.Evidence> evidence = ArgumentCaptor.forClass(PartnerTermsService.Evidence.class);
        verify(service).apply(eq(7L), any(), evidence.capture());
        org.assertj.core.api.Assertions.assertThat(evidence.getValue().ip()).isEqualTo("198.51.100.23");
    }

    @Test
    @DisplayName("evidence: a CF-Connecting-IP that is not an address is ignored, the forwarded address is the fallback")
    void evidenceFallsBackWhenCloudflareHeaderIsNotAnAddress() {
        org.springframework.mock.web.MockHttpServletRequest request = new org.springframework.mock.web.MockHttpServletRequest();
        request.addHeader("CF-Connecting-IP", "not-an-ip; DROP");
        request.addHeader("X-Forwarded-For", "203.0.113.9");
        request.addHeader("User-Agent", "UA");

        PartnerTermsService.Evidence e = PartnerProgramController.evidence(request);

        org.assertj.core.api.Assertions.assertThat(e.ip()).isEqualTo("203.0.113.9");
        org.assertj.core.api.Assertions.assertThat(e.userAgent()).isEqualTo("UA");
        org.assertj.core.api.Assertions.assertThat(PartnerProgramController.evidence(null)).isEqualTo(PartnerTermsService.Evidence.none());
    }

    @Test
    @DisplayName("POST applications: terms not ticked is 400, terms changed since the form loaded is 409; no acceptance audited")
    void applyTermsRefusals() throws Exception {
        when(service.apply(eq(7L), any(), any())).thenReturn(new Outcome(null, "terms_not_accepted"));
        mvc.perform(post("/api/billing/partner/applications").header("X-User-ID", "7")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"company_name\":\"Acme\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("terms_not_accepted"));

        when(service.apply(eq(7L), any(), any())).thenReturn(new Outcome(null, "terms_outdated"));
        mvc.perform(post("/api/billing/partner/applications").header("X-User-ID", "7")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"company_name\":\"Acme\",\"terms_version\":\"2020-01-01\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("terms_outdated"));

        verify(auditLogger, never()).eventFromRequest(eq(AuditEventTypes.PARTNER_TERMS_ACCEPTED), any());
    }

    @Test
    @DisplayName("POST terms/accept: records the acceptance with its evidence, audits it and returns the new standing")
    void acceptTerms() throws Exception {
        when(service.acceptTerms(eq(7L), eq(VERSION), any())).thenReturn(null);
        when(termsService.status(7L)).thenReturn(new PartnerTermsService.Status(VERSION,
                new PartnerTermsService.Acceptance(VERSION, Instant.parse("2026-10-02T09:00:00Z")), true));

        mvc.perform(post("/api/billing/partner/terms/accept").header("X-User-ID", "7")
                        .header("X-Forwarded-For", "198.51.100.4").header("User-Agent", "UA")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"terms_version\":\"2026-10-01\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.agreement.accepted_version").value(VERSION))
                .andExpect(jsonPath("$.agreement.accepted_at").value("2026-10-02T09:00:00Z"))
                .andExpect(jsonPath("$.agreement.required").value(false))
                .andExpect(jsonPath("$.agreement.payouts_blocked").value(false));

        ArgumentCaptor<PartnerTermsService.Evidence> evidence = ArgumentCaptor.forClass(PartnerTermsService.Evidence.class);
        verify(service).acceptTerms(eq(7L), eq(VERSION), evidence.capture());
        org.assertj.core.api.Assertions.assertThat(evidence.getValue().ip()).isEqualTo("198.51.100.4");
        verify(auditLogger).eventFromRequest(eq(AuditEventTypes.PARTNER_TERMS_ACCEPTED), any());
    }

    @Test
    @DisplayName("POST terms/accept refusals: no user 401, not a partner 403, outdated 409, nothing ticked 400; never audited")
    void acceptTermsRefusals() throws Exception {
        mvc.perform(post("/api/billing/partner/terms/accept")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"terms_version\":\"2026-10-01\"}"))
                .andExpect(status().isUnauthorized());
        verify(service, never()).acceptTerms(any(), any(), any());

        when(service.acceptTerms(eq(7L), any(), any())).thenReturn("not_partner");
        mvc.perform(post("/api/billing/partner/terms/accept").header("X-User-ID", "7")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"terms_version\":\"2026-10-01\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("not_partner"));

        when(service.acceptTerms(eq(7L), any(), any())).thenReturn("terms_outdated");
        mvc.perform(post("/api/billing/partner/terms/accept").header("X-User-ID", "7")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"terms_version\":\"2020-01-01\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("terms_outdated"));

        when(service.acceptTerms(eq(7L), any(), any())).thenReturn("terms_not_accepted");
        mvc.perform(post("/api/billing/partner/terms/accept").header("X-User-ID", "7")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("terms_not_accepted"));

        verify(auditLogger, never()).eventFromRequest(eq(AuditEventTypes.PARTNER_TERMS_ACCEPTED), any());
    }

    @Test
    @DisplayName("POST terms/accept on a self-hosted build is 503 and records nothing")
    void acceptTermsCe() throws Exception {
        MockMvc ce = MockMvcBuilders.standaloneSetup(new PartnerProgramController(service, termsService, auditLogger, true)).build();

        ce.perform(post("/api/billing/partner/terms/accept").header("X-User-ID", "7")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"terms_version\":\"2026-10-01\"}"))
                .andExpect(status().isServiceUnavailable());
        verify(service, never()).acceptTerms(any(), any(), any());
    }

    @Test
    @DisplayName("GET me: a partner who never accepted the terms must accept them, and is paid nothing until they do")
    void meAgreementNeverAccepted() throws Exception {
        when(service.dashboard(7L)).thenReturn(partnerDashboard(NO_AGREEMENT));

        mvc.perform(get("/api/billing/partner/me").header("X-User-ID", "7"))
                .andExpect(jsonPath("$.agreement.current_version").value(VERSION))
                .andExpect(jsonPath("$.agreement.accepted_version").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.agreement.required").value(true))
                .andExpect(jsonPath("$.agreement.payouts_blocked").value(true));
    }

    @Test
    @DisplayName("GET me: a partner on an older version is asked to accept the new one, but stays payable")
    void meAgreementOlderVersion() throws Exception {
        when(service.dashboard(7L)).thenReturn(partnerDashboard(new PartnerTermsService.Status(VERSION,
                new PartnerTermsService.Acceptance("2026-01-01", Instant.parse("2026-01-02T00:00:00Z")), false)));

        mvc.perform(get("/api/billing/partner/me").header("X-User-ID", "7"))
                .andExpect(jsonPath("$.agreement.accepted_version").value("2026-01-01"))
                .andExpect(jsonPath("$.agreement.accepted_current").value(false))
                .andExpect(jsonPath("$.agreement.required").value(true))
                .andExpect(jsonPath("$.agreement.payouts_blocked").value(false));
    }

    @Test
    @DisplayName("GET me: someone who is not a partner is never asked to accept anything from the dashboard")
    void meAgreementNotPartner() throws Exception {
        when(service.dashboard(7L)).thenReturn(new Dashboard("pending", TERMS, null, null, 0, 0, null, List.of(), null, null,
                NO_AGREEMENT));

        mvc.perform(get("/api/billing/partner/me").header("X-User-ID", "7"))
                .andExpect(jsonPath("$.agreement.required").value(false))
                .andExpect(jsonPath("$.agreement.payouts_blocked").value(false));
    }

    private static Dashboard partnerDashboard(PartnerTermsService.Status agreement) {
        RewardCode c = new RewardCode();
        c.setCode("ACME");
        c.setPayoutBps(3000);
        return new Dashboard("active", TERMS, null, c, 0, 0,
                new Amounts(Map.of(), Map.of(), Map.of(), Map.of()), List.of(), null, 30.0, agreement);
    }
}
