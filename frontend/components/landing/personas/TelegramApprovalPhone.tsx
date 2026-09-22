'use client';

import { useEffect, useMemo, useRef } from 'react';
import dynamic from 'next/dynamic';
import Image from 'next/image';
import { useLocale, useTranslations } from 'next-intl';
import { BatteryFull, Camera, Check, CheckCheck, ChevronLeft, FileText, Mic, Plus, Signal, Smile, Wifi } from 'lucide-react';
import { BrandMark } from '@/components/integrations/BrandMark';
import { useLandingTheme } from '@/components/landing/LandingThemeProvider';
import { buildPersonaInterfaceHtml } from './personaInterfaceHtml';
import { CREATOR_EXAMPLES, BUSINESS_EXAMPLE_KEYS, BUSINESS_PREVIEW_VIEWPORT, type CreatorExampleKey, type BusinessExampleKey, type PersonaKey } from './personas';

const InterfaceThumbnail = dynamic(
  () => import('@/app/workflows/builder/components/interface/InterfaceThumbnail').then((m) => m.InterfaceThumbnail),
  { ssr: false },
);

/**
 * The attached file's name, from the subject the message is about.
 *
 * <p>The subject is TRANSLATED, so the separator class has to be "not a letter and not a
 * number" in any script, not `[^a-z0-9]`. That first spelling folded the accents (which is
 * still wanted: Resilier, not Résilier) and then deleted every non-Latin character, which
 * on /zh left the digits and nothing else: the three recruiting examples all came out as
 * the same file, and the three marketing ones as "northstar.pdf" three times over. A
 * document named after another document is worse than an unnamed one.
 *
 * <p>`fallback` is the example's own key, used only if a subject carries no letter and no
 * digit at all. It is a product handle rather than a word, so it cannot show one locale's
 * language to another's reader.
 *
 * <p>The trim runs AFTER the 38-character cut, not before: cutting a long subject can land
 * on a separator, and "operations-specialist-application-.pdf" is the one shape a name
 * derived from prose still gets wrong.
 */
function documentName(subject: string, fallback: string) {
  const slug = subject.normalize('NFD').replace(/[̀-ͯ]/g, '')
    .toLowerCase().replace(/[^\p{L}\p{N}]+/gu, '-').slice(0, 38).replace(/^-|-$/g, '');
  return `${slug || fallback}.pdf`;
}

