package com.apimarketplace.common.scope;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ScopeGuard - multi-org isolation predicate")
class ScopeGuardTest {

    private static final String USER_A = "user-A";
    private static final String USER_B = "user-B";
    private static final String ORG_A = "org-A";
    private static final String ORG_B = "org-B";

    @Test
    @DisplayName("utility class is not instantiable")
    void utilityClassIsNotInstantiable() throws Exception {
        java.lang.reflect.Constructor<ScopeGuard> ctor = ScopeGuard.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        assertThatThrownBy(ctor::newInstance)
                .hasCauseInstanceOf(AssertionError.class);
    }

    @Nested
    @DisplayName("isInStrictScope - caller in org workspace")
    class StrictOrgScope {

        @Test
        @DisplayName("matches row tagged with same org")
        void matchesRowInSameOrg() {
            assertThat(ScopeGuard.isInStrictScope(USER_A, ORG_A, USER_A, ORG_A)).isTrue();
        }

        @Test
        @DisplayName("matches row tagged with same org even if owned by a different user (team chat)")
        void matchesRowOwnedByOtherUserInSameOrg() {
            // Org workspace is shared: owner doesn't matter, only org match.
            assertThat(ScopeGuard.isInStrictScope(USER_A, ORG_A, USER_B, ORG_A)).isTrue();
        }

        @Test
        @DisplayName("rejects row tagged with a different org")
        void rejectsRowInDifferentOrg() {
            assertThat(ScopeGuard.isInStrictScope(USER_A, ORG_A, USER_A, ORG_B)).isFalse();
        }

        @Test
        @DisplayName("matches org row even when callerUserId is null - org membership is gateway-validated upstream")
        void orgBranchDoesNotRequireCallerUserId() {
            // The org-workspace branch trusts the gateway's membership check
            // and does not require callerUserId. A future refactor that adds
            // a callerUserId null-guard before the org branch would silently
            // break authenticated-but-userId-stripped paths (rare but possible
            // in internal HTTP forwards).
            assertThat(ScopeGuard.isInStrictScope(null, ORG_A, USER_B, ORG_A)).isTrue();
            assertThat(ScopeGuard.isInStrictScope("", ORG_A, USER_B, ORG_A)).isTrue();
        }

        @Test
        @DisplayName("rejects personal-scope row (org=NULL) even if owned by the caller - the bug fix")
        void rejectsPersonalRowWhenCallerInOrg() {
            // Regression: pre-fix, the lax predicate accepted this because
            // userId matched. Strict isolation MUST reject - the caller's
            // active workspace is OrgA, the row is in personal scope.
            assertThat(ScopeGuard.isInStrictScope(USER_A, ORG_A, USER_A, null)).isFalse();
        }
    }

    @Nested
    @DisplayName("isInStrictScope - caller in personal workspace")
    class StrictPersonalScope {

        @Test
        @DisplayName("matches row owned by caller AND with org=NULL")
        void matchesPersonalRowOwnedByCaller() {
            assertThat(ScopeGuard.isInStrictScope(USER_A, null, USER_A, null)).isTrue();
        }

        @Test
        @DisplayName("matches when caller orgId is blank (treated as personal)")
        void blankOrgIdTreatedAsPersonal() {
            assertThat(ScopeGuard.isInStrictScope(USER_A, "   ", USER_A, null)).isTrue();
        }

        @Test
        @DisplayName("rejects row owned by another user")
        void rejectsRowOwnedByOtherUser() {
            assertThat(ScopeGuard.isInStrictScope(USER_A, null, USER_B, null)).isFalse();
        }

        @Test
        @DisplayName("rejects row tagged with an org even when owned by caller - symmetric to the bug fix")
        void rejectsOrgTaggedRowWhenCallerInPersonal() {
            // Symmetric regression guard: caller in personal scope must NOT
            // see their org-tagged rows. They will see them when they switch
            // back into the matching org workspace.
            assertThat(ScopeGuard.isInStrictScope(USER_A, null, USER_A, ORG_A)).isFalse();
        }

        @Test
        @DisplayName("rejects when callerUserId is null or blank (no identity → no match)")
        void rejectsWhenCallerUserIdMissing() {
            assertThat(ScopeGuard.isInStrictScope(null, null, USER_A, null)).isFalse();
            assertThat(ScopeGuard.isInStrictScope("", null, USER_A, null)).isFalse();
            assertThat(ScopeGuard.isInStrictScope("   ", null, USER_A, null)).isFalse();
        }
    }

    @Nested
    @DisplayName("isInOwnerOrOrgScope - tolerant fallback for internal channels")
    class TolerantScope {

        @Test
        @DisplayName("matches owner regardless of caller's active workspace")
        void matchesOwnerAcrossWorkspaces() {
            // The tolerance: caller in OrgA workspace can still authorize on
            // their personal row. Reserved for internal callers (WS channels
            // already gated upstream).
            assertThat(ScopeGuard.isInOwnerOrOrgScope(USER_A, ORG_A, USER_A, null)).isTrue();
        }

        @Test
        @DisplayName("matches org membership even when caller is not the owner")
        void matchesOrgWhenNotOwner() {
            assertThat(ScopeGuard.isInOwnerOrOrgScope(USER_A, ORG_A, USER_B, ORG_A)).isTrue();
        }

        @Test
        @DisplayName("rejects when neither owner nor org match")
        void rejectsCrossUserCrossOrg() {
            assertThat(ScopeGuard.isInOwnerOrOrgScope(USER_A, ORG_A, USER_B, ORG_B)).isFalse();
        }

        @Test
        @DisplayName("rejects when callerUserId is blank AND no org match")
        void rejectsWhenBothIdentifiersMissing() {
            assertThat(ScopeGuard.isInOwnerOrOrgScope(null, null, USER_A, ORG_A)).isFalse();
            assertThat(ScopeGuard.isInOwnerOrOrgScope("", "", USER_A, ORG_A)).isFalse();
        }
    }

    @Nested
    @DisplayName("crossResourceMatches - parent vs child workspace match")
    class CrossResourceMatches {

        @Test
        @DisplayName("matches when both are personal (both NULL)")
        void bothNullMatches() {
            assertThat(ScopeGuard.crossResourceMatches(null, null)).isTrue();
        }

        @Test
        @DisplayName("matches when both tagged with the same org")
        void sameOrgMatches() {
            assertThat(ScopeGuard.crossResourceMatches(ORG_A, ORG_A)).isTrue();
        }

        @Test
        @DisplayName("rejects when parent is personal but child has an org")
        void parentPersonalChildOrgRejected() {
            assertThat(ScopeGuard.crossResourceMatches(null, ORG_A)).isFalse();
        }

        @Test
        @DisplayName("rejects when parent has an org but child is personal")
        void parentOrgChildPersonalRejected() {
            assertThat(ScopeGuard.crossResourceMatches(ORG_A, null)).isFalse();
        }

        @Test
        @DisplayName("rejects when parent and child have different orgs")
        void differentOrgRejected() {
            assertThat(ScopeGuard.crossResourceMatches(ORG_A, ORG_B)).isFalse();
        }
    }
}
