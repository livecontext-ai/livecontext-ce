package com.apimarketplace.auth.service.mail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shared e-mail shell. What matters: it never lets user input become markup, it declares the
 * language it is written in, and the text alternative says the same thing as the HTML one.
 */
@DisplayName("BrandedMail - one shell, escaped, and honest about its language")
class BrandedMailTest {

    private static final String LOGO = "https://app.example.com/logo.png";

    @Test
    @DisplayName("declares the recipient's language, so a client does not offer to translate it")
    void declaresTheLanguage() {
        String html = BrandedMail.html(LOGO, "fr", null, "Titre", List.of("Corps"), null, null, null,
                null, BrandedMail.Footer.of("Pied"));

        assertThat(html).contains("<html lang=\"fr\">");
    }

    @Test
    @DisplayName("falls back to en rather than emitting an empty lang attribute")
    void missingLocaleFallsBack() {
        String html = BrandedMail.html(LOGO, null, null, "Title", List.of("Body"), null, null, null, null, null);

        assertThat(html).contains("<html lang=\"en\">");
    }

    @Test
    @DisplayName("escapes every interpolated value, including the ones we supply ourselves")
    void escapesEverything() {
        String html = BrandedMail.html(LOGO, "en", "<i>pre</i>", "<script>t</script>",
                List.of("<script>body</script>"), "<code>", "<note>",
                new BrandedMail.Button("https://x/?a=1&b=2", "<label>"),
                null, BrandedMail.Footer.of("<foot>"));

        assertThat(html).doesNotContain("<script>", "<i>pre</i>", "<code>", "<note>", "<label>", "<foot>");
        assertThat(html).contains("&lt;script&gt;body&lt;/script&gt;");
        // An ampersand inside a URL must become &amp; or the href is a different URL. The button is the
        // only slot carrying one now: the footer had an optional link that no mail ever passed, whose
        // plain-text branch appended the literal string "null" when the label was omitted.
        assertThat(html).contains("https://x/?a=1&amp;b=2");
    }

    @Test
    @DisplayName("a *starred* span becomes bold, and markup in the value still cannot escape")
    void emphasisIsSafe() {
        String html = BrandedMail.html(LOGO, "en", null, "Title",
                List.of("*<b>Acme</b>* invited you"), null, null, null, null, null);

        // The star pair became a tag; the user's own <b> did not.
        assertThat(html).contains("<strong>&lt;b&gt;Acme&lt;/b&gt;</strong>");
        assertThat(html).doesNotContain("<b>Acme</b>");
    }

    @Test
    @DisplayName("a lone star is left alone, so a name with one cannot open a tag that never closes")
    void unbalancedEmphasisIsInert() {
        assertThat(BrandedMail.emphasize("2 * 3 = 6")).isEqualTo("2 * 3 = 6");
        assertThat(BrandedMail.emphasize("*open")).isEqualTo("*open");
    }

    @Test
    @DisplayName("a star inside an interpolated VALUE shifts every emphasis after it, so it is stripped")
    void starInAValueWouldShiftTheEmphasis() {
        // What it does unstripped, which is the reason plainValue exists: the markers pair up
        // across the sentence instead of around the words the catalog chose.
        String broken = BrandedMail.emphasize(BrandedMail.escape("*Star* Labs* invited you to join *Org*"));
        assertThat(broken).contains("<strong>Star</strong>");
        assertThat(broken).endsWith("*");

        assertThat(BrandedMail.plainValue("*Star* Labs")).isEqualTo("Star Labs");
        assertThat(BrandedMail.plainValue("Acme")).isEqualTo("Acme");

        // A value that would end up EMPTY becomes a single space, not nothing.
        //
        // Nothing leaves an empty `**` pair in the sentence, and an empty pair has no character
        // between its markers: the pairing pattern skips it and pairs its SECOND marker with the
        // opening one of the next span, so the emphasis slides onto the prose and a stray marker is
        // left at each end. A space keeps every pair balanced.
        assertThat(BrandedMail.plainValue(null)).isEqualTo(" ");
        assertThat(BrandedMail.plainValue("")).isEqualTo(" ");
        assertThat(BrandedMail.plainValue("*")).isEqualTo(" ");
        assertThat(BrandedMail.plainValue("***")).isEqualTo(" ");
    }

