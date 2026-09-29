package com.apimarketplace.auth.service.mail;

import java.util.ArrayList;
import java.util.List;

/**
 * The one branded shell every e-mail auth-service sends is built from: the logo header, the
 * white card, the heading, the body, an optional action button or verification code, and the
 * footer.
 *
 * <p>It exists because that table markup was COPIED into five mailers. Five copies meant five
 * places to localize, five places a brand change had to be repeated, and five chances for one
 * of them to keep saying {@code <html lang="en">} to a French reader - which is exactly what
 * they all did.
 *
 * <p>Holds no text of its own. Every string reaches it already translated (see the
 * {@code i18n/account-mail} catalog), including the footer, so the shell can never be the
 * reason a message is only available in English.
 *
 * <p>The heading renders at 22px, which is what the four mails that had one used before this shell
 * existed (the cloud-link recovery mail had no heading at all, and gained one here). It matches the
 * Keycloak e-mail theme, whose per-mail templates are also 22px, so the two halves of the product
 * do not disagree on the same heading. The mailers this shell does NOT render - the notification
 * digest and the contact-form relay - keep their own 20px templates.
 */
public final class BrandedMail {

    /** An action button: an absolute URL and the already-translated label on it. */
    public record Button(String url, String label) {}

    /**
     * The footer sentence.
     *
     * <p>It carried an optional {@code linkUrl}/{@code linkLabel} pair, described as "the manage
     * notifications of a digest". No mail rendered by this shell ever passed one - all five use
     * {@link #of} - and the digest mail does not use this shell at all: it keeps its own template
     * and borrows only {@link #logoUrl}. Both rendering branches were reachable from tests only, and
     * the plain-text one was wrong: a footer with a URL and no label appended the literal string
     * "null". Removed rather than fixed, since fixing it would have been maintaining a feature for a
     * caller that does not exist.
     */
    public record Footer(String text) {
        public static Footer of(String text) {
            return new Footer(text);
        }
    }

    /**
     * The header logo, absolute, cache-busted.
     *
     * <p>The same literal was assembled seven times across five classes, so a brand change or a bump
     * of {@code ?v=} was a five-file edit with four chances to miss one. It is one edit here now for
     * everything auth-service sends.
     *
     * <p>NOT for everything the product sends. An eighth copy lives in the Keycloak e-mail theme
     * ({@code infra/keycloak/themes/livecontext/email/html/template.ftl}), hard-coded to the absolute
     * cloud URL, so a self-hosted install loads the vendor's logo from the public internet on the
     * mails Keycloak itself sends. Not fixable from here: a FreeMarker theme cannot read a Spring
     * property, so it needs the image served from the theme's own resources, which is a change to a
     * separately deployed artifact. Named rather than left behind a false "one place" claim.
     */
    public static String logoUrl(String frontendUrl) {
        return frontendUrl + "/liveContext-logo-light.png?v=2";
    }

    private BrandedMail() {
    }

    /**
     * No linkable address, which is right for every mail whose paragraphs interpolate a value
     * somebody else typed. Merged into one javadoc block with the parameter list below, because
     * javadoc keeps only the last comment before a declaration.
     *
     * @param logoUrl    absolute URL of the header logo
     * @param locale     the recipient's locale, for the {@code <html lang>} attribute
     * @param preheader  the line a mail client previews next to the subject; null to omit
     * @param title      the heading, already translated
     * @param paragraphs body paragraphs, already translated; escaped here
     * @param code       a verification code to show in its own box; null to omit
     * @param note       a small grey line under the body (an expiry, a caveat); null to omit
     * @param button     an action button; null to omit
     * @param footer     the footer sentence and optional link
     */
    public static String html(String logoUrl,
                              String locale,
                              String preheader,
                              String title,
                              List<String> paragraphs,
                              String code,
                              String note,
                              Button button,
                              String signature,
                              Footer footer) {
        return html(logoUrl, locale, preheader, title, paragraphs, code, note, button, signature,
                footer, null);
    }

