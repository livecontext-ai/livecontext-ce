// @vitest-environment jsdom
import { describe, expect, it, vi } from 'vitest';
import { fixtureTranslations } from './fixtureTranslations';
import en from '@/messages/en.json';
import fr from '@/messages/fr.json';
import de from '@/messages/de.json';
import es from '@/messages/es.json';
import pt from '@/messages/pt.json';
import zh from '@/messages/zh.json';
import { buildPersonaInterfaceHtml } from '../personaInterfaceHtml';
import { CREATOR_EXAMPLES, CREATOR_EXAMPLE_KEYS, BUSINESS_EXAMPLE_KEYS, PERSONA_KEYS } from '../personas';

const messages = { en, fr, de, es, pt, zh };
it.each(CREATOR_EXAMPLE_KEYS)('renders selected %s media and localized metadata in six locales', (example) => {
  for (const [locale, catalog] of Object.entries(messages)) {
    const t = fixtureTranslations(locale, catalog.PersonaLanding.personas.creator.workflowShowcase);
    const doc = new DOMParser().parseFromString(buildPersonaInterfaceHtml('creator', 'light', t, example), 'text/html');
    expect(doc.querySelector('main')?.dataset.example).toBe(example);
    expect(doc.querySelector('img,video')?.getAttribute('src')).toBe(CREATOR_EXAMPLES[example].src);
    expect(doc.querySelector('.creator-location')?.textContent).toBe(t(`examples.${example}.location`));
    expect(doc.querySelector('.creator-description')?.textContent).toBe(t(`examples.${example}.description`));
    expect(doc.querySelectorAll('script')).toHaveLength(CREATOR_EXAMPLES[example].kind === 'video' ? 1 : 0);
    if (CREATOR_EXAMPLES[example].kind === 'video') {
      const video = doc.querySelector('video')!;
      for (const attribute of ['autoplay', 'muted', 'playsinline', 'loop']) expect(video.hasAttribute(attribute)).toBe(true);
      expect(video.preload).toBe('none');
      expect(doc.querySelectorAll('[data-caption]')).toHaveLength(3);
    }
    if (example === 'cuts') {
      for (const [selector, key] of [['.creator-timeline h2', 'timelineTitle'], ['.timeline-labels span', 'originalLabel'], ['.timeline-labels strong', 'editedLabel']] as const) {
        expect(doc.querySelector(selector)?.textContent).toBe(t(`examples.cuts.${key}`));
      }
      expect(doc.querySelectorAll('.timeline-track .kept')).toHaveLength(3);
      expect(doc.querySelectorAll('.timeline-track .removed')).toHaveLength(2);
    }
  }
});

it('cuts dead portions, highlights retained segments and keeps reduced-motion playback static', () => {
  const doc = new DOMParser().parseFromString(buildPersonaInterfaceHtml('creator', 'dark', key => key, 'cuts'), 'text/html');
  const video = doc.querySelector('video')!;
  const play = vi.fn().mockResolvedValue(undefined);
  const pause = vi.fn();
  Object.defineProperties(video, { play: { value: play }, pause: { value: pause } });
  const motion = { matches: false, addEventListener: vi.fn() };
  new Function('document', 'window', doc.querySelector('script')!.textContent!)(doc, { matchMedia: () => motion });
  for (const [time, expected, segment] of [[2, 2, 0], [3, 6, 1], [8, 8, 1], [9, 13, 2], [14, 14, 2], [15, 0, 0]]) {
    video.currentTime = time;
    video.dispatchEvent(new Event('timeupdate'));
    expect(video.currentTime).toBe(expected);
    expect(doc.querySelector('[data-segment][data-active=true]')?.getAttribute('data-segment')).toBe(String(segment));
    expect(doc.querySelector('[data-caption]:not([hidden])')?.getAttribute('data-caption')).toBe(String(segment));
  }
  Object.defineProperty(video, 'seeking', { value: true, configurable: true });
  video.currentTime = 5;
  video.dispatchEvent(new Event('timeupdate'));
  expect(video.currentTime).toBe(5);
  Object.defineProperty(video, 'seeking', { value: false });
  video.dispatchEvent(new Event('seeked'));
  expect(video.currentTime).toBe(6);
  motion.matches = true;
  video.currentTime = 4;
  motion.addEventListener.mock.calls[0][1]();
  video.dispatchEvent(new Event('timeupdate'));
  expect(video.currentTime).toBe(4);
  expect(pause).toHaveBeenCalledOnce();
  expect(video.autoplay).toBe(false);
});

