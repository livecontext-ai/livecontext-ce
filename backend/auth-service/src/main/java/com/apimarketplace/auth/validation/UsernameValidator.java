package com.apimarketplace.auth.validation;

import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.auth.util.ReservedUsernames;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;

/**
 * Composant responsable de la validation et normalisation des usernames.
 * Applique le principe SRP (Single Responsibility Principle).
 */
@Component
public class UsernameValidator {

    private static final int MIN_LENGTH = 3;
    private static final int MAX_LENGTH = 20;
    private static final int MAX_NORMALIZED_LENGTH = 32;
    private static final String VALID_PATTERN = "^[a-zA-Z0-9_-]+$";

    private final UserRepository userRepository;

    public UsernameValidator(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Valide un username selon les regles metier.
     * @param username Le username a valider
     * @param currentUserId L'ID de l'utilisateur courant (pour exclure lors de la verification d'unicite)
     * @return Optional vide si valide, sinon message d'erreur
     */
    public Optional<String> validate(String username, Long currentUserId) {
        if (username == null || username.isBlank()) {
            return Optional.of("Username cannot be empty");
        }

        String trimmed = username.trim();

        if (trimmed.length() < MIN_LENGTH || trimmed.length() > MAX_LENGTH) {
            return Optional.of("Username must be between " + MIN_LENGTH + " and " + MAX_LENGTH + " characters");
        }

        if (!trimmed.matches(VALID_PATTERN)) {
            return Optional.of("Username can only contain letters, numbers, hyphens, and underscores");
        }

        if (ReservedUsernames.isReserved(trimmed)) {
            return Optional.of("This username is reserved and cannot be used");
        }

        if (isUsernameTaken(trimmed, currentUserId)) {
            return Optional.of("This username is already taken");
        }

        return Optional.empty();
    }

    /**
     * Verifie si un username est deja utilise par un autre utilisateur.
     */
    public boolean isUsernameTaken(String username, Long excludeUserId) {
        return userRepository.findByUsername(username)
                .filter(user -> !user.getId().equals(excludeUserId))
                .isPresent();
    }

    /**
     * Normalise un username (pour les creations automatiques).
     * Supprime les diacritiques, met en minuscules, remplace les caracteres invalides.
     */
    public String normalize(String input) {
        if (input == null) {
            return null;
        }

        String normalized = Normalizer.normalize(input, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");

        normalized = normalized.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]", "_")
                .replaceAll("_+", "_");

        if (normalized.length() > MAX_NORMALIZED_LENGTH) {
            normalized = normalized.substring(0, MAX_NORMALIZED_LENGTH);
        }

        return normalized;
    }

    /**
     * Genere un username unique a partir d'une base.
     * Ajoute un suffixe numerique si necessaire.
     */
    public String generateUniqueUsername(String base) {
        String normalized = normalize(base);
        if (normalized == null || normalized.isBlank()) {
            normalized = "user";
        }

        String candidate = normalized;
        int counter = 1;

        while (userRepository.existsByUsername(candidate)) {
            candidate = normalized + "_" + counter;
            counter++;
            if (counter > 50) {
                candidate = normalized + "_" + System.currentTimeMillis();
                break;
            }
        }

        return candidate;
    }

    /**
     * Builds a username from a providerId.
     * Keycloak sub is a UUID, so take first 8 chars with "kc_" prefix.
     */
    public String buildUsernameFromProviderId(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            return "kc_user";
        }

        // Keycloak sub is UUID, take first 8 chars
        String shortId = providerId.length() > 8 ? providerId.substring(0, 8) : providerId;
        String base = normalize("kc_" + shortId);

        if (base == null || base.isBlank()) {
            base = "kc_user";
        }

        if (base.length() > MAX_NORMALIZED_LENGTH) {
            base = base.substring(0, MAX_NORMALIZED_LENGTH);
        }

        return base;
    }
}
