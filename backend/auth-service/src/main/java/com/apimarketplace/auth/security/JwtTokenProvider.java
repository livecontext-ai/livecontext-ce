package com.apimarketplace.auth.security;

import com.apimarketplace.auth.domain.User;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.security.Key;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * JWT token provider supporting dual-mode signing:
 * - HMAC HS512 (EE mode, Keycloak): uses jwt.secret
 * - RSA RS256 (CE mode, embedded auth): uses JwtKeyPairManager RSA keys
 *
 * Mode is determined by presence of JwtKeyPairManager bean (only active when auth.mode=embedded).
 */
@Component
public class JwtTokenProvider {

    private static final Logger logger = LoggerFactory.getLogger(JwtTokenProvider.class);

    @Value("${jwt.secret:}")
    private String jwtSecret;

    @Value("${jwt.access-token.expiration:900000}")
    private long accessTokenExpirationMs; // 15 min default for CE

    @Value("${jwt.refresh-token.expiration:2592000000}")
    private long refreshTokenExpirationMs; // 30 days default for CE

    @Value("${auth.jwt.issuer:livecontext}")
    private String issuer;

    @Autowired(required = false)
    private JwtKeyPairManager keyPairManager;

    private boolean isRsaMode() {
        return keyPairManager != null;
    }

    private Key getSigningKey() {
        if (isRsaMode()) {
            return keyPairManager.getPrivateKey();
        }
        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalStateException("jwt.secret must be configured when RSA key-pair auth is disabled");
        }
        return Keys.hmacShaKeyFor(jwtSecret.getBytes());
    }

    private SignatureAlgorithm getAlgorithm() {
        return isRsaMode() ? SignatureAlgorithm.RS256 : SignatureAlgorithm.HS512;
    }

    public String generateAccessToken(User user) {
        return generateAccessToken(user, null);
    }

    public String generateAccessToken(User user, OrganizationClaims organizationClaims) {
        return generateToken(user, accessTokenExpirationMs, "access", organizationClaims);
    }

    public String generateRefreshToken(User user) {
        return generateToken(user, refreshTokenExpirationMs, "refresh", null);
    }

    private String generateToken(User user, long expirationMs, String tokenType, OrganizationClaims organizationClaims) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", user.getId());
        claims.put("email", user.getEmail());
        if (user.getAuthProvider() != null) {
            claims.put("provider", user.getAuthProvider().getProvider());
        }
        claims.put("token_type", tokenType);
        if (user.getRoles() != null && !user.getRoles().isEmpty()) {
            claims.put("roles", user.getRoles());
        }
        if ("access".equals(tokenType) && organizationClaims != null) {
            addOrganizationClaims(claims, organizationClaims);
        }

        var builder = Jwts.builder()
                .setClaims(claims)
                .setSubject(subjectFor(user))
                .setIssuer(issuer)
                .setIssuedAt(new Date(System.currentTimeMillis()))
                .setExpiration(new Date(System.currentTimeMillis() + expirationMs))
                .signWith(getSigningKey(), getAlgorithm());

        // Add key ID header for RSA mode (needed for JWKS validation)
        if (isRsaMode()) {
            builder.setHeaderParam("kid", keyPairManager.getKeyId());
        }

        return builder.compact();
    }

    private void addOrganizationClaims(Map<String, Object> claims, OrganizationClaims organizationClaims) {
        if (organizationClaims.defaultOrganizationId() != null
                && !organizationClaims.defaultOrganizationId().isBlank()) {
            claims.put("defaultOrganizationId", organizationClaims.defaultOrganizationId());
        }
        if (organizationClaims.defaultOrganizationRole() != null
                && !organizationClaims.defaultOrganizationRole().isBlank()) {
            claims.put("defaultOrganizationRole", organizationClaims.defaultOrganizationRole());
        }
        if (organizationClaims.memberships() != null && !organizationClaims.memberships().isEmpty()) {
            claims.put("memberships", organizationClaims.memberships().stream()
                    .filter(m -> m.orgId() != null && !m.orgId().isBlank())
                    .map(m -> {
                        Map<String, Object> membership = new HashMap<>();
                        membership.put("orgId", m.orgId());
                        if (m.role() != null && !m.role().isBlank()) {
                            membership.put("role", m.role());
                        }
                        membership.put("personal", m.personal());
                        membership.put("paused", m.paused());
                        return membership;
                    })
                    .toList());
        }
    }

    private String subjectFor(User user) {
        String providerId = user.getProviderId();
        if (providerId != null && !providerId.isBlank()) {
            return providerId;
        }
        return String.valueOf(user.getId());
    }

    public String getUsernameFromToken(String token) {
        return getClaimFromToken(token, Claims::getSubject);
    }

    public String getUsernameFromRefreshToken(String token) {
        return getClaimFromToken(token, Claims::getSubject);
    }

    public Date getExpirationDateFromToken(String token) {
        return getClaimFromToken(token, Claims::getExpiration);
    }

    public <T> T getClaimFromToken(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = getAllClaimsFromToken(token);
        return claimsResolver.apply(claims);
    }

    private Claims getAllClaimsFromToken(String token) {
        Key verificationKey;
        if (isRsaMode()) {
            verificationKey = keyPairManager.getPublicKey();
        } else {
            if (jwtSecret == null || jwtSecret.isBlank()) {
                throw new IllegalStateException("jwt.secret must be configured when RSA key-pair auth is disabled");
            }
            verificationKey = Keys.hmacShaKeyFor(jwtSecret.getBytes());
        }

        return Jwts.parser()
                .setSigningKey(verificationKey)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    private Boolean isTokenExpired(String token) {
        final Date expiration = getExpirationDateFromToken(token);
        return expiration.before(new Date());
    }

    public Boolean validateToken(String token, org.springframework.security.core.userdetails.UserDetails userDetails) {
        final String username = getUsernameFromToken(token);
        if (userDetails instanceof User user) {
            Long tokenUserId = getUserIdFromToken(token);
            return tokenUserId != null && tokenUserId.equals(user.getId()) && !isTokenExpired(token);
        }
        return (username.equals(userDetails.getUsername()) && !isTokenExpired(token));
    }

    public Boolean validateRefreshToken(String token) {
        try {
            return !isTokenExpired(token);
        } catch (Exception e) {
            return false;
        }
    }

    public Boolean validateAccessToken(String token) {
        try {
            Claims claims = getAllClaimsFromToken(token);
            return !claims.getExpiration().before(new Date());
        } catch (Exception e) {
            return false;
        }
    }

    public Long getUserIdFromToken(String token) {
        Claims claims = getAllClaimsFromToken(token);
        Object userId = claims.get("userId");
        if (userId instanceof Number n) {
            return n.longValue();
        }
        // Fallback for legacy embedded tokens whose subject was the numeric user ID.
        try {
            return Long.parseLong(claims.getSubject());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public String getProviderFromToken(String token) {
        Claims claims = getAllClaimsFromToken(token);
        return claims.get("provider", String.class);
    }

    public long getAccessTokenExpirationMs() {
        return accessTokenExpirationMs;
    }

    public long getRefreshTokenExpirationMs() {
        return refreshTokenExpirationMs;
    }

    public record OrganizationClaims(
            String defaultOrganizationId,
            String defaultOrganizationRole,
            List<OrganizationMembershipClaim> memberships) {}

    public record OrganizationMembershipClaim(String orgId, String role, boolean personal, boolean paused) {}
}