it('escapes every cuts annotation without altering its fixed script', () => {
  const hostile = '</script><img src=x onerror="alert(1)">';
  const doc = new DOMParser().parseFromString(buildPersonaInterfaceHtml('creator', 'light', () => hostile, 'cuts'), 'text/html');
  expect(doc.querySelectorAll('script')).toHaveLength(1);
  expect(doc.querySelector('script')?.textContent).not.toContain(hostile);
  expect(doc.querySelector('[onerror],img[src="x"]')).toBeNull();
  expect(doc.querySelector('.creator-timeline')?.getAttribute('aria-label')).toBe(hostile);
  for (const selector of ['.creator-timeline h2', '.timeline-labels span', '.timeline-labels strong', '[data-caption]']) {
    expect(doc.querySelector(selector)?.textContent).toBe(hostile);
  }
});

it('synchronizes captions to video time and pauses for reduced motion', () => {
  const doc = new DOMParser().parseFromString(buildPersonaInterfaceHtml('creator', 'dark', key => key, 'reel'), 'text/html');
  const video = doc.querySelector('video')!;
  const play = vi.fn().mockResolvedValue(undefined);
  const pause = vi.fn();
  Object.defineProperties(video, { play: { value: play }, pause: { value: pause } });
  const media = { matches: false, addEventListener: vi.fn() };
  new Function('document', 'window', doc.querySelector('script')!.textContent!)(doc, { matchMedia: () => media });
  expect(play).toHaveBeenCalledOnce();
  for (const [time, expected] of [[0, 0], [5, 1], [9, 2], [15, 2], [0, 0]]) {
    video.currentTime = time;
    video.dispatchEvent(new Event('timeupdate'));
    expect(doc.querySelector('[data-caption]:not([hidden])')?.getAttribute('data-caption')).toBe(String(expected));
  }
  media.matches = true;
  media.addEventListener.mock.calls[0][1]();
  expect(pause).toHaveBeenCalledOnce();
  expect(video.autoplay).toBe(false);
});

it('keeps hostile reel copy out of the fixed playback script', () => {
  const hostile = '</script><img src=x onerror="alert(1)">';
  const doc = new DOMParser().parseFromString(buildPersonaInterfaceHtml('creator', 'light', () => hostile, 'reel'), 'text/html');
  expect(doc.querySelectorAll('script')).toHaveLength(1);
  expect(doc.querySelector('script')?.textContent).not.toContain(hostile);
  expect(doc.querySelector('[onerror],img[src="x"]')).toBeNull();
  expect(doc.querySelector('[data-caption]')?.textContent).toBe(hostile);
});
const layouts = {
  ops: 'ops-report', creator: 'creator-story', support: 'support-reply', sales: 'sales-quote',
  marketing: 'marketing-campaign', recruiting: 'recruiting-shortlist',
};

