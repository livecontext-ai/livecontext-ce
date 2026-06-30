package com.apimarketplace.auth.service.oauth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Normalise les profils OAuth provenant de differents providers.
 * Applique le principe OCP (Open/Closed Principle) - extensible sans modification.
 */
@Component
public class OAuthProfileNormalizer {

    private static final Logger log = LoggerFactory.getLogger(OAuthProfileNormalizer.class);
    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * Normalise un profil Google OAuth.
     */
    public OAuthProfile normalizeGoogle(Map<String, Object> attributes) {
        String sub = extractString(attributes, "sub");
        String email = extractString(attributes, "email");
        boolean emailVerified = extractBoolean(attributes, "email_verified");
        String givenName = extractString(attributes, "given_name");
        String familyName = extractString(attributes, "family_name");
        String name = extractString(attributes, "name");
        String picture = extractString(attributes, "picture");

        return OAuthProfile.builder()
                .provider("google")
                .providerId(sub)
                .email(email)
                .emailVerified(emailVerified)
                .firstName(givenName)
                .lastName(familyName)
                .displayName(name)
                .avatarUrl(picture)
                .build();
    }

    /**
     * Normalise un profil GitHub OAuth.
     */
    public OAuthProfile normalizeGithub(Map<String, Object> attributes, String accessToken) {
        String id = String.valueOf(attributes.get("id"));
        String email = extractString(attributes, "email");
        String login = extractString(attributes, "login");
        String name = extractString(attributes, "name");
        String avatar = extractString(attributes, "avatar_url");

        // Fallback: recuperer l'email via l'API GitHub si non public
        if (email == null && accessToken != null) {
            email = fetchGithubPrimaryEmail(accessToken);
        }

        // Extraire prenom/nom du display name
        String firstName = null;
        String lastName = null;
        if (name != null && name.contains(" ")) {
            int lastSpace = name.lastIndexOf(' ');
            firstName = name.substring(0, lastSpace).trim();
            lastName = name.substring(lastSpace + 1).trim();
        }

        return OAuthProfile.builder()
                .provider("github")
                .providerId(id)
                .email(email)
                .emailVerified(email != null)
                .firstName(firstName)
                .lastName(lastName)
                .displayName(name != null ? name : login)
                .avatarUrl(avatar)
                .build();
    }

    private String fetchGithubPrimaryEmail(String accessToken) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(accessToken);
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));

            ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                    "https://api.github.com/user/emails",
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    new ParameterizedTypeReference<>() {}
            );

            if (response.getBody() != null) {
                return response.getBody().stream()
                        .filter(m -> Boolean.TRUE.equals(m.get("primary")))
                        .map(m -> (String) m.get("email"))
                        .findFirst()
                        .orElseGet(() -> response.getBody().stream()
                                .filter(m -> Boolean.TRUE.equals(m.get("verified")))
                                .map(m -> (String) m.get("email"))
                                .findFirst()
                                .orElse(null));
            }
        } catch (Exception e) {
            log.warn("Failed to fetch GitHub email: {}", e.getMessage());
        }
        return null;
    }

    private String extractString(Map<String, Object> attributes, String key) {
        Object value = attributes.get(key);
        return value != null ? String.valueOf(value) : null;
    }

    private boolean extractBoolean(Map<String, Object> attributes, String key) {
        Object value = attributes.get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return "true".equalsIgnoreCase(String.valueOf(value));
    }
}
