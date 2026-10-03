import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import path from 'node:path';

/**
 * V554 wiring the heavier composers cannot be mounted to prove.
 *
 * <p>AppHeader (the main chat's selection check) pulls in the router, the org store, streaming and
 * billing; ChatPageV2 the whole chat page. Their DECISIONS are tested where they live
 * (`isSelectionAvailable`, `ModelSelectorDropdown`); what this pins is that these two files still
 * route through them. Without it, dropping the unlisted list from either call left every test green
 * while the main chat reset a user on an unlisted model back to the default on the next load.
 * The side panels are mounted and tested instead (ChatPanelContent / WorkflowPanelContent
 * `.unlistedSelection` tests).
 */
const read = (rel: string) => readFileSync(path.join(__dirname, '..', '..', rel), 'utf8');

describe('unlisted models - composer wiring', () => {
  it('AppHeader validates the stored selection against the unlisted models too', () => {
    const src = read('app/AppHeader.tsx');

    expect(src).toMatch(/const \{[^}]*\bunlistedModels\b[^}]*\} = useVisibleModels\(\)/);
    expect(src).toMatch(/isSelectionAvailable\(\s*sel,\s*models,\s*unlistedModels\s*\)/);
  });

  it('ChatPageV2 hands the hidden models to the menu and names a hidden selection in the trigger', () => {
    const src = read('chat/ChatPageV2/index.tsx');

    expect(src).toMatch(/const \{[^}]*\bunlistedModels\b[^}]*\} = useVisibleModels\(\)/);
    expect(src).toMatch(/unlistedModels=\{hiddenModels\}/);
    expect(src).toMatch(/\?\?\s*hiddenModels\.find\(\(m\) => modelMatches\(m, selectedModel\)\)/);
  });

  it('every ModelPicker call site passes the translated Hidden models heading', () => {
    // The prop defaults to English, so a call site that forgets it shows "Hidden models" in
    // every language without failing anything.
    const callSites = [
      '../app/workflows/builder/components/inspector/AgentConfigurationPanel.tsx',
      '../app/workflows/builder/components/inspector/forms/BrowserAgentParametersForm.tsx',
      '../app/workflows/builder/components/inspector/forms/ClassifyParametersForm.tsx',
      '../app/workflows/builder/components/inspector/forms/GuardrailParametersForm.tsx',
      'chat/ChatConfigPanel.tsx',
      'chat/CreateAgentModal.tsx',
    ];
    for (const rel of callSites) {
      const src = read(rel);
      const pickers = src.match(/<ModelPicker\b/g) ?? [];
      const labelled = src.match(/hiddenModelsLabel=\{tActions\('unlistedModels'\)\}/g) ?? [];
      expect(pickers.length, rel).toBeGreaterThan(0);
      expect(labelled.length, rel).toBe(pickers.length);
    }
  });
});