    @Test
    @DisplayName("an empty value leaves BOTH halves of the mail readable, not just the HTML one")
    void anEmptyValueDoesNotGarbleEitherHalf() {
        // The first fix for this deleted `**` inside `emphasize`, which repaired the HTML half and
        // left the text half - whose pairing runs in `unemphasize` - printing the garbled sentence
        // verbatim. Both halves are asserted here, which is what that fix was missing.
        //
        // The sentence is the real one: `invite.body` emphasizes three interpolated values, so an
        // empty one is exactly where a boundary can slide.
        String sentence = "*" + BrandedMail.plainValue("Alice") + "* invited you to join the"
                + " organization *" + BrandedMail.plainValue("") + "* on LiveContext as *"
                + BrandedMail.plainValue("MEMBER") + "*.";

        String html = BrandedMail.emphasize(BrandedMail.escape(sentence));
        String plain = BrandedMail.unemphasize(sentence);

        // The inviter and the role are still the bold spans; the prose is not.
        assertThat(html).contains("<strong>Alice</strong>", "<strong>MEMBER</strong>");
        assertThat(html).doesNotContain("<strong> on LiveContext as </strong>");
        // And no marker survives into either half.
        assertThat(html).doesNotContain("*");
        assertThat(plain).doesNotContain("*");
        assertThat(plain).contains("Alice invited you to join the organization");
        assertThat(plain).contains("on LiveContext as MEMBER.");
    }

    @Test
    @DisplayName("a template token inside user text can never expand, in ANY slot")
    void templateTokensInUserTextAreInert() {
        // Two guards, because ordering alone only protects the slot that goes in last. Escaping
        // breaks the opening pair, so a value carrying "{{LOGO}}" is inert wherever it lands -
        // including the title and preheader, which carry no user text today.
        String html = BrandedMail.html(LOGO, "en", "{{LOGO}}", "{{FOOTER}}",
                List.of("{{LOGO}} and {{FOOTER}} and {{TITLE}}"), null, "{{BODY}}",
                new BrandedMail.Button("https://x/a", "{{LOGO}}"),
                null, BrandedMail.Footer.of("Why you got this"));

        assertThat(html).doesNotContain("{{LOGO}}").doesNotContain("{{BODY}}").doesNotContain("{{TITLE}}");
        // The reader still sees the braces they typed, as literal text.
        assertThat(html).contains("&#123;&#123;LOGO&#125;".replace("&#125;", "}"));
        // And the real tokens resolved.
        assertThat(html).contains(LOGO).contains("Why you got this");
    }

    @Test
    @DisplayName("the text alternative carries the same content, with the markers removed")
    void plainMirrorsTheHtml() {
        String plain = BrandedMail.plain("Title", List.of("*Alice* invited you"), "123456",
                "Expires soon", new BrandedMail.Button("https://x/accept", "Accept"),
                null, BrandedMail.Footer.of("Why you got this"));

        assertThat(plain).contains("Title", "Alice invited you", "123456", "Expires soon",
                "Accept: https://x/accept", "Why you got this");
        // No markers and no markup leak into a part that is rendered as text.
        assertThat(plain).doesNotContain("*", "<strong>", "&lt;");
    }

    @Test
    @DisplayName("optional blocks are omitted rather than rendered empty")
    void optionalBlocksAreOmitted() {
        String html = BrandedMail.html(LOGO, "en", null, "Title", List.of("Body"), null, null, null, null, null);

        assertThat(html).doesNotContain("letter-spacing")   // no code box
                .doesNotContain("<a href")                  // no button, no footer link
                .doesNotContain("mso-hide");                // no preheader
        assertThat(BrandedMail.plain("Title", List.of(), null, null, null, null, null)).isEqualTo("Title");
    }

