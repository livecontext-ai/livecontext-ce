package com.apimarketplace.auth.web;

import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.service.OnboardingService;
import com.apimarketplace.auth.service.UserService;
import com.apimarketplace.common.storage.domain.StorageEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Avatar endpoint behavior - focused on the NULL-avatar generated-SVG fallback.
 * Existing avatar resolution (uploaded photo bytes from storage.storage) covered
 * elsewhere; this class pins the new fallback shape.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserController.getAvatar - initials-SVG fallback")
class UserControllerAvatarFallbackTest {

    @Mock UserService userService;
    @Mock OnboardingService onboardingService;

    UserController controller;

    @BeforeEach
    void setUp() {
        controller = new UserController();
        // Field injection via reflection - the controller uses @Autowired for these
        // and we want to avoid a Spring context for this micro-test.
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "userService", userService);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "onboardingService", onboardingService);
    }

    @Test
    @DisplayName("Missing user → still 200 SVG (no enumeration oracle), \"?\" initials")
    void noEnumerationWhenUserMissing() {
        when(userService.getAvatarEntity(42L)).thenReturn(Optional.empty());
        when(userService.findById(42L)).thenReturn(Optional.empty());

        ResponseEntity<byte[]> response = controller.getAvatar(42L, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.parseMediaType("image/svg+xml"));
        String svg = new String(response.getBody(), StandardCharsets.UTF_8);
        assertThat(svg).contains(">?</text>");
    }

    @Test
    @DisplayName("ETag changes when user's name changes (rename invalidates cache)")
    void etagDiffersAfterRename() {
        User v1 = new User();
        v1.setId(7L);
        v1.setFirstName("Sarah");
        v1.setLastName("Lambert");
        v1.setEmail("sarah@example.com");

        when(userService.getAvatarEntity(7L)).thenReturn(Optional.empty());
        when(userService.findById(7L)).thenReturn(Optional.of(v1));
        String etagBefore = controller.getAvatar(7L, null).getHeaders().getETag();

        v1.setLastName("Toulouse");
        String etagAfter = controller.getAvatar(7L, null).getHeaders().getETag();

        assertThat(etagBefore).isNotBlank().isNotEqualTo(etagAfter);
    }

    @Test
    @DisplayName("If-None-Match matching ETag → 304 Not Modified, empty body")
    void respectsIfNoneMatch() {
        User u = new User();
        u.setId(9L);
        u.setFirstName("Léa");
        u.setLastName("Tremblay");
        u.setEmail("lea@example.com");
        when(userService.getAvatarEntity(9L)).thenReturn(Optional.empty());
        when(userService.findById(9L)).thenReturn(Optional.of(u));

        String currentEtag = controller.getAvatar(9L, null).getHeaders().getETag();
        ResponseEntity<byte[]> conditional = controller.getAvatar(9L, currentEtag);

        assertThat(conditional.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(conditional.getBody()).isNull();
    }

    @Test
    @DisplayName("User with null avatar_url → SVG fallback (200, image/svg+xml, contains initials)")
    void fallbackGeneratesSvgForNullAvatar() {
        User u = new User();
        u.setId(7L);
        u.setFirstName("Sarah");
        u.setLastName("Lambert");
        u.setEmail("sarah@example.com");

        when(userService.getAvatarEntity(7L)).thenReturn(Optional.empty());
        when(userService.findById(7L)).thenReturn(Optional.of(u));

        ResponseEntity<byte[]> response = controller.getAvatar(7L, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.parseMediaType("image/svg+xml"));
        // Revalidate via ETag, never pin for a day (see the CacheRevalidation nested class).
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-cache");
        String svg = new String(response.getBody(), StandardCharsets.UTF_8);
        assertThat(svg).contains(">SL</text>");
        assertThat(svg).startsWith("<svg");
    }

    @Test
    @DisplayName("StorageEntity present but data_binary null → SVG fallback (not 404)")
    void fallbackWhenStorageRowHasNullBinary() {
        User u = new User();
        u.setId(8L);
        u.setFirstName("Marc");
        u.setLastName("Dubois");
        u.setEmail("marc@example.com");

        StorageEntity empty = new StorageEntity();
        // dataBinary stays null by default
        when(userService.getAvatarEntity(8L)).thenReturn(Optional.of(empty));
        when(userService.findById(8L)).thenReturn(Optional.of(u));

        ResponseEntity<byte[]> response = controller.getAvatar(8L, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(new String(response.getBody(), StandardCharsets.UTF_8)).contains(">MD</text>");
    }

    @Nested
    @DisplayName("Existing-avatar path unchanged")
    class ExistingAvatarPath {
        @Test
        @DisplayName("StorageEntity with binary bytes → returns those bytes with stored MIME")
        void returnsUploadedAvatarBytes() {
            byte[] png = new byte[] { (byte) 0x89, 0x50, 0x4E, 0x47, 1, 2, 3, 4 };
            StorageEntity entity = new StorageEntity();
            entity.setDataBinary(png);
            entity.setMimeType("image/png");
            when(userService.getAvatarEntity(1L)).thenReturn(Optional.of(entity));

            ResponseEntity<byte[]> response = controller.getAvatar(1L, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
            assertThat(response.getBody()).isEqualTo(png);
        }
    }

    /**
     * Regression: the avatar is a MUTABLE resource on a stable URL. A bare
     * {@code Cache-Control: max-age=86400} let the browser pin a stale copy for a day
     * (it never sent the conditional request, so the ETag never busted it) - a freshly
     * uploaded photo kept showing the old initials on every URL already cached. The fix
     * is {@code no-cache}: cacheable but always revalidated via the ETag (cheap 304 when
     * unchanged), so a changed avatar propagates immediately.
     */
    @Nested
    @DisplayName("Cache-Control forces ETag revalidation (no day-long pin)")
    class CacheRevalidation {

        @Test
        @DisplayName("Uploaded photo → no-cache, NOT max-age=86400 (the stale-for-a-day bug)")
        void uploadedPhotoRevalidates() {
            byte[] jpeg = new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3, 4 };
            StorageEntity entity = new StorageEntity();
            entity.setDataBinary(jpeg);
            entity.setMimeType("image/jpeg");
            when(userService.getAvatarEntity(5L)).thenReturn(Optional.of(entity));

            ResponseEntity<byte[]> response = controller.getAvatar(5L, null);

            assertThat(response.getHeaders().getCacheControl())
                    .isEqualTo("no-cache")
                    .doesNotContain("max-age=86400");
        }

        @Test
        @DisplayName("Initials SVG fallback → no-cache, NOT max-age=86400")
        void initialsSvgRevalidates() {
            User u = new User();
            u.setId(6L);
            u.setFirstName("Sarah");
            u.setLastName("Lambert");
            u.setEmail("sarah@example.com");
            when(userService.getAvatarEntity(6L)).thenReturn(Optional.empty());
            when(userService.findById(6L)).thenReturn(Optional.of(u));

            ResponseEntity<byte[]> response = controller.getAvatar(6L, null);

            assertThat(response.getHeaders().getCacheControl())
                    .isEqualTo("no-cache")
                    .doesNotContain("max-age=86400");
        }

        @Test
        @DisplayName("304 Not Modified still carries the revalidation directive")
        void notModifiedCarriesRevalidationDirective() {
            User u = new User();
            u.setId(11L);
            u.setFirstName("Léa");
            u.setLastName("Tremblay");
            u.setEmail("lea@example.com");
            when(userService.getAvatarEntity(11L)).thenReturn(Optional.empty());
            when(userService.findById(11L)).thenReturn(Optional.of(u));

            String currentEtag = controller.getAvatar(11L, null).getHeaders().getETag();
            ResponseEntity<byte[]> conditional = controller.getAvatar(11L, currentEtag);

            assertThat(conditional.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
            assertThat(conditional.getHeaders().getCacheControl()).isEqualTo("no-cache");
        }

        @Test
        @DisplayName("Uploaded photo + matching If-None-Match → 304 with ETag + no-cache (the exact prod-broken scenario)")
        void uploadedPhoto304KeepsEtagAndRevalidates() {
            byte[] jpeg = new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 9, 8, 7, 6 };
            StorageEntity entity = new StorageEntity();
            entity.setDataBinary(jpeg);
            entity.setMimeType("image/jpeg");
            // Entity must resolve for BOTH the initial fetch and the conditional one.
            when(userService.getAvatarEntity(12L)).thenReturn(Optional.of(entity));
            when(userService.findById(12L)).thenReturn(Optional.empty());

            String currentEtag = controller.getAvatar(12L, null).getHeaders().getETag();
            ResponseEntity<byte[]> conditional = controller.getAvatar(12L, currentEtag);

            assertThat(conditional.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
            assertThat(conditional.getHeaders().getETag()).isEqualTo(currentEtag);
            assertThat(conditional.getHeaders().getCacheControl()).isEqualTo("no-cache");
        }
    }
}