    /**
     * Same, plus the ONE address this mail is allowed to turn into a {@code mailto:} link.
     *
     * @param linkableAddress an address the PRODUCT chose (the configured support address), never a
     *                        value a person typed. See {@link #linkifyAddress}.
     */
    public static String html(String logoUrl,
                              String locale,
                              String preheader,
                              String title,
                              List<String> paragraphs,
                              String code,
                              String note,
                              Button button,
                              String signature,
                              Footer footer,
                              String linkableAddress) {
        // Slot order: paragraphs, code, BUTTON, note, signature, footer.
        //
        // Two of those moved, because the first version emitted note and signature before the
        // button and produced mails that read backwards:
        //   - "If the button does not work, copy and paste this link" appeared ABOVE anything
        //     clickable, in the invitation and the reset mail;
        //   - the sign-off appeared above the call to action in all five mails, and in the
        //     verification mail it sat between "use the code below" and the code itself.
        // Both fall out of the slot order rather than from anything a mailer chose, which is why
        // fixing it here fixes it everywhere, and why the ordering is now asserted in the tests.
        StringBuilder body = new StringBuilder();
        if (paragraphs != null) {
            for (String paragraph : paragraphs) {
                if (paragraph == null || paragraph.isBlank()) continue;
                body.append("<p style=\"margin:0 0 10px 0;\">")
                        .append(linkifyAddress(emphasize(escape(paragraph)), linkableAddress))
                        .append("</p>");
            }
        }
        if (code != null && !code.isBlank()) {
            body.append("<div style=\"margin:24px 0;padding:20px;background:#f5f5f4;border:1px solid #e7e5e4;")
                    .append("border-radius:8px;text-align:center;font-size:28px;font-weight:600;")
                    .append("letter-spacing:6px;color:#111827;\">")
                    .append(escape(code))
                    .append("</div>");
        }
        if (button != null && button.url() != null && !button.url().isBlank()) {
            body.append("<p style=\"margin:24px 0 0 0;\"><a href=\"").append(escape(button.url()))
                    .append("\" style=\"display:inline-block;padding:12px 24px;background:#111827;color:#ffffff;")
                    .append("border-radius:8px;font-size:15px;font-weight:600;text-decoration:none;\">")
                    .append(escape(button.label())).append("</a></p>");
        }
        if (note != null && !note.isBlank()) {
            // `word-break:break-word` because the note is where a pasteable URL goes: a ~120
            // character link with no space in it overflows a 560px table otherwise, and the one mail
            // that needs the fallback most is the one whose reader is locked out. Harmless for prose.
            body.append("<p style=\"margin:0;font-size:13px;color:#6b7280;word-break:break-word;\">")
                    .append(escape(note))
                    .append("</p>");
        }
        if (signature != null && !signature.isBlank()) {
            body.append("<p style=\"margin:20px 0 0 0;color:#6b7280;\">")
                    .append(emphasize(escape(signature)))
                    .append("</p>");
        }

        String preheaderHtml = preheader == null || preheader.isBlank() ? "" :
                "<span style=\"display:none!important;font-size:1px;line-height:1px;color:#ffffff;mso-hide:all;\">"
                        + escape(preheader) + "</span>";

        String footerHtml = footer == null ? "" : escape(footer.text());

        // {{BODY}} is substituted LAST, and every other token before it.
        //
        // What actually makes every slot safe is `escape`, which turns `{` into `&#123;` so no token
        // can survive a value at all. The ordering is belt-and-braces on top of it.
        //
        // This used to say "the body is the only part carrying text somebody typed", which stopped
        // being true the moment the invitation mail put an organisation name in the PREHEADER - a
        // slot substituted second, before {{LOGO}}. A maintainer who trusted that sentence and
        // dropped the brace-escaping as redundant would reopen token expansion there, with an
        // attacker-chosen value, in a mail sent to a stranger.
        return """
                <!DOCTYPE html>
                <html lang="{{LANG}}"><head><meta charset="UTF-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>{{TITLE}}</title></head>
                <body style="margin:0;padding:0;">
                {{PREHEADER}}
                <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="background:#f5f5f4;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;color:#111827;">
                  <tr><td align="center" style="padding:40px 16px;">
                    <table role="presentation" width="560" cellpadding="0" cellspacing="0" border="0" style="max-width:560px;width:100%;background:#ffffff;border:1px solid #e7e5e4;border-radius:12px;">
                      <tr><td align="left" style="padding:32px 40px 24px 40px;border-bottom:1px solid #e7e5e4;">
                        <img src="{{LOGO}}" alt="LiveContext" height="32" style="display:block;height:32px;width:auto;border:0;text-decoration:none;">
                      </td></tr>
                      <tr><td style="padding:32px 40px;font-size:15px;line-height:1.6;color:#111827;">
                        <h1 style="margin:0 0 16px 0;font-size:22px;font-weight:600;color:#111827;">{{TITLE}}</h1>
                        {{BODY}}
                      </td></tr>
                      <tr><td style="padding:24px 40px 32px 40px;border-top:1px solid #e7e5e4;font-size:12px;line-height:1.5;color:#6b7280;">
                        {{FOOTER}}<br><br>&copy; LiveContext
                      </td></tr>
                    </table>
                  </td></tr>
                </table>
                </body></html>
                """
                .replace("{{LANG}}", escape(locale == null ? "en" : locale))
                .replace("{{PREHEADER}}", preheaderHtml)
                .replace("{{TITLE}}", escape(title))
                .replace("{{FOOTER}}", footerHtml)
                .replace("{{LOGO}}", escape(logoUrl))
                .replace("{{BODY}}", body.toString());
    }