describe('persona interface HTML', () => {
  it.each(Object.keys(messages) as (keyof typeof messages)[])('renders one distinct translated app composition per persona in %s', (locale) => {
    for (const persona of PERSONA_KEYS) {
      const t = fixtureTranslations(locale, messages[locale].PersonaLanding.personas[persona].workflowShowcase);
      for (const theme of ['light', 'dark'] as const) {
        const doc = new DOMParser().parseFromString(buildPersonaInterfaceHtml(persona, theme, t), 'text/html');
        expect(doc.documentElement.dataset.theme).toBe(theme);
        expect(doc.querySelector('main')?.getAttribute('data-layout')).toBe(layouts[persona]);
        if (persona === 'creator') {
          expect(doc.title).toBe(t('examples.product.title'));
          expect(doc.querySelector('img')?.alt).toBe(t('examples.product.title'));
          expect(doc.querySelector('main')?.dataset.width).toBe('1080');
          expect(doc.querySelector('main')?.dataset.height).toBe('1920');
          expect(doc.querySelector('.creator-format')?.textContent).toBe(t('examples.product.format'));
          expect(doc.querySelector('.creator-annotations h1')?.textContent).toBe(t('examples.product.descriptionLabel'));
          expect(doc.querySelector('.creator-description')?.textContent).toBe(t('examples.product.description'));
          expect(doc.querySelector('.creator-annotations h2')?.textContent).toBe(t('examples.product.hashtagsLabel'));
          expect(doc.querySelector('.creator-hashtags')?.textContent).toBe(t('examples.product.hashtags'));
          expect(doc.querySelector('.creator-annotations h1 svg')).not.toBeNull();
        } else {
          const example = BUSINESS_EXAMPLE_KEYS[persona][0];
          expect(doc.title).toBe(t(`examples.${example}.title`));
          expect(doc.querySelector('h1')?.textContent).toBe(t(`examples.${example}.subject`));
        }
        expect(doc.querySelectorAll('button:disabled')).toHaveLength(persona === 'creator' ? 0 : 1);
        expect(doc.querySelector('script')).toBeNull();
        expect(doc.querySelector('a')).toBeNull();
      }
    }
  });

  it('escapes translated content in text and media attributes without introducing executable HTML', () => {
    const hostile = '<img src=x onerror="alert(1)"><script>alert(1)</script>&';
    for (const persona of PERSONA_KEYS) {
      const doc = new DOMParser().parseFromString(buildPersonaInterfaceHtml(persona, 'light', () => hostile), 'text/html');
      expect(doc.querySelector('[onerror],script,img[src="x"]')).toBeNull();
      expect(persona === 'creator' ? doc.title : doc.querySelector('h1')?.textContent).toBe(hostile);
      if (persona === 'creator') {
        for (const selector of ['.creator-format', '.creator-annotations h1', '.creator-description', '.creator-annotations h2', '.creator-hashtags']) {
          expect(doc.querySelector(selector)?.textContent).toBe(hostile);
        }
        expect(doc.querySelector('.creator-annotations')?.getAttribute('aria-label')).toBe(hostile);
      }
    }
  });

  it('uses the shared portrait story asset for Creator while preserving the recruiting composition', () => {
    const creator = new DOMParser().parseFromString(buildPersonaInterfaceHtml('creator', 'light', key => key), 'text/html');
    expect(creator.images).toHaveLength(1);
    expect(creator.querySelector('img')?.getAttribute('src')).toBe(CREATOR_EXAMPLES.product.src);
    for (const image of Array.from(creator.images)) expect(image.getAttribute('src')).toMatch(/^\/landing\//);
    const recruiting = new DOMParser().parseFromString(buildPersonaInterfaceHtml('recruiting', 'dark', key => key), 'text/html');
    expect(recruiting.querySelector('.candidate-dossier')).not.toBeNull();
    expect(recruiting.querySelectorAll('.cv-experience')).toHaveLength(2);
  });
});

const compositions = {
  reply: '.helpdesk-layout', refund: '.cancellation-layout', incident: '.incident-console',
  prospect: '.profile-layout', quote: '.quote-document', followup: '.pipeline',
  campaign: '.campaign-calendar', seo: '.search-preview', listening: '.mention-feed',
  shortlist: '.candidate-dossier', interview: '.interview-grid', onboarding: '.task-list',
} as const;
for (const persona of ['support', 'sales', 'marketing', 'recruiting'] as const) {
  it.each(BUSINESS_EXAMPLE_KEYS[persona])('renders ${persona} %s as a localized authored workspace in both themes', example => {
    for (const [locale, catalog] of Object.entries(messages)) {
      const t = fixtureTranslations(locale, catalog.PersonaLanding.personas[persona].workflowShowcase);
      for (const theme of ['light', 'dark'] as const) {
        const doc = new DOMParser().parseFromString(buildPersonaInterfaceHtml(persona, theme, t, 'product', example), 'text/html');
        expect(doc.querySelector('main')?.dataset).toMatchObject({ layout: persona + '-' + example, width: '1020', height: '1080' });
        expect(doc.querySelector(compositions[example])).not.toBeNull();
        expect(doc.querySelector('h1')?.textContent).toBe(t('examples.' + example + '.subject'));
        expect(doc.querySelector('button')?.textContent).toBe(t('examples.' + example + '.actionLabel'));
        expect(doc.querySelectorAll('button:disabled')).toHaveLength(1);
        expect(doc.querySelector('script,input,iframe,a')).toBeNull();
        if (persona === 'support') {
          expect(doc.querySelectorAll('.support-metrics > div')).toHaveLength(3);
          expect(doc.querySelector('.support-metrics')?.textContent).toContain(t(`workbench.${example === 'reply' ? 'sla' : example === 'refund' ? 'mrrAtRisk' : 'uptime'}`));
          expect(doc.querySelector(example === 'reply' ? '.ticket-conversation' : example === 'refund' ? '.subscription-dossier' : '.service-board')).not.toBeNull();
        }
        if (example !== 'shortlist') for (const key of ['first','second','third']) expect(doc.body.textContent).toContain(t('examples.' + example + '.records.' + key + '.label'));
        if (example === 'quote') {
          expect(doc.querySelector('.quote-meta h2')?.textContent).toBe(t('examples.quote.document.documentTitle'));
          expect(doc.querySelector('.quote-logo')?.textContent).toBe('NC');
          expect(doc.querySelector('.quote-client strong')?.textContent).toBe(t('examples.quote.customerName'));
          expect(doc.querySelector('.quote-amount-card strong')?.textContent).toBe(t('examples.quote.totalValue'));
          expect(doc.querySelectorAll('.quote-items tbody tr')).toHaveLength(3);
          expect(doc.querySelector('.quote-totals .quote-total dd')?.textContent).toBe(t('examples.quote.totalValue'));
          expect(doc.querySelector('.quote-footer')?.textContent).toContain(t('examples.quote.document.notesValue'));
          expect(doc.querySelector('.rail')).toBeNull();
        }
        if (example === 'shortlist') {
          expect(doc.querySelector('.candidate-portrait')?.getAttribute('src')).toBe('/landing/personas/recruiting-alex-morgan.webp');
          expect(doc.querySelector('.candidate-portrait')?.getAttribute('alt')).toBe(t('examples.shortlist.customerName'));
          expect(doc.querySelectorAll('.cv-experience')).toHaveLength(2);
          expect(doc.querySelectorAll('.cv-skills li')).toHaveLength(3);
          expect(doc.querySelector('.cv-experience strong')?.textContent).toBe(t('examples.shortlist.resume.experienceFirst.role'));
          expect(doc.querySelector('.fit-assessment h3')?.textContent).toBe(t('examples.shortlist.resume.fitLabel'));
          expect(doc.querySelector('.human-review')?.textContent).toBe(t('examples.shortlist.resume.humanReview'));
          expect(doc.querySelector('.fit-score strong')?.textContent).toBe('82');
          expect(doc.querySelector('table,.metrics,.rail')).toBeNull();
        }
        if (example === 'interview') {
          expect(doc.querySelector('.meeting-date h2')?.textContent).toBe(t('examples.interview.meeting.date'));
          expect(doc.querySelector('.participants')?.textContent).toContain(t('examples.interview.meeting.interviewer'));
          expect(doc.querySelectorAll('.meeting-agenda li')).toHaveLength(3);
          expect(doc.querySelector('.cv-sheet,table,.editor')).toBeNull();
        }
        if (example === 'onboarding') {
          expect(doc.querySelector('.people-start strong')?.textContent).toBe(t('examples.onboarding.plan.startDate'));
          expect(doc.querySelector('.people-summary')?.textContent).toContain(t('examples.onboarding.plan.buddy'));
          expect(doc.querySelectorAll('.people-tasks li')).toHaveLength(3);
          expect(doc.querySelector('.people-summary strong')?.textContent).toBe('0 / 3');
          expect(doc.querySelector('.cv-sheet,.meeting-dossier,.editor')).toBeNull();
        }
      }
    }
  });
  it.each(BUSINESS_EXAMPLE_KEYS[persona])('escapes ${persona} %s fixture data and metadata', example => {
    const hostile = '<img src=x onerror="alert(1)"><script>alert(1)</script>&';
    const doc = new DOMParser().parseFromString(buildPersonaInterfaceHtml(persona, 'light', () => hostile, 'product', example), 'text/html');
    expect(doc.querySelector('script,[onerror],img[src="x"]')).toBeNull();
    expect(doc.querySelector('h1')?.textContent).toBe(hostile);
    expect(doc.querySelector('button')?.textContent).toBe(hostile);
  });
}
