package com.apimarketplace.auth.validation;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Composant responsable de la validation de l'age (date de naissance).
 * Applique le principe SRP (Single Responsibility Principle).
 */
@Component
public class AgeValidator {

    private static final int MIN_AGE = 18;
    private static final int MAX_AGE = 120;

    /**
     * Valide une date de naissance.
     * @param birthDate La date de naissance
     * @return Optional vide si valide, sinon message d'erreur
     */
    public Optional<String> validate(LocalDateTime birthDate) {
        if (birthDate == null) {
            return Optional.of("Birth date is required");
        }

        int age = calculateAge(birthDate);

        if (age < MIN_AGE) {
            return Optional.of("Sorry, you need to be at least " + MIN_AGE + " years old to use our service");
        }

        if (age > MAX_AGE) {
            return Optional.of("Please enter a valid birth date");
        }

        return Optional.empty();
    }

    /**
     * Calcule l'age a partir d'une date de naissance.
     */
    public int calculateAge(LocalDateTime birthDate) {
        if (birthDate == null) {
            return 0;
        }

        LocalDateTime now = LocalDateTime.now();
        int age = now.getYear() - birthDate.getYear();

        if (now.getMonthValue() < birthDate.getMonthValue() ||
            (now.getMonthValue() == birthDate.getMonthValue() && now.getDayOfMonth() < birthDate.getDayOfMonth())) {
            age--;
        }

        return age;
    }

    /**
     * Verifie si une date de naissance correspond a un age valide (>= 18 ans).
     */
    public boolean isAgeValid(LocalDateTime birthDate) {
        return validate(birthDate).isEmpty();
    }
}