    /**
     * The plain-text alternative of the same message, in the same order.
     *
     * <p>Not optional politeness: a mail with an HTML part and no text part scores worse with
     * every spam filter, and the text part is what a screen reader and a plain-text client show.
     */
    public static String plain(String title,
                               List<String> paragraphs,
                               String code,
                               String note,
                               Button button,
                               String signature,
                               Footer footer) {
        List<String> blocks = new ArrayList<>();
        if (title != null && !title.isBlank()) blocks.add(unemphasize(title));
        if (paragraphs != null) {
            for (String paragraph : paragraphs) {
                if (paragraph != null && !paragraph.isBlank()) blocks.add(unemphasize(paragraph));
            }
        }
        if (code != null && !code.isBlank()) blocks.add(code);
        // Button BEFORE note, and the signature last: see the order note on `html`.
        if (button != null && button.url() != null && !button.url().isBlank()) {
            blocks.add(button.label() + ": " + button.url());
        }
        if (note != null && !note.isBlank()) blocks.add(note);
        if (signature != null && !signature.isBlank()) blocks.add(unemphasize(signature));
        if (footer != null) {
            blocks.add("-");
            blocks.add(footer.text());
        }
        return String.join("\n\n", blocks);
    }

    /**
     * A subject line: one line, trimmed.
     *
     * <p>A subject is TEXT, not HTML, so it must not be HTML-escaped - an organization called
     * "Ben &amp; Jerry's" would arrive as "Ben &amp;amp; Jerry's" in the inbox list. What it must
     * not carry is a line break: a subject is a mail HEADER, and a value with a newline in it is
     * how a header gets split into two. Every subject that interpolates something a user typed
     * goes through this.
     */
    public static String subject(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\r\\n]+", " ").trim();
    }

    /**
     * Minimal HTML escaping. Every interpolated value goes through it, including our own.
     *
     * <p>It also defuses the shell's own {@code {{TOKEN}}} syntax, by breaking the opening pair.
     * The template is assembled with {@code String.replace}, so a value containing {@code
     * {{LOGO}}} would otherwise be expanded by a later replacement into the logo URL. Ordering the
     * substitutions protects the body; this protects every slot, including the ones that carry no
     * user text today and might tomorrow (an organization name in a heading is the obvious next
     * step). {@code &#123;} is the literal "{", so the reader still sees what they typed.
     */
    public static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
                .replace("{{", "&#123;&#123;");
    }

    /**
     * Turn ONE known address, where it appears in an already-escaped paragraph, into a
     * {@code mailto:} link.
     *
     * <p>Two sentences in the lifecycle mails end in a support address, and before this shell they
     * were hand-written anchors. Moving them into the catalog made them inert text: most clients
     * autolink one, some do not, and the reader of a deactivation notice is being told where to
     * write if the deactivation was not theirs.
     *
     * <p>ONE address, supplied by the caller, and not "every address-shaped run of text".
     *
     * <p>The first version linked anything matching the shape of an address, in every paragraph. The
     * invitation body interpolates an organisation name, an inviter display name and a role, none of
     * which the product controls, and it is delivered to somebody who may not have an account. An
     * org named {@code security@their-bank.example} therefore became a live {@code mailto:} anchor
     * inside a LiveContext-branded message sent to a stranger. Escaping held, so nothing could be
     * injected, but a link the sender chose the text of is a phishing affordance that did not exist
     * before the consolidation - created by a helper whose own javadoc scoped it to "two sentences in
     * the lifecycle mails".
     *
     * <p>Runs AFTER escaping, on text that therefore contains no markup of its own, and the address
     * is escaped before being matched so it cannot carry any either. The plain-text part keeps the
     * bare address, which is what it should show.
     */
    static String linkifyAddress(String escaped, String address) {
        if (address == null || address.isBlank()) return escaped;
        String needle = escape(address.trim());
        if (!escaped.contains(needle)) return escaped;
        // Link-coloured and underlined, as the address was before the shell: #111827 is the body text
        // colour set on the enclosing cell, so styling it that way made the anchor indistinguishable
        // from the sentence, which defeats the point of linking it.
        return escaped.replace(needle,
                "<a href=\"mailto:" + needle + "\" style=\"color:#2563eb;text-decoration:underline;\">"
                        + needle + "</a>");
    }

    /**
     * Turn {@code *bold*} into {@code <strong>bold</strong>}, so a translated sentence can
     * emphasize a name or an organization the way the hand-written invitation mail did.
     *
     * <p>Applied strictly AFTER {@link #escape}, which is what makes it safe: by then any markup
     * that came from user input is already inert text, and the only tags this can produce are the
     * {@code <strong>} pair it writes itself. The worst a hostile organization name can do is
     * render itself in bold.
     *
     * <p>Unbalanced markers are left alone rather than half-converted: a lone asterisk in a name
     * must not open a tag that never closes.
     *
     * <p>This block sat ABOVE the linkifier for a while, where javadoc keeps only the last comment
     * before a declaration - so the argument for why post-escape emphasis is injection-proof was
     * attached to nothing, in the file that explains that hazard twice.
     */
    static String emphasize(String escaped) {
        if (escaped == null || escaped.indexOf('*') < 0) return escaped;
        return escaped.replaceAll("\\*([^*]+)\\*", "<strong>$1</strong>");
    }

    /** The plain-text reading of the same sentence: the markers simply go away. */
    static String unemphasize(String text) {
        if (text == null || text.indexOf('*') < 0) return text;
        return text.replaceAll("\\*([^*]+)\\*", "$1");
    }

    /**
     * A value to interpolate into a translated sentence, with the emphasis marker removed.
     *
     * <p>The stars belong to the CATALOG, which decides what a sentence emphasizes; a value that
     * carries one of its own shifts every boundary after it. An organization called
     * {@code *Star* Labs} turned "*{inviter}* invited you to join *{org}*" into a sentence with
     * the bold running through the wrong words and a stray star at the end. Nothing unsafe - the
     * tags stay balanced and escaping still happens first - just visibly broken, for a name its
     * owner is entitled to choose.
     */
    public static String plainValue(String value) {
        String stripped = value == null ? "" : value.replace("*", "");
        // A value that ends up EMPTY becomes a single space, and that is not cosmetic.
        //
        // The sentences these values land in carry their own markers: `invite.body` is
        // "*{inviter}* invited you to join the organization *{org}* ... as *{role}*". An empty value
        // therefore produces `**`, and an empty pair has no character between its markers, so the
        // pairing pattern skips it and pairs its SECOND marker with the opening one of the NEXT span:
        // the emphasis slides off the value and onto the prose, and a stray marker is left at each
        // end. Reachable with an organisation named "*", and with a blank one - the mailer maps a
        // null name to "".
        //
        // Fixed HERE rather than in `emphasize`, which is where it was tried first: deleting `**`
        // there repaired the HTML half and not the text half (whose pairing runs in `unemphasize`),
        // and it silently deleted a `**` a person had typed into their own display name - which
        // `AccountMailCatalog.greeting` deliberately does not do, for the reason stated there. One
        // space keeps every marker balanced without touching anything anybody typed.
        return stripped.isEmpty() ? " " : stripped;
    }
}
