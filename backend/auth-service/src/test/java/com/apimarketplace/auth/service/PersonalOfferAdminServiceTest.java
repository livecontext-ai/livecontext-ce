package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.PersonalOfferMatrix;
import com.apimarketplace.auth.domain.PersonalOfferPolicy;
import com.apimarketplace.auth.repository.PersonalOfferMatrixRepository;
import com.apimarketplace.auth.repository.PersonalOfferPolicyRepository;
import com.apimarketplace.auth.service.PersonalOfferAdminService.Cell;
import com.apimarketplace.auth.service.PersonalOfferAdminService.PolicyInput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("Admin personal offer policies preserve every previously issued promise")
class PersonalOfferAdminServiceTest {
    private PersonalOfferPolicyRepository policies;
    private PersonalOfferMatrixRepository matrix;
    private JdbcTemplate jdbc;
    private PersonalOfferAdminService service;

    @BeforeEach
    void setUp() {
        policies = mock(PersonalOfferPolicyRepository.class);
        matrix = mock(PersonalOfferMatrixRepository.class);
        jdbc = mock(JdbcTemplate.class);
        service = new PersonalOfferAdminService(policies, matrix, jdbc);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACTIVE", "PAUSED"})
    @DisplayName("A published policy remains immutable even when the campaign is paused")
    void publishedTermsCannotBeEdited(String state) {
        PersonalOfferPolicy published = policy(1L, 1, state);
        when(policies.lockCampaign(PersonalOfferAdminService.CAMPAIGN)).thenReturn(List.of(published));

        assertThatThrownBy(() -> service.update(1L, input(List.of(new Cell("PRO", 50000, 8000)))))
                .isInstanceOf(PersonalOfferAdminService.InvalidPolicy.class).hasMessage("OFFER_POLICY_IMMUTABLE");

        assertThat(published.getValidityHours()).isEqualTo(72);
        verifyNoInteractions(matrix, jdbc);
    }

    @Test
    @DisplayName("Cloning commercial terms creates a new draft version without changing the active policy")
    void newVersionIsDraftAndDoesNotChangePublishedTerms() {
        PersonalOfferPolicy active = policy(1L, 4, "ACTIVE");
        when(policies.lockCampaign(PersonalOfferAdminService.CAMPAIGN)).thenReturn(List.of(active));
        when(policies.saveAndFlush(any())).thenAnswer(call -> {
            PersonalOfferPolicy saved = call.getArgument(0);
            saved.setId(2L);
            return saved;
        });

        var created = service.create(input(List.of(new Cell("PRO", 50000, 4000))));

        assertThat(created.state()).isEqualTo("DRAFT");
        assertThat(created.version()).isEqualTo(5);
        assertThat(created.id()).isEqualTo(2L);
        assertThat(active.getState()).isEqualTo("ACTIVE");
        assertThat(active.getVersion()).isEqualTo(4);
        verify(matrix, never()).deleteByPolicyId(any());
    }

    @Test
    @DisplayName("Activating a new policy pauses the previous version without touching issued codes")
    void activateReplacesCampaignVersionWithoutRevokingCodes() {
        PersonalOfferPolicy previous = policy(1L, 1, "ACTIVE");
        PersonalOfferPolicy next = policy(2L, 2, "DRAFT");
        PersonalOfferMatrix positive = new PersonalOfferMatrix();
        positive.setBonusCredits(8000);
        positive.setPlanCode("PRO");
        positive.setMonthlyCredits(50000);
        when(policies.lockCampaign(PersonalOfferAdminService.CAMPAIGN)).thenReturn(List.of(previous, next));
        when(matrix.findByPolicyId(2L)).thenReturn(List.of(positive));
        when(policies.saveAndFlush(next)).thenReturn(next);

        var published = service.activate(2L);

        assertThat(published.state()).isEqualTo("ACTIVE");
        assertThat(previous.getState()).isEqualTo("PAUSED");
        verifyNoInteractions(jdbc);
        verify(matrix, never()).deleteByPolicyId(any());
    }

    @Test
    @DisplayName("Pausing a campaign preserves the promised matrix and issued codes")
    void pauseDoesNotRevokeOffers() {
        PersonalOfferPolicy active = policy(1L, 1, "ACTIVE");
        when(policies.lockCampaign(PersonalOfferAdminService.CAMPAIGN)).thenReturn(List.of(active));
        when(policies.save(active)).thenReturn(active);

        assertThat(service.pause(1L).state()).isEqualTo("PAUSED");

        verifyNoInteractions(jdbc);
        verify(matrix, never()).deleteByPolicyId(any());
    }

    @Test
    @DisplayName("Impossible Starter packs, unknown plans, duplicates and negative bonuses are refused before persistence")
    void invalidMatrixNeverReachesStorage() {
        for (List<Cell> cells : List.of(
                List.of(new Cell("STARTER", 250000, 8000)),
                List.of(new Cell("FREE", 5000, 8000)),
                List.of(new Cell("PRO", 1234, 8000)),
                List.of(new Cell("PRO", 50000, -1)),
                List.of(new Cell("PRO", 50000, 8000), new Cell("PRO", 50000, 0)))) {
            assertThatThrownBy(() -> service.create(input(cells)))
                    .isInstanceOf(PersonalOfferAdminService.InvalidPolicy.class).hasMessage("OFFER_MATRIX_INVALID");
        }
        verifyNoInteractions(policies, matrix, jdbc);
    }

    @Test
    @DisplayName("A reminder outside the validity window is refused even before a draft is saved")
    void invalidReminderCannotBeSaved() {
        PolicyInput request = new PolicyInput(PersonalOfferAdminService.CAMPAIGN, "Test", 4, 12,
                30, true, 12, 800, false, List.of(new Cell("PRO", 50000, 8000)));
        assertThatThrownBy(() -> service.create(request)).hasMessage("OFFER_POLICY_INVALID");
        verifyNoInteractions(policies, matrix, jdbc);
    }

    @Test
    @DisplayName("Revocation is scoped to a personal offer ID and refuses unrelated reward codes")
    void disableCannotRevokeAnotherProgram() {
        when(jdbc.update("UPDATE auth.reward_code SET active=FALSE WHERE id=? AND program='PERSONAL_UPGRADE'", 8L))
                .thenReturn(0);
        assertThatThrownBy(() -> service.disableCode(8L)).hasMessage("OFFER_UNAVAILABLE");
    }

    private PolicyInput input(List<Cell> cells) {
        return new PolicyInput(PersonalOfferAdminService.CAMPAIGN, "Offer", 4, 72, 30, false, 12, 800, false, cells);
    }

    private PersonalOfferPolicy policy(long id, int version, String state) {
        PersonalOfferPolicy policy = new PersonalOfferPolicy();
        policy.setId(id);
        policy.setCampaignKey(PersonalOfferAdminService.CAMPAIGN);
        policy.setLabel("Offer");
        policy.setVersion(version);
        policy.setState(state);
        return policy;
    }
}
