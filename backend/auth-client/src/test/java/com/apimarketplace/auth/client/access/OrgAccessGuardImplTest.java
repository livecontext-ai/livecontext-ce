package com.apimarketplace.auth.client.access;

import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.auth.client.dto.OrgRestrictionDto;
import com.apimarketplace.common.event.EventBus;
import com.apimarketplace.common.scope.OrgAccessCacheInvalidation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class OrgAccessGuardImplTest {

    private AuthClient authClient;
    private OrgAccessGuardImpl guard;

    private static final String ORG = "org-1";
    private static final String USER = "user-1";
    private static final String TYPE = "workflow";

    @BeforeEach
    void setUp() {
        authClient = mock(AuthClient.class);
        guard = new OrgAccessGuardImpl(authClient);
    }

    @Nested
    @DisplayName("Admin role bypass")
    class AdminBypass {

        @Test
        @DisplayName("OWNER role short-circuits before any AuthClient call")
        void ownerNeverQueriesAuthClient() {
            Set<String> result = guard.getRestrictedResourceIds(ORG, USER, TYPE, "OWNER");

            assertThat(result).isEmpty();
            verifyNoInteractions(authClient);
        }

        @Test
        @DisplayName("ADMIN role short-circuits before any AuthClient call")
        void adminNeverQueriesAuthClient() {
            Set<String> result = guard.getRestrictedResourceIds(ORG, USER, TYPE, "ADMIN");

            assertThat(result).isEmpty();
            verifyNoInteractions(authClient);
        }

        @Test
        @DisplayName("filterAccessible returns input unchanged for OWNER")
        void ownerFilterPassesThrough() {
            List<String> input = List.of("a", "b", "c");

            List<String> result = guard.filterAccessible(input, ORG, USER, TYPE, "OWNER", s -> s);

            assertThat(result).containsExactlyElementsOf(input);
            verifyNoInteractions(authClient);
        }
    }

    @Nested
    @DisplayName("Write access (read-only vs read-write)")
    class WriteAccess {

        @Test
        @DisplayName("canWrite is false when the resource is write-restricted (DENY or READ)")
        void canWriteFalseWhenWriteRestricted() {
            when(authClient.getWriteRestrictedResourceIds(ORG, USER, TYPE)).thenReturn(Set.of("r1"));

            assertThat(guard.canWrite(ORG, USER, TYPE, "r1", "MEMBER")).isFalse();
            assertThat(guard.canWrite(ORG, USER, TYPE, "r2", "MEMBER")).isTrue();
        }

        @Test
        @DisplayName("read-only resource: canAccess (read) true but canWrite false")
        void readOnlyAllowsReadDeniesWrite() {
            // The read set excludes READ-only ids (server returns DENY-only); the write set includes them.
            when(authClient.getRestrictedResourceIds(ORG, USER, TYPE)).thenReturn(Set.of());
            when(authClient.getWriteRestrictedResourceIds(ORG, USER, TYPE)).thenReturn(Set.of("readonly-1"));

            assertThat(guard.canAccess(ORG, USER, TYPE, "readonly-1", "MEMBER")).isTrue();
            assertThat(guard.canWrite(ORG, USER, TYPE, "readonly-1", "MEMBER")).isFalse();
        }

        @Test
        @DisplayName("OWNER/ADMIN can always write, without any AuthClient call")
        void adminCanAlwaysWrite() {
            assertThat(guard.canWrite(ORG, USER, TYPE, "r1", "OWNER")).isTrue();
            assertThat(guard.getWriteRestrictedResourceIds(ORG, USER, TYPE, "ADMIN")).isEmpty();
            verifyNoInteractions(authClient);
        }

        @Test
        @DisplayName("canWrite with a null resourceId returns false")
        void canWriteNullResourceIdFalse() {
            assertThat(guard.canWrite(ORG, USER, TYPE, null, "MEMBER")).isFalse();
        }
    }

    @Nested
    @DisplayName("VIEWER role-level read-only (regression: VIEWER could write unrestricted org resources)")
    class ViewerReadOnly {

        @Test
        @DisplayName("canWrite is false for VIEWER even with an empty per-resource deny-list")
        void viewerCannotWriteUnrestrictedResource() {
            // Pre-fix, VIEWER was treated identically to MEMBER: with no individual
            // restriction on the resource, canWrite returned true and a VIEWER could
            // delete/save/restore org workflows whose endpoints had no ad-hoc
            // isViewerRole gate. The role boundary is now enforced centrally.
            assertThat(guard.canWrite(ORG, USER, TYPE, "r1", "VIEWER")).isFalse();
            verifyNoInteractions(authClient);
        }

        @Test
        @DisplayName("VIEWER match is case-insensitive and trims whitespace")
        void viewerMatchIsLenient() {
            assertThat(guard.canWrite(ORG, USER, TYPE, "r1", "viewer")).isFalse();
            assertThat(guard.canWrite(ORG, USER, TYPE, "r1", " Viewer ")).isFalse();
        }

        @Test
        @DisplayName("VIEWER outside an org workspace (null/blank orgId) is not blocked by the role gate")
        void viewerWithoutOrgIsNotRoleBlocked() {
            // Personal workspace: no org context, the role header is meaningless.
            // getWriteRestrictedResourceIds(null org) short-circuits to empty.
            assertThat(guard.canWrite(null, USER, TYPE, "r1", "VIEWER")).isTrue();
            assertThat(guard.canWrite("  ", USER, TYPE, "r1", "VIEWER")).isTrue();
        }

        @Test
        @DisplayName("VIEWER read path is untouched: canAccess and filterAccessible behave like MEMBER")
        void viewerReadsAreUnaffected() {
            when(authClient.getRestrictedResourceIds(ORG, USER, TYPE)).thenReturn(Set.of("hidden"));

            assertThat(guard.canAccess(ORG, USER, TYPE, "visible", "VIEWER")).isTrue();
            assertThat(guard.canAccess(ORG, USER, TYPE, "hidden", "VIEWER")).isFalse();
            assertThat(guard.filterAccessible(List.of("visible", "hidden"), ORG, USER, TYPE, "VIEWER", s -> s))
                    .containsExactly("visible");
        }

        @Test
        @DisplayName("MEMBER write behaviour is unchanged by the VIEWER gate")
        void memberUnchanged() {
            when(authClient.getWriteRestrictedResourceIds(ORG, USER, TYPE)).thenReturn(Set.of());

            assertThat(guard.canWrite(ORG, USER, TYPE, "r1", "MEMBER")).isTrue();
        }

        @Test
        @DisplayName("isRoleWriteBlocked exposes the same boundary for id-less call sites (create, bulk)")
        void isRoleWriteBlockedContract() {
            assertThat(OrgAccessGuard.isRoleWriteBlocked(ORG, "VIEWER")).isTrue();
            assertThat(OrgAccessGuard.isRoleWriteBlocked(ORG, "viewer ")).isTrue();
            assertThat(OrgAccessGuard.isRoleWriteBlocked(null, "VIEWER")).isFalse();
            assertThat(OrgAccessGuard.isRoleWriteBlocked(" ", "VIEWER")).isFalse();
            assertThat(OrgAccessGuard.isRoleWriteBlocked(ORG, "MEMBER")).isFalse();
            assertThat(OrgAccessGuard.isRoleWriteBlocked(ORG, null)).isFalse();
        }
    }

    @Nested
    @DisplayName("Restriction lookup")
    class RestrictionLookup {

        @Test
        @DisplayName("MEMBER role queries AuthClient and returns the restricted set")
        void memberQueriesAuthClient() {
            when(authClient.getRestrictedResourceIds(ORG, USER, TYPE))
                    .thenReturn(Set.of("r1", "r2"));

            Set<String> result = guard.getRestrictedResourceIds(ORG, USER, TYPE, "MEMBER");

            assertThat(result).containsExactlyInAnyOrder("r1", "r2");
            verify(authClient).getRestrictedResourceIds(ORG, USER, TYPE);
        }

        @Test
        @DisplayName("null orgRole is treated as non-admin and triggers AuthClient")
        void nullRoleIsNotAdmin() {
            when(authClient.getRestrictedResourceIds(ORG, USER, TYPE))
                    .thenReturn(Set.of("r1"));

            Set<String> result = guard.getRestrictedResourceIds(ORG, USER, TYPE, null);

            assertThat(result).containsExactly("r1");
            verify(authClient).getRestrictedResourceIds(ORG, USER, TYPE);
        }

        @Test
        @DisplayName("null orgId / userId / resourceType returns empty without touching AuthClient")
        void nullArgsReturnEmpty() {
            assertThat(guard.getRestrictedResourceIds(null, USER, TYPE, "MEMBER")).isEmpty();
            assertThat(guard.getRestrictedResourceIds(ORG, null, TYPE, "MEMBER")).isEmpty();
            assertThat(guard.getRestrictedResourceIds(ORG, USER, null, "MEMBER")).isEmpty();
            verifyNoInteractions(authClient);
        }
    }

    @Nested
    @DisplayName("canAccess gate")
    class CanAccess {

        @Test
        @DisplayName("returns false when resource is restricted")
        void deniesRestricted() {
            when(authClient.getRestrictedResourceIds(ORG, USER, TYPE))
                    .thenReturn(Set.of("r1"));

            assertThat(guard.canAccess(ORG, USER, TYPE, "r1", "MEMBER")).isFalse();
        }

        @Test
        @DisplayName("returns true when resource is not restricted")
        void allowsUnrestricted() {
            when(authClient.getRestrictedResourceIds(ORG, USER, TYPE))
                    .thenReturn(Set.of("r1"));

            assertThat(guard.canAccess(ORG, USER, TYPE, "r-other", "MEMBER")).isTrue();
        }

        @Test
        @DisplayName("OWNER always passes regardless of restrictions in DB")
        void ownerAlwaysPasses() {
            assertThat(guard.canAccess(ORG, USER, TYPE, "r1", "OWNER")).isTrue();
            verifyNoInteractions(authClient);
        }

        @Test
        @DisplayName("null resourceId returns false (defensive)")
        void nullResourceIdDenied() {
            assertThat(guard.canAccess(ORG, USER, TYPE, null, "MEMBER")).isFalse();
        }
    }

    @Nested
    @DisplayName("filterAccessible")
    class Filter {

        record Item(String id, String label) {}

        @Test
        @DisplayName("removes restricted items for MEMBER")
        void removesRestricted() {
            when(authClient.getRestrictedResourceIds(ORG, USER, TYPE))
                    .thenReturn(Set.of("a"));

            List<Item> input = List.of(new Item("a", "A"), new Item("b", "B"));
            List<Item> result = guard.filterAccessible(input, ORG, USER, TYPE, "MEMBER", Item::id);

            assertThat(result).extracting(Item::id).containsExactly("b");
        }

        @Test
        @DisplayName("returns input unchanged when no restrictions exist")
        void noRestrictionsPassesThrough() {
            when(authClient.getRestrictedResourceIds(ORG, USER, TYPE))
                    .thenReturn(Set.of());

            List<Item> input = List.of(new Item("a", "A"));
            List<Item> result = guard.filterAccessible(input, ORG, USER, TYPE, "MEMBER", Item::id);

            assertThat(result).containsExactlyElementsOf(input);
        }

        @Test
        @DisplayName("null/empty input returns input")
        void nullOrEmptyInput() {
            assertThat(guard.filterAccessible(null, ORG, USER, TYPE, "MEMBER", x -> "x")).isNull();
            assertThat(guard.filterAccessible(List.of(), ORG, USER, TYPE, "MEMBER", x -> "x")).isEmpty();
            verifyNoInteractions(authClient);
        }
    }

    @Nested
    @DisplayName("Cache")
    class Cache {

        @Test
        @DisplayName("Second lookup hits cache (no second AuthClient call)")
        void cacheHit() {
            OrgAccessGuardImpl cachedGuard = new OrgAccessGuardImpl(authClient, 300_000L);
            when(authClient.getRestrictedResourceIds(ORG, USER, TYPE))
                    .thenReturn(Set.of("r1"));

            cachedGuard.getRestrictedResourceIds(ORG, USER, TYPE, "MEMBER");
            cachedGuard.getRestrictedResourceIds(ORG, USER, TYPE, "MEMBER");

            verify(authClient, times(1)).getRestrictedResourceIds(ORG, USER, TYPE);
        }

        @Test
        @DisplayName("Different resourceTypes do not share a cache entry")
        void cacheKeyIncludesResourceType() {
            when(authClient.getRestrictedResourceIds(ORG, USER, "workflow"))
                    .thenReturn(Set.of("w1"));
            when(authClient.getRestrictedResourceIds(ORG, USER, "agent"))
                    .thenReturn(Set.of("a1"));

            assertThat(guard.getRestrictedResourceIds(ORG, USER, "workflow", "MEMBER")).containsExactly("w1");
            assertThat(guard.getRestrictedResourceIds(ORG, USER, "agent", "MEMBER")).containsExactly("a1");
        }

        @Test
        @DisplayName("invalidateCache forces a re-fetch on next read")
        void invalidationForcesRefetch() {
            when(authClient.getRestrictedResourceIds(ORG, USER, TYPE))
                    .thenReturn(Set.of("r1"))
                    .thenReturn(Set.of("r1", "r2"));

            guard.getRestrictedResourceIds(ORG, USER, TYPE, "MEMBER");
            guard.invalidateCache(ORG, USER);
            Set<String> after = guard.getRestrictedResourceIds(ORG, USER, TYPE, "MEMBER");

            assertThat(after).containsExactlyInAnyOrder("r1", "r2");
            verify(authClient, times(2)).getRestrictedResourceIds(ORG, USER, TYPE);
        }

        @Test
        @DisplayName("invalidateCacheForOrg drops every entry of that org")
        void invalidationByOrg() {
            OrgAccessGuardImpl cachedGuard = new OrgAccessGuardImpl(authClient, 300_000L);
            when(authClient.getRestrictedResourceIds(ORG, USER, "workflow"))
                    .thenReturn(Set.of("w1"))
                    .thenReturn(Set.of()); // after invalidation
            when(authClient.getRestrictedResourceIds(ORG, USER, "agent"))
                    .thenReturn(Set.of("a1"))
                    .thenReturn(Set.of()); // after invalidation

            cachedGuard.getRestrictedResourceIds(ORG, USER, "workflow", "MEMBER");
            cachedGuard.getRestrictedResourceIds(ORG, USER, "agent", "MEMBER");
            cachedGuard.invalidateCacheForOrg(ORG);
            cachedGuard.getRestrictedResourceIds(ORG, USER, "workflow", "MEMBER");
            cachedGuard.getRestrictedResourceIds(ORG, USER, "agent", "MEMBER");

            verify(authClient, times(2)).getRestrictedResourceIds(ORG, USER, "workflow");
            verify(authClient, times(2)).getRestrictedResourceIds(ORG, USER, "agent");
        }

        @Test
        @DisplayName("invalidateCache only drops entries of the targeted user")
        void invalidationIsUserScoped() {
            OrgAccessGuardImpl cachedGuard = new OrgAccessGuardImpl(authClient, 300_000L);
            when(authClient.getRestrictedResourceIds(ORG, USER, TYPE))
                    .thenReturn(Set.of("r1"));
            when(authClient.getRestrictedResourceIds(ORG, "user-2", TYPE))
                    .thenReturn(Set.of("r2"));

            cachedGuard.getRestrictedResourceIds(ORG, USER, TYPE, "MEMBER");
            cachedGuard.getRestrictedResourceIds(ORG, "user-2", TYPE, "MEMBER");
            cachedGuard.invalidateCache(ORG, USER);

            // user-1 must re-fetch
            cachedGuard.getRestrictedResourceIds(ORG, USER, TYPE, "MEMBER");
            // user-2 still cached
            cachedGuard.getRestrictedResourceIds(ORG, "user-2", TYPE, "MEMBER");

            verify(authClient, times(2)).getRestrictedResourceIds(ORG, USER, TYPE);
            verify(authClient, times(1)).getRestrictedResourceIds(ORG, "user-2", TYPE);
        }

        @Test
        @DisplayName("cross-service invalidation event forces a cached restriction re-fetch")
        void crossServiceInvalidationEventForcesRefetch() {
            SyncEventBus eventBus = new SyncEventBus();
            OrgAccessGuardImpl subscribedGuard = new OrgAccessGuardImpl(authClient, 300_000L, eventBus);
            when(authClient.getRestrictedResourceIds(ORG, USER, "agent"))
                    .thenReturn(Set.of())
                    .thenReturn(Set.of("agent-1"));

            assertThat(subscribedGuard.canAccess(ORG, USER, "agent", "agent-1", "MEMBER")).isTrue();
            eventBus.publish(
                    OrgAccessCacheInvalidation.CHANNEL,
                    OrgAccessCacheInvalidation.messageFor(ORG, USER));

            assertThat(subscribedGuard.canAccess(ORG, USER, "agent", "agent-1", "MEMBER")).isFalse();
            verify(authClient, times(2)).getRestrictedResourceIds(ORG, USER, "agent");
        }
    }

    @Nested
    @DisplayName("Cache TTL expiry")
    class CacheTtl {

        @Test
        @DisplayName("Entry expires after configured TTL and triggers re-fetch")
        void entryExpiresAndRefetches() throws Exception {
            // 1ms TTL guard - first lookup populates cache, sleep past TTL,
            // second lookup must re-query AuthClient.
            OrgAccessGuardImpl shortLived = new OrgAccessGuardImpl(authClient, 1L);
            when(authClient.getRestrictedResourceIds(ORG, USER, TYPE))
                    .thenReturn(Set.of("r1"))
                    .thenReturn(Set.of("r1", "r2"));

            shortLived.getRestrictedResourceIds(ORG, USER, TYPE, "MEMBER");
            Thread.sleep(5L);
            Set<String> after = shortLived.getRestrictedResourceIds(ORG, USER, TYPE, "MEMBER");

            assertThat(after).containsExactlyInAnyOrder("r1", "r2");
            verify(authClient, times(2)).getRestrictedResourceIds(ORG, USER, TYPE);
        }
    }

    @Nested
    @DisplayName("Documented behavioural deltas vs pre-factorisation copies")
    class BehaviouralDeltas {

        @Test
        @DisplayName("canAccess(null resourceId) always denied - including for OWNER - defensive fail-fast")
        void nullResourceIdAlwaysDenied() {
            // Prior orchestrator/OrgAccessService.canAccess returned true here because
            // Set.contains(null) is false. New contract: null resourceId is always
            // denied (fail-fast for caller bugs), even when orgRole=OWNER. Production
            // call-sites never pass null; the change is purely defensive.
            assertThat(guard.canAccess(ORG, USER, TYPE, null, "MEMBER")).isFalse();
            assertThat(guard.canAccess(ORG, USER, TYPE, null, "OWNER")).isFalse();
            assertThat(guard.canAccess(ORG, USER, TYPE, null, "ADMIN")).isFalse();
        }

        @Test
        @DisplayName("filterAccessible(null items) returns null instead of NPE")
        void nullItemsNoNpe() {
            assertThat(guard.filterAccessible(null, ORG, USER, TYPE, "MEMBER", x -> "x")).isNull();
        }

        @Test
        @DisplayName("getRestrictedResourceIds with null orgId/userId/resourceType returns empty without AuthClient call")
        void nullArgsShortCircuit() {
            assertThat(guard.getRestrictedResourceIds(null, USER, TYPE, "MEMBER")).isEmpty();
            assertThat(guard.getRestrictedResourceIds(ORG, null, TYPE, "MEMBER")).isEmpty();
            assertThat(guard.getRestrictedResourceIds(ORG, USER, null, "MEMBER")).isEmpty();
            verifyNoInteractions(authClient);
        }
    }

    @Nested
    @DisplayName("Write delegation")
    class WriteDelegation {

        @Test
        @DisplayName("restrictAccess delegates and invalidates the user's cache")
        void restrictDelegatesAndInvalidates() {
            // Prime cache
            when(authClient.getRestrictedResourceIds(ORG, USER, TYPE))
                    .thenReturn(Set.of("r1"))
                    .thenReturn(Set.of("r1", "r2"));
            guard.getRestrictedResourceIds(ORG, USER, TYPE, "MEMBER");

            guard.restrictAccess(ORG, USER, TYPE, "r2", "owner-X");

            verify(authClient).restrictAccess(ORG, USER, TYPE, "r2", "owner-X");
            // Next read must re-fetch (cache invalidated)
            Set<String> after = guard.getRestrictedResourceIds(ORG, USER, TYPE, "MEMBER");
            assertThat(after).containsExactlyInAnyOrder("r1", "r2");
        }

        @Test
        @DisplayName("grantAccess delegates and invalidates cache")
        void grantDelegatesAndInvalidates() {
            when(authClient.getRestrictedResourceIds(ORG, USER, TYPE))
                    .thenReturn(Set.of("r1"))
                    .thenReturn(Set.of());
            guard.getRestrictedResourceIds(ORG, USER, TYPE, "MEMBER");

            guard.grantAccess(ORG, USER, TYPE, "r1");

            verify(authClient).grantAccess(ORG, USER, TYPE, "r1");
            assertThat(guard.getRestrictedResourceIds(ORG, USER, TYPE, "MEMBER")).isEmpty();
        }

        @Test
        @DisplayName("setRestrictions delegates and invalidates cache")
        void setRestrictionsDelegatesAndInvalidates() {
            when(authClient.getRestrictedResourceIds(ORG, USER, TYPE))
                    .thenReturn(Set.of("r1"))
                    .thenReturn(Set.of("rA", "rB"));
            guard.getRestrictedResourceIds(ORG, USER, TYPE, "MEMBER");

            guard.setRestrictions(ORG, USER, TYPE, Set.of("rA", "rB"), "owner-X");

            verify(authClient).setRestrictions(ORG, USER, TYPE, Set.of("rA", "rB"), "owner-X");
            assertThat(guard.getRestrictedResourceIds(ORG, USER, TYPE, "MEMBER"))
                    .containsExactlyInAnyOrder("rA", "rB");
        }

        @Test
        @DisplayName("getMemberRestrictions is a pure pass-through to AuthClient")
        void getMemberRestrictionsPassesThrough() {
            List<OrgRestrictionDto> stub = List.of(); // empty is fine - type-checks the signature
            when(authClient.getMemberRestrictions(ORG, USER)).thenReturn(stub);

            List<OrgRestrictionDto> result = guard.getMemberRestrictions(ORG, USER);

            assertThat(result).isSameAs(stub);
        }
    }

    private static final class SyncEventBus implements EventBus {
        private final Map<String, List<Consumer<String>>> listeners = new HashMap<>();

        @Override
        public void publish(String channel, String message) {
            listeners.getOrDefault(channel, List.of()).forEach(listener -> listener.accept(message));
        }

        @Override
        public Subscription subscribe(String channel, Consumer<String> listener) {
            listeners.computeIfAbsent(channel, ignored -> new ArrayList<>()).add(listener);
            return () -> listeners.getOrDefault(channel, List.of()).remove(listener);
        }
    }
}
