package com.apimarketplace.publication.utils;

/**
 * Single rule for which agent {@code avatarUrl} values may cross tenant boundaries
 * (publication snapshots, acquire-time clones, moderation views).
 *
 * <p>Publishable values render for ANY viewer via a plain {@code <img>}:
 * <ul>
 *   <li>{@code preset:*} - static frontend assets (including the customized
 *       {@code preset:x?c1=..&c2=..} color form, recolored client-side);</li>
 *   <li>{@code http(s)://...} - external images;</li>
 *   <li>{@code /api/proxy/files/avatar/{id}} - the anonymous avatar serve (uploaded and
 *       AI-generated avatars land here via the generic {@code avatar} upload category).</li>
 * </ul>
 * Everything else (notably the auth-gated {@code /api/proxy/files/by-id/...} form) is
 * dropped: it would 401 for any viewer other than the uploader and must not leak.
 */
public final class AvatarUrlPolicy {

    /** Path prefix of the anonymous avatar serve (see storage-service FileController.avatarById). */
    public static final String PUBLIC_AVATAR_PATH = "/api/proxy/files/avatar/";

    private AvatarUrlPolicy() {
    }

    /** @return the value unchanged when publishable, else {@code null}. */
    public static String publishable(String avatarUrl) {
        if (avatarUrl == null) {
            return null;
        }
        if (avatarUrl.startsWith("preset:")
                || avatarUrl.startsWith("http")
                || avatarUrl.startsWith(PUBLIC_AVATAR_PATH)) {
            return avatarUrl;
        }
        return null;
    }
}
