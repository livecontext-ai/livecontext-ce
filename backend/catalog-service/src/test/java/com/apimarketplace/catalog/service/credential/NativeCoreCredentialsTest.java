package com.apimarketplace.catalog.service.credential;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The credentials a core workflow node reads, which no catalog API backs.
 *
 * <p>These names decide whether a credential the product genuinely offers can be created at
 * all. Every other template in {@code catalog.credentials} is found through a join on
 * {@code catalog.apis}; these have no API to join to, so a name missing from this list is a
 * credential form that never appears in Settings, for a node that is nonetheless shipped and
 * documented.
 *
 * <p>That is not hypothetical. {@code ssh}, {@code sftp} and {@code database} were absent for
 * as long as their nodes had existed. Both ends looked complete, the node resolved a
 * credential by name and the frontend offered to create one under that name, and only the
 * middle was missing. Production counted the cost: 2 imap credentials, 1 smtp, and zero ssh,
 * sftp or database across every tenant, because the only way left to run those nodes was to
 * type the host and the password into the node, which stores them in the workflow plan.
 */
class NativeCoreCredentialsTest {

    @Test
    @DisplayName("every core node that reads a native credential has its name in the list")
    void coversEveryCoreNodeCredential() {
        assertThat(NativeCoreCredentials.names())
                .as("send_email reads smtp, email_inbox reads imap, and ssh / sftp / database "
                        + "each read a template of their own name")
                .containsExactlyInAnyOrder("smtp", "imap", "ssh", "sftp", "database");
    }

    @Test
    @DisplayName("the SQL in-list quotes every name and separates them with commas")
    void buildsAQuotedInList() {
        String inList = NativeCoreCredentials.sqlInList();
        for (String name : NativeCoreCredentials.names()) {
            assertThat(inList).contains("'" + name + "'");
        }
        assertThat(inList.split(",")).hasSameSizeAs(NativeCoreCredentials.names());
    }

    /**
     * The list is inlined into SQL, so it is worth pinning that it can only ever contain
     * literals safe to inline. A name carrying a quote would be an injection, and the only way
     * one gets in is an edit to this class.
     */
    @Test
    @DisplayName("no name carries a quote or a backslash, since the list is inlined into SQL")
    void namesAreSafeToInline() {
        assertThat(NativeCoreCredentials.names())
                .allSatisfy(name -> assertThat(name).matches("[a-z0-9_]+"));
    }

    /**
     * The listing and the catalog bundle both read this list. A caller that could append to it
     * would change what the OTHER one ships, from anywhere in the service.
     */
    @Test
    @DisplayName("the list is immutable: a caller cannot edit what both readers share")
    void listIsImmutable() {
        assertThatThrownBy(() -> NativeCoreCredentials.names().add("injected"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
