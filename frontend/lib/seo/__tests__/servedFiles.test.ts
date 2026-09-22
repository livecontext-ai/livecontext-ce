import { describe, it, expect } from 'vitest';
import { readdirSync } from 'fs';
import { join } from 'path';
import {
  METADATA_ROUTES,
  PUBLIC_ASSET_DIRECTORIES,
  PUBLIC_ROOT_FILES,
  isServedFilePath,
} from '../servedFiles';
import { SITEMAP_PATHS } from '../sitemaps';

const PUBLIC_DIR = join(__dirname, '..', '..', '..', 'public');

describe('servedFiles - the list matches what is on disk', () => {
  // An allow list rots silently: a file added to `public/` and not named here
  // stops being served and answers 404, which nobody notices until a logo
  // disappears. Deriving both halves from disk turns that into a CI failure.
  it('names every directory under public/, and no others', () => {
    const onDisk = readdirSync(PUBLIC_DIR, { withFileTypes: true })
      .filter((entry) => entry.isDirectory())
      .map((entry) => entry.name)
      .sort();

    expect([...PUBLIC_ASSET_DIRECTORIES].sort()).toEqual(onDisk);
  });

  it('names every dotted file at the root of public/, and no others', () => {
    const onDisk = readdirSync(PUBLIC_DIR, { withFileTypes: true })
      .filter((entry) => entry.isFile() && entry.name.includes('.'))
      .map((entry) => entry.name)
      .sort();

    expect([...PUBLIC_ROOT_FILES].sort()).toEqual(onDisk);
  });

  it('serves every sitemap robots.txt advertises', () => {
    // A sitemap declared to search engines and 404ing here is a broken promise
    // nothing else would report.
    for (const path of SITEMAP_PATHS) {
      expect(METADATA_ROUTES, path).toContain(path);
      expect(isServedFilePath(path), path).toBe(true);
    }
  });
});

describe('servedFiles - what passes', () => {
  it('serves the files that exist at the root of public/', () => {
    for (const file of PUBLIC_ROOT_FILES) {
      expect(isServedFilePath(`/${file}`), file).toBe(true);
    }
  });

  it('serves anything under a real asset directory', () => {
    expect(isServedFilePath('/videos/automate-client-invoicing.webp')).toBe(true);
    expect(isServedFilePath('/landing/screenshots/builder.png')).toBe(true);
    expect(isServedFilePath('/avatars/someone.jpg')).toBe(true);
  });

  it('serves the metadata routes, apex and per section', () => {
    expect(isServedFilePath('/robots.txt')).toBe(true);
    expect(isServedFilePath('/sitemap.xml')).toBe(true);
    expect(isServedFilePath('/videos/sitemap.xml')).toBe(true);
  });

  it('serves the build output', () => {
    expect(isServedFilePath('/_next/static/chunks/main.js')).toBe(true);
  });
});

describe('servedFiles - what is refused', () => {
  // Each of these answered HTTP 200 with the landing page before this module
  // existed, which is what made a search console file them as Soft 404 and made
  // "the verification file is installed" indistinguishable from "it is not".
  it('refuses an invented verification or sitemap file at the root', () => {
    expect(isServedFilePath('/indexnow.txt')).toBe(false);
    expect(isServedFilePath('/BingSiteAuth.xml')).toBe(false);
    expect(isServedFilePath('/sitemap_index.xml')).toBe(false);
    expect(isServedFilePath('/google1234567890abcdef.html')).toBe(false);
  });

  it('refuses a metadata FILENAME under a directory nobody publishes', () => {
    // The first version of this module matched the last segment at any depth,
    // which handed 200 to `/wp-content/sitemap.xml` and `/wp-admin/robots.txt`,
    // two of the most probed URLs on the web, and re-opened the whole class the
    // module exists to close. Only the WHOLE path counts.
    expect(isServedFilePath('/wp-content/sitemap.xml')).toBe(false);
    expect(isServedFilePath('/wp-admin/robots.txt')).toBe(false);
    expect(isServedFilePath('/a/b/c/sitemap.xml')).toBe(false);
    expect(isServedFilePath('/nawak/manifest.webmanifest')).toBe(false);
    // A metadata route is not locale-prefixed either.
    expect(isServedFilePath('/en/sitemap.xml')).toBe(false);
  });

  it('refuses the .well-known namespace, because this site serves nothing there', () => {
    // Reserving a namespace in an RFC does not make a deployment answer it.
    // Waving it through meant every ACME probe, security.txt scan and Chrome
    // devtools lookup collected a 200 with an HTML body.
    expect(isServedFilePath('/.well-known/security.txt')).toBe(false);
    expect(isServedFilePath('/.well-known/acme-challenge/x')).toBe(false);
  });

  it('refuses the probes that hunt for another stack', () => {
    expect(isServedFilePath('/wp-login.php')).toBe(false);
    expect(isServedFilePath('/index.php')).toBe(false);
    expect(isServedFilePath('/.env')).toBe(false);
  });

  it('refuses a flight SUFFIX, which is the one shape this module forbids', () => {
    // A rule for `.rsc` / `.segments` was added once as a defence against a
    // future adapter, and it handed `/wp-login.rsc` and `/x.segments` straight
    // back to the catch-all: the same suffix-matching mistake as
    // `/wp-content/sitemap.xml`, reintroduced three lines below the comment
    // banning it. Nothing on this server requests these.
    expect(isServedFilePath('/wp-login.rsc')).toBe(false);
    expect(isServedFilePath('/x.segments')).toBe(false);
    expect(isServedFilePath('/a/b.segments/c')).toBe(false);
    expect(isServedFilePath('/about.rsc')).toBe(false);
  });

  it('refuses a framework prefix without its boundary', () => {
    // `startsWith('/__nextjs')` with no separator let `/__nextjsfoo.php` pass.
    expect(isServedFilePath('/__nextjsfoo.php')).toBe(false);
    expect(isServedFilePath('/_nextfoo.js')).toBe(false);
  });

  it('refuses an invented directory, even when it looks like an asset path', () => {
    expect(isServedFilePath('/assets/app.js')).toBe(false);
    expect(isServedFilePath('/static/logo.png')).toBe(false);
  });

  it('refuses a root file that merely resembles one we serve', () => {
    expect(isServedFilePath('/og-image.png')).toBe(false);
    expect(isServedFilePath('/llms.json')).toBe(false);
  });

  it('refuses the retired blog assets, so their URLs can drop out of the index', () => {
    expect(isServedFilePath('/blog/small-data-sharp-decisions.jpg')).toBe(false);
    expect(isServedFilePath('/blog/authors/someone.jpg')).toBe(false);
  });
});
