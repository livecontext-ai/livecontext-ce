package com.apimarketplace.auth.dto;

import java.time.LocalDateTime;

/**
 * DTO pour le profil utilisateur
 */
public class UserProfile {
    private Long id;
    private String username;
    private String displayName;
    private String email;
    private String firstName;
    private String lastName;
    private String avatarUrl;
    private String authProvider;
    private boolean emailVerified;
    private boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime lastLoginAt;
    private LocalDateTime age;
    private LocalDateTime displayNameChangedAt;

    // Profile presentation fields (from user_profiles). Surfaced here so the account
    // settings page reads/writes them through the existing /api/users/profile.
    private String handle;
    private String bio;
    // UNLISTED, matching UserProfileEntity's default. mapToUserProfile always
    // overwrites this from the entity, so the value is not reachable today, but
    // "PUBLIC" here would mean any future path that forgets that setter presents
    // the user as search-indexable. The safe state is the safe default.
    private String profileVisibility = "UNLISTED";

    // Display preferences (auth.users, V527/V540). Written through
    // PUT /api/users/profile/context, read back here so the app can format every date in the
    // person's zone and language on ANY device - the browser cookie only ever knew the last
    // device they used. `explicit` distinguishes a deliberate pick (pinned) from what the
    // browser merely reported (last wins), which is what the Settings selects display.
    private String locale;
    private boolean localeExplicit;
    private String timeZone;
    private boolean timeZoneExplicit;

    // Constructeur par defaut
    public UserProfile() {}

    // Constructeur avec parametres
    public UserProfile(Long id, String username, String displayName, String email, String firstName, String lastName,
                      String avatarUrl, String authProvider, boolean emailVerified, boolean enabled,
                      LocalDateTime createdAt, LocalDateTime lastLoginAt, LocalDateTime age,
                      LocalDateTime displayNameChangedAt) {
        this.id = id;
        this.username = username;
        this.displayName = displayName;
        this.email = email;
        this.firstName = firstName;
        this.lastName = lastName;
        this.avatarUrl = avatarUrl;
        this.authProvider = authProvider;
        this.emailVerified = emailVerified;
        this.enabled = enabled;
        this.createdAt = createdAt;
        this.lastLoginAt = lastLoginAt;
        this.age = age;
        this.displayNameChangedAt = displayNameChangedAt;
    }

    // Getters et Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    
    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }
    
    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }
    
    public String getAvatarUrl() { return avatarUrl; }
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }
    
    public String getAuthProvider() { return authProvider; }
    public void setAuthProvider(String authProvider) { this.authProvider = authProvider; }
    
    public boolean isEmailVerified() { return emailVerified; }
    public void setEmailVerified(boolean emailVerified) { this.emailVerified = emailVerified; }
    
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    
    public LocalDateTime getLastLoginAt() { return lastLoginAt; }
    public void setLastLoginAt(LocalDateTime lastLoginAt) { this.lastLoginAt = lastLoginAt; }

    public LocalDateTime getAge() { return age; }
    public void setAge(LocalDateTime age) { this.age = age; }

    public LocalDateTime getDisplayNameChangedAt() { return displayNameChangedAt; }
    public void setDisplayNameChangedAt(LocalDateTime displayNameChangedAt) { this.displayNameChangedAt = displayNameChangedAt; }

    public String getHandle() { return handle; }
    public void setHandle(String handle) { this.handle = handle; }

    public String getBio() { return bio; }
    public void setBio(String bio) { this.bio = bio; }

    public String getProfileVisibility() { return profileVisibility; }
    public void setProfileVisibility(String profileVisibility) { this.profileVisibility = profileVisibility; }

    public String getLocale() { return locale; }
    public void setLocale(String locale) { this.locale = locale; }

    public boolean isLocaleExplicit() { return localeExplicit; }
    public void setLocaleExplicit(boolean localeExplicit) { this.localeExplicit = localeExplicit; }

    public String getTimeZone() { return timeZone; }
    public void setTimeZone(String timeZone) { this.timeZone = timeZone; }

    public boolean isTimeZoneExplicit() { return timeZoneExplicit; }
    public void setTimeZoneExplicit(boolean timeZoneExplicit) { this.timeZoneExplicit = timeZoneExplicit; }

    @Override
    public String toString() {
        return "UserProfile{" +
                "id=" + id +
                ", username='" + username + '\'' +
                ", displayName='" + displayName + '\'' +
                ", email='" + email + '\'' +
                ", firstName='" + firstName + '\'' +
                ", lastName='" + lastName + '\'' +
                ", authProvider='" + authProvider + '\'' +
                ", emailVerified=" + emailVerified +
                ", enabled=" + enabled +
                '}';
    }
}
