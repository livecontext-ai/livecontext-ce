import { CE_ACCESS_TOKEN_KEY, OIDC_USER_KEY_PREFIX } from '@/lib/auth/sessionKeys';
import { SITE_SESSION_HINT_COOKIE } from '@/lib/auth/siteSessionHint';

/**
 * Whether this browser holds a stored session, known before the first paint.
 *
 * <p>The public pages are rendered without a session (the same cached HTML for everyone), so
 * the server always sends the visitor end of the header ("Sign in", "Get started"), and the
 * account is only resolved once the scripts run. {@link SESSION_HINT_SCRIPT} runs inline, in
 * the header, before that end is parsed: when the browser holds a session (a cloud OIDC user,
 * or a self-hosted install's token, in this origin's storage; or, on the docs subdomain, the
 * app's signed-in cookie) it marks the document with {@link SESSION_HINT_ATTR}, and the visitor
 * end stays invisible until the account is resolved, instead of flashing "Get started" at
 * someone who already has an account.
 *
 * <p>A hint, not a proof: a stored session can be over. The header still decides from the
 * resolved account, and shows the visitor end once it knows there is none.
 *
 * <p>Not a client module on purpose: the server-rendered header inlines the script, and a
 * value exported from a `'use client'` file reaches a server component as a reference, not a
 * string.
 */
export const SESSION_HINT_ATTR = 'data-lc-session';

export const SESSION_HINT_SCRIPT =
  `(function(){var h=document.documentElement;`
  + `try{var s=window.localStorage;for(var i=0;i<s.length;i++){var k=s.key(i)||'';`
  + `if((k.indexOf('${OIDC_USER_KEY_PREFIX}')===0||k==='${CE_ACCESS_TOKEN_KEY}')&&s.getItem(k)){`
  + `h.setAttribute('${SESSION_HINT_ATTR}','');return}}}catch(e){}`
  + `try{if((' '+document.cookie).indexOf(' ${SITE_SESSION_HINT_COOKIE}=')>=0)h.setAttribute('${SESSION_HINT_ATTR}','')}catch(e){}`
  + `})()`;