export default function TelegramApprovalPhone({ persona, phase, onApprove, creatorExample = 'product', businessExample: selectedBusinessExample }: {
  persona: PersonaKey;
  phase: 'waiting' | 'tapping' | 'approved';
  onApprove?: () => void;
  creatorExample?: CreatorExampleKey;
  businessExample?: BusinessExampleKey;
}) {
  const t = useTranslations(`PersonaLanding.personas.${persona}.workflowShowcase`);
  const locale = useLocale();
  const { theme } = useLandingTheme();
  const businessExample = selectedBusinessExample ?? (persona === 'creator' ? 'reply' : BUSINESS_EXAMPLE_KEYS[persona][0]);
  const phoneTitle = t(persona !== 'creator' ? `examples.${businessExample}.phoneTitle` : 'phoneTitle');
  const approved = phase === 'approved';
  const media = CREATOR_EXAMPLES[creatorExample];
  // The attached screen is the persona's own, built from the same HTML the hero's interface
  // node renders. Creator attaches its post instead, so this is skipped there.
  const screenHtml = useMemo(
    () => (persona === 'creator' ? null : buildPersonaInterfaceHtml(persona, theme, t as never, 'product', businessExample)),
    [persona, theme, t, businessExample],
  );
  const chatRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if ((approved || phase === 'tapping') && chatRef.current) chatRef.current.scrollTop = chatRef.current.scrollHeight;
  }, [approved, phase]);
  const time = new Intl.DateTimeFormat(locale, { hour: '2-digit', minute: '2-digit', timeZone: 'UTC' }).format(new Date('2026-01-01T09:41:00Z'));

  return (
    <div className="telegram-phone" data-device="iphone" data-business={persona !== 'creator'} data-theme={theme} data-phase={phase} data-testid="telegram-approval-phone" aria-label={phoneTitle}>
      <style>{phoneStyles}</style>
      <div className="telegram-phone-side telegram-phone-side-left" aria-hidden="true" />
      <div className="telegram-phone-action-switch" aria-hidden="true" />
      <div className="telegram-phone-side telegram-phone-side-right" aria-hidden="true" />
      <div className="telegram-phone-screen">
        <div className="telegram-phone-status text-xs" aria-hidden="true">
          <span className="font-semibold">{time}</span>
          <span className="telegram-phone-island"><i /></span>
          <span className="flex items-center gap-1"><Signal className="h-3 w-3" /><Wifi className="h-3 w-3" /><BatteryFull className="h-3 w-3" /></span>
        </div>
        <div className="telegram-phone-header">
          <ChevronLeft className="h-6 w-6 shrink-0 telegram-ios-back" aria-hidden="true" />
          <div className="min-w-0 flex-1 text-center"><p className="text-sm font-semibold">LiveContext</p><p className="text-xs opacity-70">Telegram</p></div>
          <div className="telegram-phone-avatar"><BrandMark iconSlug="telegram" size={30} /></div>
        </div>
        <div ref={chatRef} className="telegram-phone-chat">
          <div className="telegram-message telegram-message-incoming">
            <p className="text-sm font-semibold mb-2">{phoneTitle}</p>
            {persona === 'creator' ? <div className="telegram-media-attachment" data-testid="creator-story-attachment">
              <Image unoptimized src={media.poster ?? media.src} width={media.width} height={media.height} alt={t(`examples.${creatorExample}.title`)} />
              <div><p className="text-sm leading-relaxed">{t(`examples.${creatorExample}.phoneMessage`)}</p><p className="text-xs opacity-60 mt-3">{media.kind === 'video' ? 'MP4' : 'PNG'}</p></div>
            </div> : <>
              <p className="text-sm leading-snug">{t(`examples.${businessExample}.phoneMessage`)}</p>
              {/* What the run produced, attached to the message the way creator attaches its
                  post: the screen the workflow generated, rendered from its own HTML rather
                  than drawn as a stand-in, and named like the file that carries it. */}
              <div className="telegram-document mt-2" data-testid="business-document-attachment">
                <span className="telegram-document-preview">
                  {screenHtml && <InterfaceThumbnail htmlTemplate={screenHtml} viewport={BUSINESS_PREVIEW_VIEWPORT} fit="width" dropJs />}
                </span>
                <span className="min-w-0">
                  <span className="block truncate text-sm font-semibold">{documentName(t(`examples.${businessExample}.subject`), businessExample)}</span>
                  <span className="mt-0.5 flex items-center gap-1 text-xs opacity-60"><FileText className="h-3 w-3 shrink-0" aria-hidden="true" />PDF</span>
                </span>
              </div>
              <div className="telegram-attachment mt-2 space-y-1 break-words" data-testid="business-summary-attachment">
                <p className="text-sm font-semibold">{t(`examples.${businessExample}.subject`)}</p>
                <p className="text-sm leading-snug">{t(`examples.${businessExample}.contextValue`)}</p>
              </div>
            </>}
            <div className="text-xs text-right opacity-50 mt-2">{time}</div>
          </div>
          <button type="button" onClick={onApprove} disabled={approved || !onApprove} className="telegram-approve text-sm font-semibold" aria-pressed={approved}>
            <Check className="h-3.5 w-3.5" aria-hidden="true" />{approved ? t('approvedStatus') : t(persona !== 'creator' ? `examples.${businessExample}.actionLabel` : 'approveAction')}
            {phase === 'tapping' && <span className="telegram-tap" aria-hidden="true" />}
          </button>
          <div className="telegram-message telegram-message-outgoing text-sm" data-visible={approved} aria-hidden={!approved}>
            <p>{t('approvedStatus')}</p>
            <span className="flex items-center justify-end gap-1 mt-1 text-xs opacity-70">{time}<CheckCheck className="h-3 w-3" aria-hidden="true" /></span>
          </div>
        </div>
        <div className="telegram-phone-composer" aria-hidden="true"><Plus className="h-5 w-5" /><div className="telegram-phone-input"><span /><Smile className="h-4 w-4" /><Camera className="h-4 w-4" /></div><Mic className="h-4 w-4" /></div>
        <div className="telegram-phone-home" aria-hidden="true"><span /></div>
      </div>
    </div>
  );
}

