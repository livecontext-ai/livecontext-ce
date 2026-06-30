package com.apimarketplace.auth.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

/**
 * DTO pour la mise a jour du profil utilisateur
 */
public class UserProfileUpdateRequest {
    @Size(min = 3, max = 30, message = "Display name must be between 3 and 30 characters")
    private String displayName;

    private String firstName;
    private String lastName;
    private String avatarUrl;
    @Size(min = 3, max = 20, message = "Username must be between 3 and 20 characters")
    @Pattern(regexp = "^[a-zA-Z0-9_-]+$", message = "Username can only contain letters, numbers, hyphens, and underscores")
    private String username;
    
    private LocalDateTime age;

    // OIDC supplementary data
    private String email;
    private String locale;
    private String timezone;
    private String picture;
    private String nickname;
    private String givenName;
    private String familyName;
    private Boolean emailVerified;

    // Profile fields. Size-bounded here; handle format/uniqueness + visibility validation
    // happen in UserService (defensive, never throws → no 500 on odd input).
    @Size(max = 32, message = "Handle must be at most 32 characters")
    private String handle;
    @Size(max = 500, message = "Bio must be at most 500 characters")
    private String bio;
    private String profileVisibility;

    // Constructeur par defaut
    public UserProfileUpdateRequest() {}

    // Constructeur avec parametres
    public UserProfileUpdateRequest(String firstName, String lastName, String avatarUrl, String username, LocalDateTime age) {
        this.firstName = firstName;
        this.lastName = lastName;
        this.avatarUrl = avatarUrl;
        this.username = username;
        this.age = age;
    }

    // Getters et Setters
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) {
        this.displayName = displayName == null ? null : displayName.trim();
    }

    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }

    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }

    public String getAvatarUrl() { return avatarUrl; }
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public LocalDateTime getAge() { return age; }
    public void setAge(LocalDateTime age) { this.age = age; }

    // Getters and Setters for OIDC data
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getLocale() { return locale; }
    public void setLocale(String locale) { this.locale = locale; }

    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }

    public String getPicture() { return picture; }
    public void setPicture(String picture) { this.picture = picture; }

    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }

    public String getGivenName() { return givenName; }
    public void setGivenName(String givenName) { this.givenName = givenName; }

    public String getFamilyName() { return familyName; }
    public void setFamilyName(String familyName) { this.familyName = familyName; }

    public Boolean getEmailVerified() { return emailVerified; }
    public void setEmailVerified(Boolean emailVerified) { this.emailVerified = emailVerified; }

    public String getHandle() { return handle; }
    public void setHandle(String handle) { this.handle = handle; }

    public String getBio() { return bio; }
    public void setBio(String bio) { this.bio = bio; }

    public String getProfileVisibility() { return profileVisibility; }
    public void setProfileVisibility(String profileVisibility) { this.profileVisibility = profileVisibility; }

    @Override
    public String toString() {
        return "UserProfileUpdateRequest{" +
               "firstName='" + firstName + '\'' +
               ", lastName='" + lastName + '\'' +
               ", avatarUrl='" + avatarUrl + '\'' +
                ", username='" + username + '\'' +
                ", age=" + age +
               ", email='" + email + '\'' +
               ", locale='" + locale + '\'' +
               ", timezone='" + timezone + '\'' +
               ", picture='" + picture + '\'' +
               ", nickname='" + nickname + '\'' +
               ", givenName='" + givenName + '\'' +
               ", familyName='" + familyName + '\'' +
               ", emailVerified=" + emailVerified +
               '}';
    }
}