    @Test
    @DisplayName("an e-mail address in a paragraph is a link in the HTML part and bare in the text part")
    void addressesInParagraphsBecomeLinks() {
        // Two lifecycle mails end a sentence with a support address, and before this shell they were
        // hand-written anchors. Moving the sentences into the catalog made them inert text, because
        // every paragraph is escaped - most clients autolink one, some do not, and the reader of a
        // deactivation notice is being told where to write if it was not them.
        String html = BrandedMail.html(LOGO, "en", null, "Title",
                List.of("Reach us at help@example.org."), null, null, null, null, null, "help@example.org");

        assertThat(html).contains("<a href=\"mailto:help@example.org\"");
        // The trailing full stop ends the sentence, not the address: it is outside the anchor.
        assertThat(html).doesNotContain("mailto:help@example.org.");
        assertThat(html).contains("</a>.");

        // The text part keeps the bare address, which is what a text client should show.
        String plain = BrandedMail.plain("Title", List.of("Reach us at help@example.org."),
                null, null, null, null, null);
        assertThat(plain).contains("help@example.org.").doesNotContain("mailto:", "<a");
    }

    @Test
    @DisplayName("links ONLY the address the caller declared, never one that arrived in a value")
    void linksOnlyTheDeclaredAddress() {
        // The invitation body interpolates an organisation name, an inviter name and a role, none of
        // which the product controls, and it goes to somebody who may not have an account. The first
        // version of the linkifier matched anything address-SHAPED in any paragraph, so an org named
        // security@their-bank.example became a live mailto anchor inside a LiveContext-branded mail.
        // Escaping held, so nothing could be injected; a link whose text the sender chose is still an
        // affordance that did not exist before the shell.
        String withoutDeclaration = BrandedMail.html(LOGO, "en", null, "Title",
                List.of("joined security@their-bank.example today"), null, null, null, null, null);

        assertThat(withoutDeclaration).contains("security@their-bank.example");
        assertThat(withoutDeclaration).doesNotContain("mailto:");

        // And with a declared address, ONLY that one is linked.
        String withDeclaration = BrandedMail.html(LOGO, "en", null, "Title",
                List.of("write to help@example.org about security@their-bank.example"),
                null, null, null, null, null, "help@example.org");

        assertThat(withDeclaration).contains("mailto:help@example.org");
        assertThat(withDeclaration).doesNotContain("mailto:security@their-bank.example");
    }

    @Test
    @DisplayName("linking runs after escaping, so neither the text nor the ADDRESS can smuggle markup")
    void linkingCannotBeUsedToInjectMarkup() {
        // The 10-argument overload, with a declared address, or this test exercises nothing.
        //
        // The previous version passed NINE arguments, which binds the overload that supplies a null
        // `linkableAddress` - so `linkifyAddress` returned on its first line and the test proved only
        // that `escape` works, which the first test in this file already proves. Replacing the whole
        // body of `linkifyAddress` with `return escaped;` left it green. The one assertion written
        // for the injection safety of the newest helper on the path closest to user input reached
        // none of it.
        String paragraph = "write to help@example.org about \"><script>alert(1)</script>";
        String html = BrandedMail.html(LOGO, "en", null, "Title", List.of(paragraph),
                null, null, null, null, null, "help@example.org");

        // The declared address IS linked, so the linkifier ran.
        assertThat(html).contains("<a href=\"mailto:help@example.org\"");
        // And the markup in the paragraph is still inert.
        assertThat(html).doesNotContain("<script>");
        assertThat(html).contains("&lt;script&gt;");
    }

    @Test
    @DisplayName("an ADDRESS carrying markup is escaped before it reaches the href")
    void theAddressItselfIsEscaped() {
        // The address is a configured property, so this is defence and not a live threat - but the
        // helper builds an `href` out of it, and the version that built it from the raw argument
        // rather than the escaped one passed every test that existed. The paragraph carries the same
        // raw text, so if the needle were unescaped it would match there and be copied into the
        // attribute verbatim.
        String hostile = "a\"><script>alert(1)</script>@example.org";
        String html = BrandedMail.html(LOGO, "en", null, "Title", List.of("write to " + hostile),
                null, null, null, null, null, hostile);

        assertThat(html).doesNotContain("<script>");
        assertThat(html).doesNotContain("\"><script");
        // Linked, with every dangerous character already entity-encoded inside the attribute.
        assertThat(html).contains("mailto:a&quot;&gt;&lt;script&gt;");
    }

