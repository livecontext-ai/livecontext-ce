package com.apimarketplace.auth.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "users")
public class User implements UserDetails {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Size(max = 50)
    @Column(unique = true, nullable = true)
    private String username;

    @Size(max = 100)
    @Email
    @Column(unique = true, nullable = true)
    private String email;

    @Size(max = 100)
    private String firstName;

    @Size(max = 100)
    private String lastName;

    @Size(max = 255)
    private String avatarUrl;

    @Enumerated(EnumType.STRING)
    private AuthProvider authProvider;

    @Column(name = "provider_id")
    private String providerId;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "role")
    private Set<String> roles = new HashSet<>();

    private boolean enabled = true;
    private boolean emailVerified = false;

    @Column(name = "deactivated_at")
    private LocalDateTime deactivatedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    /**
     * PR11d-b - timestamp of the most-recent setDefaultOrganization flip.
     * Used by OrganizationController to rate-limit rapid workspace flips
     * that could bypass per-member quota caps (audit B 2026-05-12).
     * NULL for users who never switched workspaces.
     */
    @Column(name = "last_default_flip_at")
    private LocalDateTime lastDefaultFlipAt;

    @Column(name = "user_version")
    private Long userVersion = 1L;

    // API Key fields (hash-based, V12/V15)
    @Column(name = "api_key_hash", length = 64)
    private String apiKeyHash;

    @Column(name = "api_key_hint", length = 10)
    private String apiKeyHint;

    @Column(name = "api_key_created_at")
    private LocalDateTime apiKeyCreatedAt;

    // Password hash for local email+password auth (CE embedded mode)
    @Column(name = "password_hash")
    private String passwordHash;

    // Champs complementaires
    @Column(name = "age")
    private LocalDateTime age;

    // Organization memberships (lazy loaded)
    @OneToMany(mappedBy = "user", fetch = FetchType.LAZY)
    private Set<OrganizationMember> memberships = new HashSet<>();

    // Constructeurs
    public User() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public User(String username, String email, AuthProvider authProvider, String providerId) {
        this();
        this.username = username;
        this.email = email;
        this.authProvider = authProvider;
        this.providerId = providerId;
        this.roles = new java.util.HashSet<>(Set.of("USER"));
    }

    // Methodes UserDetails
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return roles.stream()
                    .map(role -> new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()))
                    .toList();
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    // Getters et Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public void setAvatarUrl(String avatarUrl) {
        this.avatarUrl = avatarUrl;
    }

    public AuthProvider getAuthProvider() {
        return authProvider;
    }

    public void setAuthProvider(AuthProvider authProvider) {
        this.authProvider = authProvider;
    }

    public String getProviderId() {
        return providerId;
    }

    public void setProviderId(String providerId) {
        this.providerId = providerId;
    }

    public Set<String> getRoles() {
        return roles;
    }

    public void setRoles(Set<String> roles) {
        this.roles = roles != null ? new HashSet<>(roles) : new HashSet<>();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEmailVerified() {
        return emailVerified;
    }

    public void setEmailVerified(boolean emailVerified) {
        this.emailVerified = emailVerified;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public LocalDateTime getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(LocalDateTime lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
    }

    public LocalDateTime getLastDefaultFlipAt() {
        return lastDefaultFlipAt;
    }

    public void setLastDefaultFlipAt(LocalDateTime lastDefaultFlipAt) {
        this.lastDefaultFlipAt = lastDefaultFlipAt;
    }

    public Long getUserVersion() {
        return userVersion;
    }

    public void setUserVersion(Long userVersion) {
        this.userVersion = userVersion;
    }

    public LocalDateTime getAge() {
        return age;
    }

    public void setAge(LocalDateTime age) {
        this.age = age;
    }

    public String getApiKeyHash() {
        return apiKeyHash;
    }

    public void setApiKeyHash(String apiKeyHash) {
        this.apiKeyHash = apiKeyHash;
    }

    public String getApiKeyHint() {
        return apiKeyHint;
    }

    public void setApiKeyHint(String apiKeyHint) {
        this.apiKeyHint = apiKeyHint;
    }

    public LocalDateTime getApiKeyCreatedAt() {
        return apiKeyCreatedAt;
    }

    public void setApiKeyCreatedAt(LocalDateTime apiKeyCreatedAt) {
        this.apiKeyCreatedAt = apiKeyCreatedAt;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public LocalDateTime getDeactivatedAt() {
        return deactivatedAt;
    }

    public void setDeactivatedAt(LocalDateTime deactivatedAt) {
        this.deactivatedAt = deactivatedAt;
    }

    public Set<OrganizationMember> getMemberships() {
        return memberships;
    }

    public void setMemberships(Set<OrganizationMember> memberships) {
        this.memberships = memberships;
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
