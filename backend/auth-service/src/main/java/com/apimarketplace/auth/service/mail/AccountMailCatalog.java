package com.apimarketplace.auth.service.mail;

import com.apimarketplace.common.i18n.MessageCatalog;

import java.util.Map;

/**
 * The one catalog every account e-mail is written from ({@code i18n/account-mail_*.properties}).
 *
 * <p>Four mailers loaded it independently, which meant four statics holding four copies of the
 * same file and a test whose job was to check they still agreed. One constant removes both.
 */
public final class AccountMailCatalog {

    public static final MessageCatalog INSTANCE = MessageCatalog.load("i18n/account-mail");

    /**
     * "Hi {name}," when there is a name to use, "Hi," otherwise.
     *
     * <p>Two keys rather than a {@code {name}} filled with a word like "there": that word is not
     * translatable inside a greeting, and several languages punctuate the two forms differently.
     *
     * <p>Here rather than in a mailer because it was byte-identical in two of them and inlined as
     * a third variant in a third, all reading from this same catalog.
     */
    public static String greeting(String locale, String displayName) {
        if (displayName == null || displayName.isBlank()) {
            return INSTANCE.text(locale, "common.greetingNoName");
        }
        // The name as TYPED, with nothing stripped.
        //
        // `plainValue` exists to stop a value shifting the emphasis boundaries of a sentence that HAS
        // emphasis, and `common.greeting` has none in any of the six locales, so there is no boundary
        // here to protect. What stripping would cost is a name altered without asking: a person
        // called "A*B" would be greeted as "AB".
        //
        // The consequence to accept, since a name is not a place to be clever: a name that contains a
        // BALANCED pair renders that part bold in the HTML half and plain in the text half - "Anne
        // **Marie** Smith" is the realistic case. A name that bolds part of itself is a smaller
        // surprise than a name silently rewritten, which is the trade this makes deliberately.
        return INSTANCE.text(locale, "common.greeting", Map.of("name", displayName.trim()));
    }

    private AccountMailCatalog() {
    }
}
