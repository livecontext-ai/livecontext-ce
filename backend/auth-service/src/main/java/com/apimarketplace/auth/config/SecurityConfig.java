package com.apimarketplace.auth.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {


    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable) // CSRF off globalement
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authz -> authz
                                               .requestMatchers("/api/auth/health", "/public/**").permitAll()
                                               .requestMatchers("/api/auth/register", "/api/auth/login",
                                                       "/api/auth/refresh", "/api/auth/logout",
                                                       "/api/auth/openid-configuration").permitAll() // Embedded auth (CE)
                                               .requestMatchers("/api/users/health").permitAll() // Sante du service seulement
                                               .requestMatchers("/webhooks/**").permitAll() // <-- match le contrôleur corrige
                                               .requestMatchers("/api/billing/plans").permitAll() // Plans publics
                                               .requestMatchers("/.well-known/jwks.json").permitAll() // JWKS endpoint
                                               .requestMatchers("/v1/authorize").permitAll() // Authorization endpoint
                                               .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                                               .anyRequest().permitAll() // Toutes les requetes sont autorisees (le gateway valide deja)
                                      );
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(List.of("*")); // Allow all origins (pattern-based, compatible with allowCredentials)
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
