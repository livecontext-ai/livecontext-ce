// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import { fixtureTranslations } from './fixtureTranslations';
import { afterEach, expect, it, vi } from 'vitest';
import fr from '@/messages/fr.json';
import zh from '@/messages/zh.json';
import TelegramApprovalPhone from '../TelegramApprovalPhone';
import { CREATOR_EXAMPLE_KEYS, CREATOR_EXAMPLES, BUSINESS_EXAMPLE_KEYS, type BusinessPersona } from '../personas';

vi.mock('@/components/landing/LandingThemeProvider', () => ({ useLandingTheme: () => ({ theme: 'dark' }) }));
// The attached preview renders the screen inside an iframe, which jsdom never loads, so the
// thumbnail is stubbed to expose the HTML it was handed. Without this the only thing a test
// can see is the empty wrapper the phone paints around it, which is there either way.
vi.mock('@/app/workflows/builder/components/interface/InterfaceThumbnail', () => ({
  InterfaceThumbnail: ({ htmlTemplate }: { htmlTemplate: string }) =>
    <span data-testid="attached-screen" data-html={htmlTemplate} />,
}));
afterEach(cleanup);

it('keeps local approval confirmation visible in the compact phone and disables the confirmed action', () => {
  const approve = vi.fn();
  const copy = fr.PersonaLanding.personas.creator.workflowShowcase;
  const view = render(<NextIntlClientProvider locale="fr" messages={fr}><TelegramApprovalPhone persona="creator" phase="waiting" onApprove={approve} /></NextIntlClientProvider>);
  fireEvent.click(screen.getByRole('button', { name: copy.approveAction }));
  expect(approve).toHaveBeenCalledOnce();
  expect(screen.getByText(copy.examples.product.phoneMessage)).toBeInTheDocument();
  expect(screen.getByTestId('telegram-approval-phone')).toHaveAttribute('data-theme', 'dark');
  expect(screen.getByTestId('telegram-approval-phone')).toHaveAttribute('data-device', 'iphone');
  expect(screen.getByTestId('creator-story-attachment').querySelector('img')).toHaveAttribute('src', CREATOR_EXAMPLES.product.src);
  const chat = view.container.querySelector('.telegram-phone-chat') as HTMLDivElement;
  Object.defineProperty(chat, 'scrollHeight', { value: 640 });
  view.rerender(<NextIntlClientProvider locale="fr" messages={fr}><TelegramApprovalPhone persona="creator" phase="approved" onApprove={approve} /></NextIntlClientProvider>);
  const confirmed = screen.getByRole('button', { name: copy.approvedStatus });
  expect(chat.scrollTop).toBe(640);
  expect(confirmed).toBeDisabled();
  fireEvent.click(confirmed);
  expect(approve).toHaveBeenCalledOnce();
});

it.each(CREATOR_EXAMPLE_KEYS)('shows the selected %s media and translated message', (example) => {
  const media = CREATOR_EXAMPLES[example];
  const copy = fr.PersonaLanding.personas.creator.workflowShowcase.examples[example];
  render(<NextIntlClientProvider locale="fr" messages={fr}><TelegramApprovalPhone persona="creator" creatorExample={example} phase="waiting" /></NextIntlClientProvider>);
  expect(screen.getByTestId('creator-story-attachment').querySelector('img')).toHaveAttribute('src', media.poster ?? media.src);
  expect(screen.getByTestId('telegram-approval-phone')).toHaveAttribute('data-business','false');
  expect(screen.getByRole('img', { name: copy.title })).toBeInTheDocument();
  expect(screen.getByText(copy.phoneMessage)).toBeInTheDocument();
  expect(screen.getByText(media.kind === 'video' ? 'MP4' : 'PNG')).toBeInTheDocument();
});

/**
 * A business approval carries the thing that was produced, not only a description of it.
 *
 * <p>Creator attaches its post; the other five attached nothing, so the message asked for a
 * decision about a screen the reader could not see. The attachment is the run's OWN screen,
 * built from the same HTML the hero's interface node renders, named like the file that
 * carries it out to Telegram.
 */
it('attaches the generated screen as a document, named after the subject', async () => {
  render(<NextIntlClientProvider locale="fr" messages={fr}><TelegramApprovalPhone persona="support" businessExample="refund" phase="waiting" /></NextIntlClientProvider>);
  const card = screen.getByTestId('business-document-attachment');
  // The name is derived from the French subject "Resilier l'abonnement - #2087": accents
  // folded, the typographic apostrophe and the hash turned into separators, one extension.
  // Asserted whole rather than as a shape, because /^[a-z0-9-]+\.pdf/ also passes for the
  // "document.pdf" the deriver returns when it is handed nothing, which is the failure this
  // test exists to catch.
  expect(card).toHaveTextContent('resilier-l-abonnement-2087.pdf');
  // And the preview is this persona's own generated screen, not a stand-in: the layout
  // marker is written by the screen builder from the persona and the example it was asked
  // for. next/dynamic resolves a tick after mount, hence the await.
  const attached = await screen.findByTestId('attached-screen');
  expect(attached.getAttribute('data-html')).toContain('data-layout="support-refund"');
});