const phoneStyles = `
  .telegram-phone{--tg-surface:#fff;--tg-text:#162632;--tg-wall:#dce6d3;--tg-bubble:#fff;--tg-sent:#e2ffc7;--tg-line:#d7dfdf;position:relative;width:min(286px,calc(100vw - 64px));padding:7px;border-radius:45px;background:linear-gradient(105deg,#a6a39b,#555650 4%,#dad8d1 5%,#555650 6%,#777870 94%,#dedbd4 95%,#555650 96%,#a6a39b);box-shadow:0 32px 70px #0005,0 6px 14px #0003,inset 0 0 0 1px #ece9dd,inset 0 0 0 3px #63645f;isolation:isolate}
  .telegram-phone[data-theme=dark]{--tg-surface:#1f2b36;--tg-text:#f1f5f8;--tg-wall:#13232a;--tg-bubble:#233340;--tg-sent:#315b50;--tg-line:#344550}
  .telegram-phone-screen{position:relative;border-radius:38px;overflow:hidden;background:var(--tg-surface);color:var(--tg-text);border:1px solid #000;height:500px;display:flex;flex-direction:column}
  .telegram-phone-status{height:39px;flex-shrink:0;padding:8px 16px 4px;display:flex;align-items:center;justify-content:space-between;position:relative}
  .telegram-phone-island{position:absolute;top:8px;left:50%;transform:translateX(-50%);width:88px;height:25px;border-radius:18px;background:#080a0c;box-shadow:inset 0 0 1px #60656b}
  .telegram-phone-island i{position:absolute;right:9px;top:9px;width:7px;height:7px;border-radius:50%;background:radial-gradient(circle at 35% 30%,#31486c,#080d16 70%)}
  .telegram-phone-header{display:flex;flex-shrink:0;align-items:center;gap:9px;padding:7px 13px;border-bottom:1px solid var(--tg-line)}
  .telegram-phone-avatar{display:flex;align-items:center;justify-content:center;width:38px;height:38px;border-radius:50%;background:#e5f4fc;flex-shrink:0}
  .telegram-phone-chat{flex:1;min-height:0;overflow-y:auto;scrollbar-width:none;padding:10px 10px 12px;background-color:var(--tg-wall);background-image:radial-gradient(ellipse at 20% 20%,transparent 28%,#668b7520 30%,transparent 33%),radial-gradient(ellipse at 85% 70%,transparent 26%,#668b751c 28%,transparent 31%);background-size:54px 72px,78px 64px}
  .telegram-phone[data-business=true] .telegram-phone-chat{display:flex;flex-direction:column}
  .telegram-phone[data-business=true] .telegram-message-incoming{min-height:0;overflow-y:auto;scrollbar-width:none}
  .telegram-phone[data-business=true] .telegram-approve,.telegram-phone[data-business=true] .telegram-message-outgoing{flex-shrink:0}
  .telegram-message{position:relative;padding:10px;border-radius:15px;box-shadow:0 1px 2px #00000012}
  .telegram-message-incoming{background:var(--tg-bubble);border-bottom-left-radius:5px}
  .telegram-attachment{border-left:3px solid #3390ec;background:#3390ec0b;padding:8px 9px;border-radius:5px}
  /* The document row: a page thumbnail and the file it stands for, the shape Telegram gives
     an attached PDF. The preview is the generated screen itself, cropped to a page. */
  .telegram-document{display:grid;grid-template-columns:58px minmax(0,1fr);gap:9px;align-items:center}
  .telegram-document-preview{display:block;width:58px;height:74px;overflow:hidden;border-radius:6px;border:1px solid #0000001f;background:#fff}
  /* The thumbnail is drawn at the card's width (fit="width"), so a 1020x1080 screen lands
     58x61 inside the 74px box and a few pixels of the card's own white show underneath.
     The box keeps a FIXED height so the row is the shape Telegram gives an attached PDF
     whatever it contains, rather than following the screen: all five business personas pass
     the same 1020x1080 viewport today, so nothing varies yet, but the row should not start
     resizing the message the day one of them does. (A child height:100% rule lived here and
     did nothing: InterfaceThumbnail sets its height inline, which beats a stylesheet. No
     backticks in this comment: it sits inside a template literal and one would close it.) */
  .telegram-attachment svg{color:#3390ec}
  .telegram-media-attachment{display:grid;grid-template-columns:112px minmax(0,1fr);gap:10px;align-items:start}
  .telegram-media-attachment img{display:block;width:112px;height:auto;aspect-ratio:9/16;object-fit:cover;border-radius:9px}
  @media(max-width:350px){.telegram-media-attachment{grid-template-columns:96px minmax(0,1fr);gap:8px}.telegram-media-attachment img{width:96px}}
  .telegram-approve{width:100%;position:relative;display:flex;align-items:center;justify-content:center;gap:7px;margin-top:5px;padding:12px 8px;border-radius:7px;background:#3390ec;color:white;box-shadow:0 2px 5px #1c5e9520;transition:transform .22s,background .22s;cursor:pointer}
  .telegram-approve:disabled{cursor:default;opacity:1}
  .telegram-phone[data-phase=tapping] .telegram-approve{transform:scale(.97);background:#217dd2}
  .telegram-phone[data-phase=approved] .telegram-approve{background:#24856b}
  .telegram-tap{position:absolute;width:32px;height:32px;right:28%;top:4px;border-radius:50%;background:#ffffff40;border:2px solid #ffffffb3;box-shadow:0 0 0 9px #ffffff20;animation:telegram-tap-pulse .7s ease-out infinite}
  .telegram-message-outgoing{display:none;margin-top:8px;margin-left:35px;background:var(--tg-sent);border-bottom-right-radius:4px}
  .telegram-message-outgoing[data-visible=true]{display:block;animation:telegram-message-in .35s ease both}
  .telegram-phone-composer{display:flex;flex-shrink:0;align-items:center;gap:10px;padding:7px 12px;color:#8295a1}
  .telegram-phone-input{height:32px;display:flex;align-items:center;justify-content:space-between;flex:1;border:1px solid var(--tg-line);border-radius:20px;padding:0 9px}
  .telegram-phone-input span{height:12px;width:1px;background:#3390ec;opacity:.7}
  .telegram-phone-home{height:14px;flex-shrink:0;display:flex;justify-content:center;align-items:center}
  .telegram-phone-home span{width:88px;height:4px;border-radius:4px;background:var(--tg-text);opacity:.85}
  .telegram-ios-back{color:#3390ec}.telegram-phone-input>span{margin-right:auto}.telegram-phone-input{gap:8px}.telegram-phone-side{position:absolute;width:3px;border-radius:2px;background:linear-gradient(90deg,#666760,#c9c7be,#666760)}
  .telegram-phone-side-left{left:-2px;top:117px;height:34px;box-shadow:0 46px #85867e}.telegram-phone-action-switch{position:absolute;left:-2px;top:78px;width:3px;height:19px;border-radius:2px;background:#96978e}
  .telegram-phone-side-right{right:-2px;top:155px;height:66px}
  @keyframes telegram-tap-pulse{0%{opacity:0;transform:scale(.65)}45%{opacity:1}100%{opacity:0;transform:scale(1.2)}}
  @keyframes telegram-message-in{from{opacity:0;transform:translateY(8px)}to{opacity:1;transform:translateY(0)}}
  @media(prefers-reduced-motion:reduce){.telegram-phone *{animation:none!important;transition:none!important}}
`;
