package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.service.oauth.OAuthProfile;
import com.apimarketplace.auth.service.oauth.OAuthProfileNormalizer;
import com.apimarketplace.auth.service.oauth.OAuthUserProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Service OIDC pour la gestion des utilisateurs Google (OpenID Connect).
 * Utilise OAuthUserProcessor pour eliminer la duplication avec OAuth2UserService.
 */
@Component
public class OidcUserService extends org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService {

    private static final Logger log = LoggerFactory.getLogger(OidcUserService.class);

    private final OAuthProfileNormalizer profileNormalizer;
    private final OAuthUserProcessor userProcessor;

    public OidcUserService(OAuthProfileNormalizer profileNormalizer, OAuthUserProcessor userProcessor) {
        this.profileNormalizer = profileNormalizer;
        this.userProcessor = userProcessor;
    }

    @Override
    @Transactional
    public OidcUser loadUser(OidcUserRequest request) {
        OidcUser oidc = super.loadUser(request);
        Map<String, Object> attrs = new LinkedHashMap<>(oidc.getAttributes());

        // Normaliser le profil Google (OIDC)
        OAuthProfile profile = profileNormalizer.normalizeGoogle(attrs);

        // Creer/Mettre a jour l'utilisateur via le processeur centralise
        User user = userProcessor.upsertUser(profile);

        // Enrichir les attributs pour le SecurityContext
        attrs.put("appUserId", user.getId());
        attrs.put("appUsername", user.getUsername());

        return new DefaultOidcUser(oidc.getAuthorities(), oidc.getIdToken(), oidc.getUserInfo(), "sub");
    }
}

