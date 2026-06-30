package com.apimarketplace.agent.service.credentials;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CredentialsCallerChain")
class CredentialsCallerChainTest {

    @Nested
    @DisplayName("get")
    class Get {

        @Test
        @DisplayName("returns empty list when credentials is null")
        void nullCredentials() {
            assertThat(CredentialsCallerChain.get(null)).isEmpty();
        }

        @Test
        @DisplayName("returns empty list when key is absent")
        void keyAbsent() {
            assertThat(CredentialsCallerChain.get(Map.of("other", "value"))).isEmpty();
        }

        @Test
        @DisplayName("returns empty list when stored value is not a list (defensive)")
        void nonListValue() {
            Map<String, Object> creds = new HashMap<>();
            creds.put(CredentialsCallerChain.KEY, "not-a-list");
            assertThat(CredentialsCallerChain.get(creds)).isEmpty();
        }

        @Test
        @DisplayName("returns the stored chain in nearest-first order")
        void returnsStoredChain() {
            UUID parent = UUID.randomUUID();
            UUID grandparent = UUID.randomUUID();
            Map<String, Object> creds = new HashMap<>();
            creds.put(CredentialsCallerChain.KEY, List.of(parent, grandparent));

            List<UUID> chain = CredentialsCallerChain.get(creds);

            assertThat(chain).containsExactly(parent, grandparent);
        }

        @Test
        @DisplayName("returned list is an immutable copy (mutating the source does not affect it)")
        void returnedListIsImmutableCopy() {
            UUID parent = UUID.randomUUID();
            // Use a mutable list so we can try to mutate it after get()
            java.util.ArrayList<UUID> backing = new java.util.ArrayList<>();
            backing.add(parent);
            Map<String, Object> creds = new HashMap<>();
            creds.put(CredentialsCallerChain.KEY, backing);

            List<UUID> chain = CredentialsCallerChain.get(creds);

            // Mutating the backing list must NOT bleed into the returned snapshot
            backing.add(UUID.randomUUID());
            assertThat(chain).hasSize(1).containsExactly(parent);

            // And the returned list itself must be unmodifiable
            assertThatThrownBy(() -> chain.add(UUID.randomUUID()))
                .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("forChild")
    class ForChild {

        @Test
        @DisplayName("empty parent chain + parent id -> single-element chain")
        void rootSpawn() {
            UUID parent = UUID.randomUUID();

            List<UUID> chain = CredentialsCallerChain.forChild(Map.of(), parent);

            assertThat(chain).containsExactly(parent);
        }

        @Test
        @DisplayName("null parent credentials + parent id -> single-element chain")
        void nullParentCredentials() {
            UUID parent = UUID.randomUUID();

            List<UUID> chain = CredentialsCallerChain.forChild(null, parent);

            assertThat(chain).containsExactly(parent);
        }

        @Test
        @DisplayName("prepends parent id to existing chain (nearest-first)")
        void prependsNearestFirst() {
            UUID parent = UUID.randomUUID();
            UUID grandparent = UUID.randomUUID();
            UUID root = UUID.randomUUID();
            Map<String, Object> parentCreds = new HashMap<>();
            parentCreds.put(CredentialsCallerChain.KEY, List.of(grandparent, root));

            List<UUID> chain = CredentialsCallerChain.forChild(parentCreds, parent);

            assertThat(chain).containsExactly(parent, grandparent, root);
        }

        @Test
        @DisplayName("returned list is immutable")
        void returnsImmutableList() {
            UUID parent = UUID.randomUUID();

            List<UUID> chain = CredentialsCallerChain.forChild(Map.of(), parent);

            assertThatThrownBy(() -> chain.add(UUID.randomUUID()))
                .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("forChild does not mutate the parent credentials map")
        void doesNotMutateParent() {
            UUID parent = UUID.randomUUID();
            UUID grandparent = UUID.randomUUID();
            Map<String, Object> parentCreds = new HashMap<>();
            parentCreds.put(CredentialsCallerChain.KEY, List.of(grandparent));

            CredentialsCallerChain.forChild(parentCreds, parent);

            // Parent map's chain entry is untouched
            @SuppressWarnings("unchecked")
            List<UUID> parentChain = (List<UUID>) parentCreds.get(CredentialsCallerChain.KEY);
            assertThat(parentChain).containsExactly(grandparent);
        }
    }
}
