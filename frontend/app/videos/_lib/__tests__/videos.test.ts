import { readFileSync } from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';
import {
  formatTimecode,
  isoDuration,
  linesToProse,
  transcriptText,
  videoPath,
  youtubeCanonicalEmbedUrl,
  youtubeEmbedUrl,
  youtubeWatchUrl,
} from '../videos';
import type { ProductVideo } from '../types';

const FRONTEND_ROOT = path.resolve(__dirname, '..', '..', '..', '..');

describe('durations', () => {
  it('formats a timecode as m:ss', () => {
    expect(formatTimecode(0)).toBe('0:00');
    expect(formatTimecode(9)).toBe('0:09');
    expect(formatTimecode(65)).toBe('1:05');
    expect(formatTimecode(318)).toBe('5:18');
    expect(formatTimecode(3661)).toBe('61:01');
  });

  it('writes the ISO 8601 duration schema.org requires', () => {
    // A malformed duration costs the page its video rich result while the page
    // itself still renders perfectly, so there is no visible symptom to catch it.
    expect(isoDuration(318)).toBe('PT5M18S');
    expect(isoDuration(290)).toBe('PT4M50S');
    expect(isoDuration(60)).toBe('PT1M0S');
    expect(isoDuration(0)).toBe('PT0M0S');
  });

  it('never emits a negative timecode or duration', () => {
    expect(formatTimecode(-5)).toBe('0:00');
    expect(isoDuration(-5)).toBe('PT0M0S');
  });
});

describe('lines to prose', () => {
  it('separates the lines it joins', () => {
    expect(linesToProse([{ t: 0, text: 'One.' }, { t: 1, text: 'Two.' }])).toBe('One. Two.');
  });

  it('is empty for no lines', () => {
    expect(linesToProse([])).toBe('');
  });

  it('is what the VideoObject transcript is built from', () => {
    const video = { transcript: [{ t: 0, text: 'One.' }, { t: 1, text: 'Two.' }] } as ProductVideo;
    expect(transcriptText(video)).toBe('One. Two.');
  });
});

describe('YouTube urls', () => {
  it('plays inline and does not pass a parameter YouTube stopped honouring', () => {
    const url = youtubeEmbedUrl('abc12345678');
    expect(url).toContain('playsinline=1');
    expect(url).not.toContain('modestbranding');
  });

  it('embeds through the no-cookie host', () => {
    // The facade plus this host is what makes a visitor who never presses play
    // never touch YouTube at all.
    expect(youtubeEmbedUrl('abc12345678')).toContain('https://www.youtube-nocookie.com/embed/abc12345678');
  });

  it('advertises the canonical host to crawlers, which is a different job', () => {
    // This string is read, not loaded: scrapers match it against the video they
    // already know, and they know the canonical host.
    expect(youtubeCanonicalEmbedUrl('abc12345678')).toBe('https://www.youtube.com/embed/abc12345678');
  });

  it('carries a start offset only when there is one', () => {
    expect(youtubeEmbedUrl('abc12345678')).not.toContain('start=');
    expect(youtubeEmbedUrl('abc12345678', 0)).not.toContain('start=');
    expect(youtubeEmbedUrl('abc12345678', 120.7)).toContain('start=120');
  });

  it('autoplays on a press of play and not on a timestamped arrival', () => {
    expect(youtubeEmbedUrl('abc12345678')).toContain('autoplay=1');
    expect(youtubeEmbedUrl('abc12345678', 90, { autoplay: false })).toContain('autoplay=0');
  });

  it('builds a watch url, with the timestamp when asked', () => {
    expect(youtubeWatchUrl('abc12345678')).toBe('https://www.youtube.com/watch?v=abc12345678');
    expect(youtubeWatchUrl('abc12345678', 90.9)).toBe('https://www.youtube.com/watch?v=abc12345678&t=90');
  });
});

describe('paths', () => {
  it('builds the page path from the slug', () => {
    expect(videoPath('automate-client-invoicing')).toBe('/videos/automate-client-invoicing');
  });
});

describe('the section writes no em-dash and no en-dash', () => {
  // Project rule: both read as machine-written. The film COPY is guarded at
  // write time by the publishing workflow; these are the page sources, which no
  // runtime check can see.
  const SOURCES = [
    'app/videos/page.tsx',
    'app/videos/[slug]/page.tsx',
    'app/videos/_lib/videos.ts',
    'app/videos/_lib/types.ts',
    'app/videos/_lib/youtube.ts',
    'app/videos/_lib/publicVideos.ts',
    'components/videos/FilmPlayer.tsx',
    'components/videos/SeekButton.tsx',
    'components/videos/filmSeek.ts',
  ];

  for (const source of SOURCES) {
    it(source, () => {
      expect(readFileSync(path.join(FRONTEND_ROOT, source), 'utf8')).not.toMatch(/[--]/);
    });
  }
});
