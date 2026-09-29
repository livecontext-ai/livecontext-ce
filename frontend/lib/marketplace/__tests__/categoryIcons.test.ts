import { readdirSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';
import * as Lucide from 'lucide-react';
import { CATEGORY_ICONS, getCategoryIcon } from '@/lib/marketplace/categoryIcons';

/**
 * Category icons come from a closed map.
 *
 * The three category surfaces resolved a slug with `import * as LucideIcons`,
 * which defeats tree shaking: the whole icon library (1.4 MB of JavaScript,
 * measured in the build analysis) shipped to every page with a category picker.
 * The map must still draw every slug the catalog actually seeds.
 */
const migrations = path.resolve(__dirname, '../../../../backend/migration-service/src/main/resources/db/migration');

/** Every icon slug a category seed migration inserts (the 5th value of each row). */
function seededSlugs(): string[] {
  const slugs = new Set<string>();
  for (const file of readdirSync(migrations)) {
    if (!/\.sql$/.test(file)) continue;
    const sql = readFileSync(path.join(migrations, file), 'utf8');
    if (!/INSERT INTO orchestrator\.workflow_categories/i.test(sql)) continue;
    // ('<uuid>', '<slug>', '<name>', '<description>', '<icon_slug>', '<color>', ...
    for (const row of sql.matchAll(/\(\s*'[0-9a-f-]{36}'\s*,\s*'[^']*'\s*,\s*'[^']*'\s*,\s*'(?:[^']|'')*'\s*,\s*'([a-z0-9-]+)'\s*,\s*'#[0-9a-fA-F]{6}'/g)) {
      slugs.add(row[1]);
    }
  }
  return [...slugs];
}

describe('category icons', () => {
  it('finds the seeded categories, so the check below is not vacuous', () => {
    expect(seededSlugs().length).toBeGreaterThanOrEqual(13);
  });

  it.each(seededSlugs())('draws the seeded slug "%s"', (slug) => {
    expect(getCategoryIcon(slug)).not.toBeNull();
  });

  it('returns null for an unknown, empty or missing slug, as the old lookup did', () => {
    expect(getCategoryIcon('not-an-icon')).toBeNull();
    expect(getCategoryIcon('')).toBeNull();
    expect(getCategoryIcon(undefined)).toBeNull();
    expect(getCategoryIcon(null)).toBeNull();
  });

  it('draws, for every slug it knows, exactly the icon the old lookup drew', () => {
    // The replaced resolver turned 'bar-chart-3' into Lucide's BarChart3 export. A map
    // entry pointing at any other icon would be a silent visual change.
    const pascal = (slug: string) => slug.split('-').map((word) => word.charAt(0).toUpperCase() + word.slice(1)).join('');
    for (const [slug, icon] of Object.entries(CATEGORY_ICONS)) {
      expect(icon, slug).toBe((Lucide as unknown as Record<string, unknown>)[pascal(slug)]);
    }
  });
});
