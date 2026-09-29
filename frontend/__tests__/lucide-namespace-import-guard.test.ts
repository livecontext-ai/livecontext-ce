import { readdirSync, readFileSync, statSync } from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * No source file may import lucide-react as a namespace.
 *
 * `import * as Icons from 'lucide-react'` followed by a keyed lookup keeps every
 * icon reachable, so the bundler ships the whole library (1.4 MB of JavaScript)
 * to every page that contains the importing component. Three category surfaces
 * did exactly that; they now read `lib/marketplace/categoryIcons.ts`. Import the
 * icons you use by name instead.
 */
const root = path.resolve(__dirname, '..');
const SKIP = new Set(['node_modules', '.next', 'e2e', 'coverage', 'public']);

function sourceFiles(dir: string, out: string[] = []): string[] {
  for (const entry of readdirSync(dir)) {
    if (SKIP.has(entry) || entry.startsWith('.')) continue;
    const full = path.join(dir, entry);
    if (statSync(full).isDirectory()) sourceFiles(full, out);
    else if (/\.(ts|tsx)$/.test(entry) && !/\.test\.tsx?$/.test(entry)) out.push(full);
  }
  return out;
}

/** Every shape that keeps the whole library reachable. */
const WHOLE_LIBRARY = [
  /import\s+\*\s+as\s+\w+\s+from\s+['"]lucide-react['"]/,
  /export\s+\*\s+from\s+['"]lucide-react['"]/,
  /import\(\s*['"]lucide-react['"]\s*\)/,
  /require\(\s*['"]lucide-react['"]\s*\)/,
];

describe('lucide-react imports', () => {
  it('never imports the whole icon library as a namespace', () => {
    const offenders = sourceFiles(root)
      .filter((file) => WHOLE_LIBRARY.some((pattern) => pattern.test(readFileSync(file, 'utf8'))))
      .map((file) => path.relative(root, file));
    expect(offenders).toEqual([]);
  });
});
