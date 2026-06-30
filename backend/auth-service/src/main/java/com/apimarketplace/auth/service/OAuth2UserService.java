package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.service.oauth.OAuthProfile;
import com.apimarketplace.auth.service.oauth.OAuthProfileNormalizer;
import com.apimarketplace.auth.service.oauth.OAuthUserProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Service OAuth2 pour la gestion des utilisateurs Google et GitHub.
 * Utilise OAuthUserProcessor pour eliminer la duplication avec OidcUserService.
 */
@Component
public class OAuth2UserService implements org.springframework.security.oauth2.client.userinfo.OAuth2UserService<OAuth2UserRequest, OAuth2User> {

    private static final Logger log = LoggerFactory.getLogger(OAuth2UserService.class);

    private final DefaultOAuth2UserService delegate = new DefaultOAuth2UserService();
    private final OAuthProfileNormalizer profileNormalizer;
    private final OAuthUserProcessor userProcessor;

    public OAuth2UserService(OAuthProfileNormalizer profileNormalizer, OAuthUserProcessor userProcessor) {
        this.profileNormalizer = profileNormalizer;
        this.userProcessor = userProcessor;
    }

    @Override
    @Transactional
    public OAuth2User loadUser(OAuth2UserRequest request) {
        OAuth2User oauth = delegate.loadUser(request);
        String registrationId = request.getClientRegistration().getRegistrationId();
        Map<String, Object> attrs = new LinkedHashMap<>(oauth.getAttributes());

        // 1) Normaliser le profil selon le provider
        OAuthProfile profile = normalizeProfile(registrationId, attrs, request.getAccessToken().getTokenValue());

        // 2) Creer/Mettre a jour l'utilisateur via le processeur centralise
        User user = userProcessor.upsertUser(profile);

        // 3) Enrichir les attributs pour le SecurityContext
        attrs.put("appUserId", user.getId());
        attrs.put("appUsername", user.getUsername());

        // 4) Retourner le principal Spring
        String nameAttr = request.getClientRegistration().getProviderDetails()
                .getUserInfoEndpoint().getUserNameAttributeName();

        return new DefaultOAuth2User(oauth.getAuthorities(), attrs, nameAttr);
    }

    private OAuthProfile normalizeProfile(String registrationId, Map<String, Object> attrs, String accessToken) {
        return switch (registrationId) {
            case "google" -> profileNormalizer.normalizeGoogle(attrs);
            case "github" -> profileNormalizer.normalizeGithub(attrs, accessToken);
            default -> {
                log.warn("Unknown OAuth provider: {}", registrationId);
                yield profileNormalizer.normalizeGoogle(attrs);
            }
        };
    }
}