it('names the attachment after the example on screen, not after the persona', async () => {
  // The name tracks the SUBJECT, so switching example switches the file. A deriver that
  // read the wrong example, or fell back to a constant, would still pass the test above.
  render(<NextIntlClientProvider locale="fr" messages={fr}><TelegramApprovalPhone persona="sales" businessExample="quote" phase="waiting" /></NextIntlClientProvider>);
  // Same subject, different persona and example: "Axion Studio - Devis AX-024".
  expect(screen.getByTestId('business-document-attachment')).toHaveTextContent('axion-studio-devis-ax-024.pdf');
  expect((await screen.findByTestId('attached-screen')).getAttribute('data-html')).toContain('data-layout="sales-quote"');
});

/**
 * The subject the name is built from is TRANSLATED, and most of the world does not write in
 * the Latin alphabet. Folding it through [^a-z0-9] kept the digits and threw the rest away:
 * on /zh the three recruiting examples all attached the same file and the three marketing
 * ones all attached "northstar.pdf". This runs the three examples a persona really offers
 * and asserts they are three different names, which is the property the reader needs.
 */
it.each(['marketing', 'recruiting'] as const)('gives each %s example its own name on a non-Latin locale', (persona) => {
  const names = BUSINESS_EXAMPLE_KEYS[persona].map((example) => {
    render(<NextIntlClientProvider locale="zh" messages={zh}><TelegramApprovalPhone persona={persona} businessExample={example} phase="waiting" /></NextIntlClientProvider>);
    const name = screen.getByTestId('business-document-attachment').textContent!.replace(/PDF$/, '');
    cleanup();
    return name;
  });
  expect(new Set(names).size, names.join(' / ')).toBe(names.length);
  for (const name of names) {
    // Not the fallback, and not a bare number: the subject has to survive into the name.
    expect(name, names.join(' / ')).toMatch(/^[^.]*[^\d.][^.]*\.pdf$/);
    expect(BUSINESS_EXAMPLE_KEYS[persona].map((example) => `${example}.pdf`)).not.toContain(name);
  }
});

it('keeps creator on its own attachment, which is the post itself', () => {
  render(<NextIntlClientProvider locale="fr" messages={fr}><TelegramApprovalPhone persona="creator" phase="waiting" /></NextIntlClientProvider>);
  expect(screen.getByTestId('creator-story-attachment')).toBeInTheDocument();
  expect(screen.queryByTestId('business-document-attachment')).not.toBeInTheDocument();
});

it.each(Object.entries(BUSINESS_EXAMPLE_KEYS).flatMap(([persona, examples]) => examples.map(example => ({ persona: persona as BusinessPersona, example }))))('shows $persona $example context and selected approval without an image', ({ persona, example }) => {
 const translate = fixtureTranslations('fr', fr.PersonaLanding.personas[persona].workflowShowcase);
 const t = (key: string) => translate(`examples.${example}.${key}`);
 const approve = vi.fn();
 render(<NextIntlClientProvider locale="fr" messages={fr} onError={error => { throw error; }}><TelegramApprovalPhone persona={persona} businessExample={example} phase="waiting" onApprove={approve} /></NextIntlClientProvider>);
 expect(screen.getByTestId('telegram-approval-phone')).toHaveAttribute('aria-label',t('phoneTitle'));
 expect(screen.getByText(t('phoneMessage'))).toBeInTheDocument();
 const attachment = screen.getByTestId('business-summary-attachment');
 expect(attachment).toHaveTextContent(t('subject'));
 expect(attachment).toHaveTextContent(t('contextValue'));
 expect(attachment.querySelector('img')).toBeNull();
 expect(attachment).not.toHaveTextContent(t('actionLabel'));
 expect(screen.getAllByText(t('actionLabel'))).toHaveLength(1);
 expect(screen.getByTestId('telegram-approval-phone')).toHaveAttribute('data-business','true');
 fireEvent.click(screen.getByRole('button', { name: t('actionLabel') }));
 expect(approve).toHaveBeenCalledOnce();
});

it.each(Object.entries(BUSINESS_EXAMPLE_KEYS))('defaults the %s phone to its first scenario', (key, examples) => {
 const persona = key as BusinessPersona;
 const translate = fixtureTranslations('fr', fr.PersonaLanding.personas[persona].workflowShowcase);
 const t = (key: string) => translate(`examples.${examples[0]}.${key}`);
 render(<NextIntlClientProvider locale="fr" messages={fr} onError={error => { throw error; }}><TelegramApprovalPhone persona={persona} phase="waiting" /></NextIntlClientProvider>);
 expect(screen.getByText(t('phoneMessage'))).toBeInTheDocument();
 expect(screen.getByTestId('business-summary-attachment')).toHaveTextContent(t('subject'));
});
