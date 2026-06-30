package com.apimarketplace.auth.web;

import com.stripe.exception.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.HashMap;
import java.util.Map;

/**
 * Centralized exception handler for Stripe API errors.
 * 
 * Provides consistent error responses across all billing-related endpoints
 * following Stripe and REST best practices.
 * 
 * @see <a href="https://stripe.com/docs/error-handling">Stripe Error Handling</a>
 */
@ControllerAdvice
public class StripeExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(StripeExceptionHandler.class);

    /**
     * Handles card-related errors (declined, insufficient funds, etc.)
     */
    @ExceptionHandler(CardException.class)
    public ResponseEntity<Map<String, Object>> handleCardException(CardException e) {
        logger.warn("Card error: {} - {}", e.getCode(), e.getMessage());
        
        Map<String, Object> response = new HashMap<>();
        response.put("error", "payment_failed");
        response.put("code", e.getCode());
        response.put("message", getCardErrorMessage(e.getCode()));
        response.put("declineCode", e.getDeclineCode());
        
        return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED).body(response);
    }

    /**
     * Handles rate limit errors (429)
     */
    @ExceptionHandler(RateLimitException.class)
    public ResponseEntity<Map<String, Object>> handleRateLimitException(RateLimitException e) {
        logger.warn("Stripe rate limit exceeded: {}", e.getMessage());
        
        Map<String, Object> response = new HashMap<>();
        response.put("error", "rate_limit_exceeded");
        response.put("message", "Trop de requêtes. Veuillez réessayer dans quelques instants.");
        response.put("retryAfter", 60);
        
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(response);
    }

    /**
     * Handles invalid request errors (bad parameters, missing fields, etc.)
     */
    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidRequestException(InvalidRequestException e) {
        logger.warn("Stripe invalid request: {} - {}", e.getCode(), e.getMessage());
        
        Map<String, Object> response = new HashMap<>();
        String errorCode = e.getCode();
        
        // Handle specific error codes
        if ("resource_missing".equals(errorCode)) {
            response.put("error", "not_found");
            response.put("message", "La ressource demandée n'existe pas.");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
        }
        
        if (e.getMessage() != null && e.getMessage().contains("No such customer")) {
            response.put("error", "customer_not_found");
            response.put("message", "Le client n'existe pas. Veuillez créer un nouveau compte.");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
        }
        
        if (e.getMessage() != null && e.getMessage().contains("No such subscription")) {
            response.put("error", "subscription_not_found");
            response.put("message", "L'abonnement n'existe pas ou a été annulé.");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
        }
        
        // Generic invalid request
        response.put("error", "invalid_request");
        response.put("code", errorCode);
        response.put("message", e.getMessage());
        
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    /**
     * Handles authentication errors (invalid API key)
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Map<String, Object>> handleAuthenticationException(AuthenticationException e) {
        logger.error("Stripe authentication error - check API key configuration: {}", e.getMessage());
        
        Map<String, Object> response = new HashMap<>();
        response.put("error", "configuration_error");
        response.put("message", "Erreur de configuration du service de paiement. Contactez le support.");
        
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }

    /**
     * Handles API connection errors (network issues with Stripe)
     */
    @ExceptionHandler(ApiConnectionException.class)
    public ResponseEntity<Map<String, Object>> handleApiConnectionException(ApiConnectionException e) {
        logger.error("Cannot connect to Stripe API: {}", e.getMessage());
        
        Map<String, Object> response = new HashMap<>();
        response.put("error", "payment_service_unavailable");
        response.put("message", "Le service de paiement est temporairement indisponible. Réessayez dans quelques instants.");
        response.put("retryable", true);
        
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
    }

    /**
     * Handles webhook signature verification errors
     */
    @ExceptionHandler(SignatureVerificationException.class)
    public ResponseEntity<Map<String, Object>> handleSignatureVerificationException(SignatureVerificationException e) {
        logger.warn("Invalid webhook signature: {}", e.getMessage());
        
        Map<String, Object> response = new HashMap<>();
        response.put("error", "invalid_signature");
        response.put("message", "Signature de webhook invalide.");
        
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    /**
     * Handles generic Stripe API errors
     */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApiException(ApiException e) {
        logger.error("Stripe API error: {} - {}", e.getCode(), e.getMessage(), e);
        
        Map<String, Object> response = new HashMap<>();
        response.put("error", "payment_error");
        response.put("code", e.getCode());
        response.put("message", "Une erreur s'est produite lors du traitement du paiement.");
        
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }

    /**
     * Catch-all for any StripeException not handled above
     */
    @ExceptionHandler(StripeException.class)
    public ResponseEntity<Map<String, Object>> handleStripeException(StripeException e) {
        logger.error("Unexpected Stripe error: {}", e.getMessage(), e);
        
        Map<String, Object> response = new HashMap<>();
        response.put("error", "stripe_error");
        response.put("message", "Une erreur inattendue s'est produite. Veuillez réessayer.");
        
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }

    // ==================== HELPER METHODS ====================

    /**
     * Returns user-friendly message for common card decline codes.
     */
    private String getCardErrorMessage(String code) {
        if (code == null) {
            return "Le paiement a été refusé.";
        }
        
        return switch (code) {
            case "card_declined" -> "Votre carte a été refusée. Veuillez utiliser un autre moyen de paiement.";
            case "insufficient_funds" -> "Fonds insuffisants. Veuillez utiliser une autre carte.";
            case "expired_card" -> "Votre carte a expiré. Veuillez utiliser une carte valide.";
            case "incorrect_cvc" -> "Le code CVC est incorrect. Veuillez vérifier et réessayer.";
            case "incorrect_number" -> "Le numéro de carte est incorrect.";
            case "processing_error" -> "Erreur de traitement. Veuillez réessayer dans quelques instants.";
            case "lost_card", "stolen_card" -> "Cette carte ne peut pas être utilisée. Contactez votre banque.";
            case "do_not_honor" -> "Paiement refusé par votre banque. Contactez-les pour plus d'informations.";
            default -> "Le paiement a été refusé. Veuillez utiliser un autre moyen de paiement.";
        };
    }
}
