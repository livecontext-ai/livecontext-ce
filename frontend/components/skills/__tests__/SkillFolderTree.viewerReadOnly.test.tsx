// @vitest-environment jsdom
/**
 * RBAC hardening (2026-07-02): the VIEWER org role is read-only in the skills
 * tree - the "+" create menu, the skill row menu (delete / default-active /
 * share) and the folder menu (rename / delete) are hidden, and rows stop being
 * draggable. The per-user active Switch stays: it only writes the caller's own
 * override, not the shared resource. MEMBER keeps the full surface.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Skill, SkillFolder } from '@/lib/api';

const orgMutationGate = vi.hoisted(() => ({ canMutate: true }));

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, vars?: Record<string, unknown>) =>
    vars && 'name' in vars ? `${key}:${vars.name}` : key,
}));
vi.mock('@/lib/stores/current-org-store', () => ({
  useCanMutateInCurrentOrg: () => orgMutationGate.canMutate,
}));
vi.mock('@/components/publications/PublicationStatusIcon', () => ({
  PublicationStatusIcon: () => null,
}));

import { SkillFolderTree } from '../SkillFolderTree';

const folder = { id: 'f1', name: 'Ops', parentId: null, isGlobal: false } as unknown as SkillFolder;
const skill = { id: 's1', name: 'Summarize', folderId: null, isGlobal: false, isDefaultActive: true } as unknown as Skill;

function renderTree() {
  return render(
    <SkillFolderTree
      allFolders={[folder]}
      allSkills={[skill]}
      onCreateFolder={vi.fn()}
      onRenameFolder={vi.fn()}
      onDeleteFolder={vi.fn()}
      onCreateSkill={vi.fn()}
      onEditSkill={vi.fn()}
      onDeleteSkill={vi.fn()}
      onMoveSkillToFolder={vi.fn()}
      onMoveFolderToFolder={vi.fn()}
      onShareSkill={vi.fn()}
      onUnshareSkill={vi.fn()}
      isAdmin={false}
      userOverrides={{}}
      onToggleSkillActive={vi.fn()}
      onToggleSkillIsDefaultActive={vi.fn()}
    />
  );
}

beforeEach(() => {
  orgMutationGate.canMutate = true;
});
afterEach(() => cleanup());

describe('SkillFolderTree - VIEWER read-only gating', () => {
  it('MEMBER: shows the add menus, the skill/folder action menus and draggable rows', () => {
    renderTree();
    expect(screen.getAllByLabelText('addMenu').length).toBeGreaterThan(0);
    expect(screen.getByLabelText('skillActions:Summarize')).toBeInTheDocument();
    expect(screen.getByLabelText('folderActions:Ops')).toBeInTheDocument();
    expect(screen.getByText('Summarize').closest('[draggable]')).toHaveAttribute('draggable', 'true');
  });

  it('VIEWER: hides add menus and every skill/folder action menu', () => {
    orgMutationGate.canMutate = false;
    renderTree();
    expect(screen.queryByLabelText('addMenu')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('skillActions:Summarize')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('folderActions:Ops')).not.toBeInTheDocument();
  });

  it('VIEWER: rows are not draggable but the per-user active toggle stays', () => {
    orgMutationGate.canMutate = false;
    renderTree();
    expect(screen.getByText('Summarize').closest('[draggable]')).toHaveAttribute('draggable', 'false');
    expect(screen.getByText('Ops').closest('[draggable]')).toHaveAttribute('draggable', 'false');
    // The per-user override switch (aria-label = skill name) is still offered.
    expect(screen.getByRole('switch', { name: 'Summarize' })).toBeInTheDocument();
  });
});
