/**
 * OIDC User profile type (standard OIDC claims + Keycloak extensions)
 */
interface OidcUser {
  sub?: string;
  name?: string;
  preferred_username?: string;
  nickname?: string;
  email?: string;
  picture?: string;
  avatar?: string;
  given_name?: string;
  family_name?: string;
  email_verified?: boolean;
  identity_provider?: string;
  [key: string]: any;
}

/**
 * Check if a username is a Keycloak auto-generated ID (e.g. "kc_2d4cc1b2")
 */
const isKeycloakGeneratedUsername = (username: string): boolean => {
  return /^kc_[0-9a-f]+$/i.test(username);
};

/**
 * Gets the user display name with fallback.
 * Priority: name > email > nickname > preferred_username (if human-readable) > sub
 */
export const getDisplayName = (user: OidcUser | undefined): string => {
  if (!user) return 'User';

  // Priority 1: Full name (populated by Google/GitHub, empty for Email/Password)
  if (user.name && user.name.trim()) return user.name;

  // Priority 2: Email (reliable for all auth types)
  if (user.email && user.email.trim()) return user.email;

  // Priority 3: Nickname (GitHub username, etc.)
  if (user.nickname && user.nickname.trim()) return user.nickname;

  // Priority 4: Preferred username (skip Keycloak auto-generated IDs like "kc_2d4cc1b2")
  if (user.preferred_username && user.preferred_username.trim() &&
      !isKeycloakGeneratedUsername(user.preferred_username.trim())) {
    return user.preferred_username;
  }

  // Priority 5: Sub (unique ID)
  if (user.sub) return user.sub.substring(0, 8);

  return 'User';
};

/**
 * Gets the profile image with fallback
 */
export const getProfileImage = (user: OidcUser | undefined): string | null => {
  if (!user) return null;

  // Priority 1: Picture (profile image)
  if (user.picture && user.picture.trim()) return user.picture;

  // Priority 2: Avatar (alternative)
  if (user.avatar && user.avatar.trim()) return user.avatar;

  return null;
};

/**
 * True when the account authenticates through an external identity provider -
 * social login (Google/GitHub/Microsoft/Facebook/LinkedIn…) OR an org SAML/SSO
 * connection (`org-<id>-saml`). Local-password accounts - direct Keycloak
 * username/password and CE embedded auth - carry NO `identity_provider` claim.
 *
 * Used to hide in-app password management (the Security tab), which only makes
 * sense for local-password accounts; federated accounts manage their password
 * at the upstream provider, not here.
 */
export const isFederatedAccount = (user: OidcUser | undefined): boolean => {
  const idp = user?.identity_provider;
  return typeof idp === 'string' && idp.trim().length > 0;
};

/**
 * Gets identity provider information
 */
export const getIdentityProvider = (user: OidcUser | undefined): string | null => {
  if (!user) return null;

  // Use identity_provider claim from Keycloak JWT
  if (user.identity_provider) {
    switch (user.identity_provider) {
      case 'google': return 'Google';
      case 'github': return 'GitHub';
      case 'facebook': return 'Facebook';
      case 'linkedin': return 'LinkedIn';
      default: return user.identity_provider;
    }
  }

  return 'Keycloak';
};
