import { readFileSync } from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * The side-panel openers used by workflow nodes load their panels lazily.
 *
 * Every canvas node imports these two modules to get its hover buttons. While
 * they imported the panels statically, the agent fleet canvas, the conversation
 * UI, the data table, the file viewer (markdown, syntax highlighting) and the
 * storage explorer were bundled with every node: 600 source files behind the
 * landing hero, which renders nodes but never opens a panel. Measured on a
 * production build, the landing went from 11.7 MB to 6.4 MB (uncompressed).
 *
 * Source-level on purpose: the behaviour tests mock these modules, so they
 * cannot tell a static import from a lazy one. `lazyPanelsRender.test.tsx`
 * proves the lazy panels still render.
 */
const frontend = path.resolve(__dirname, '..');
const read = (file: string) => readFileSync(path.join(frontend, file), 'utf8');

const escapeRegExp = (value: string) => value.replace(/[.*+?^${}()|[\]\\/]/g, '\\$&');

/** The source with comments removed, so a commented-out import cannot satisfy a check. */
function code(src: string): string {
  return src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(^|[^:])\/\/.*$/gm, '$1');
}

/** Static import statements of `module`, across line breaks (type-only imports excluded). */
function staticImportsOf(src: string, module: string): string[] {
  const statement = new RegExp(`^\\s*import\\s+(?!type\\s)[^;]*?from\\s*['"]${escapeRegExp(module)}['"]`, 'gm');
  return code(src).match(statement) ?? [];
}

/** A `dynamic(() => import('<module>') ..., { ssr: false })` call in live code. */
function lazyClientImport(module: string): RegExp {
  return new RegExp(`dynamic\\(\\s*\\(\\)\\s*=>\\s*import\\('${escapeRegExp(module)}'\\)[\\s\\S]*?\\{\\s*ssr:\\s*false\\s*\\}`);
}

describe('side-panel content is loaded lazily', () => {
  it.each([
    ['app/workflows/builder/hooks/useNodeContextualButtons.tsx', '@/components/app/AgentPanelContent'],
    ['app/workflows/builder/hooks/useNodeContextualButtons.tsx', '@/components/app/DataSourcePanelContent'],
    ['lib/sidePanel/openFilesPanel.tsx', '@/components/app/FileDetailView'],
    ['lib/sidePanel/openFilesPanel.tsx', '@/app/workflows/builder/components/inspector/StorageExplorerTab'],
  ])('%s does not statically import %s', (file, module) => {
    const src = read(file);
    expect(staticImportsOf(src, module)).toEqual([]);
    // ...and still reaches it, through a client-only dynamic import in live code.
    expect(code(src)).toMatch(lazyClientImport(module));
  });

  it('the matcher sees a static import that spans several lines, and ignores a commented one', () => {
    // Guards the guard: a multi-line import is the likelier way to reintroduce one.
    const module = '@/components/app/AgentPanelContent';
    expect(staticImportsOf(`import {\n  AgentPanelContent,\n} from '${module}';`, module)).toHaveLength(1);
    expect(staticImportsOf(`// import { AgentPanelContent } from '${module}';`, module)).toEqual([]);
    expect(staticImportsOf(`import type { Props } from '${module}';`, module)).toEqual([]);
  });

  it('keeps the agent tab names importable without the agent panel', () => {
    const tabs = read('components/app/agentPanelTabs.ts');
    expect(tabs).not.toMatch(/^\s*import\s/m);
    expect(read('app/workflows/builder/hooks/useNodeContextualButtons.tsx')).toContain("from '@/components/app/agentPanelTabs'");
  });

  it('preloads exactly the panels it made lazy', () => {
    const preloader = code(read('lib/sidePanel/preloadNodePanels.ts'));
    for (const module of [
      '@/components/app/AgentPanelContent',
      '@/components/app/DataSourcePanelContent',
      '@/components/app/FileDetailView',
      '@/app/workflows/builder/components/inspector/StorageExplorerTab',
    ]) {
      expect(preloader).toContain(`import('${module}')`);
    }
  });
});