    @Test
    @DisplayName("emphasis is a BODY PARAGRAPH convention, and the two halves agree about where")
    void emphasisAppliesToParagraphsOnly() {
        // Only paragraphs are emphasized, and this asserts BOTH halves of the mail rather than one.
        //
        // The previous version called `html()` alone under a comment saying "a star in a note would
        // print as an asterisk in HTML while `plain()` strips it from the text part - two halves
        // disagreeing". `plain()` unemphasizes the title and the paragraphs and nothing else, so a
        // note keeps its asterisk in BOTH parts and there is no disagreement there. The title is the
        // only slot where the halves differ, and it is the one the comment did not name.
        String html = BrandedMail.html(LOGO, "en", null, "*Title*", List.of("a *bold* word"),
                null, "a *note*", null, null, null);
        String plain = BrandedMail.plain("*Title*", List.of("a *bold* word"), null, "a *note*",
                null, null, null);

        // A paragraph: bold in HTML, markers gone from the text part.
        assertThat(html).contains("a <strong>bold</strong> word");
        assertThat(plain).contains("a bold word");

        // A note: the asterisks survive in both, which is why the convention is paragraph-only.
        assertThat(html).contains("a *note*");
        assertThat(plain).contains("a *note*");

        // The title: literal in HTML, because the same value fills the <title> element of the
        // document head where a tag would show - and stripped in the text part. This one slot really
        // does disagree, which is the reason the header tells authors not to put a star in it.
        assertThat(html).contains("*Title*");
        assertThat(plain).startsWith("Title");
    }

    @Test
    @DisplayName("the logo URL is built in ONE place, cache-bust included")
    void logoUrlIsBuiltHere() {
        // The same literal was assembled SEVEN times across FIVE classes, so a brand change or a bump
        // of ?v= was a five-file edit with four chances to miss one. (Counted with a pickaxe on the
        // merge base: the deactivation mailer had three of the seven. This comment said "six mailers"
        // and "five chances", disagreeing with the javadoc on the method it tests, which had it
        // right - two numbers for one fact, in one change.)
        assertThat(BrandedMail.logoUrl("https://app.example.com"))
                .isEqualTo("https://app.example.com/liveContext-logo-light.png?v=2");
    }

    @Test
    @DisplayName("the blocks come out in reading order: body, code, button, note, sign-off, footer")
    void blocksAreInReadingOrder() {
        // The guard whose absence let five mails read backwards.
        //
        // The shell decides block order, so a mailer cannot get it right or wrong on its own - and
        // nothing asserted it. Two slots were in the wrong place: the note came before the button, so
        // "if the button does not work, copy and paste this link" sat ABOVE anything clickable; and
        // the sign-off was passed as the last paragraph, so it sat above the call to action in every
        // mail, and in the verification mail between "use the code below" and the code itself.
        String html = BrandedMail.html(LOGO, "en", null, "Title", List.of("Body"),
                "123456", "Note", new BrandedMail.Button("https://x/go", "Go"), "Sign off",
                BrandedMail.Footer.of("Footer"));

        assertThat(indexOfEachInTurn(html, "Body", "123456", "Go", "Note", "Sign off", "Footer"))
                .as("HTML blocks must appear in this order")
                .isTrue();

        String plain = BrandedMail.plain("Title", List.of("Body"), "123456", "Note",
                new BrandedMail.Button("https://x/go", "Go"), "Sign off",
                BrandedMail.Footer.of("Footer"));

        assertThat(indexOfEachInTurn(plain, "Body", "123456", "Go", "Note", "Sign off", "Footer"))
                .as("the text part must read in the same order as the HTML one")
                .isTrue();
    }

    /** True when each needle appears after the one before it. */
    private static boolean indexOfEachInTurn(String haystack, String... needles) {
        int previous = -1;
        for (String needle : needles) {
            int at = haystack.indexOf(needle, previous + 1);
            if (at <= previous) return false;
            previous = at;
        }
        return true;
    }

    @Test
    @DisplayName("a subject is flattened to one line but NOT html-escaped")
    void subjectIsOneLineAndUnescaped() {
        assertThat(BrandedMail.subject("Ben & Jerry's invited you")).isEqualTo("Ben & Jerry's invited you");
        assertThat(BrandedMail.subject("Acme\r\nBcc: attacker@example.com"))
                .isEqualTo("Acme Bcc: attacker@example.com");
        assertThat(BrandedMail.subject(null)).isEmpty();
    }
}
