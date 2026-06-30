import fs from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';

describe('CE setup apiClient paths', () => {
  it('completes setup through the apiClient-relative CE path', () => {
    const source = fs.readFileSync(
      path.join(process.cwd(), 'app/[locale]/ce-setup/page.tsx'),
      'utf8',
    );

    expect(source).toContain('apiClient.post(CE_COMPLETE_API_PATH, {})');
    expect(source).not.toContain("apiClient.post('/api/ce/complete'");
    expect(source).not.toContain('apiClient.post("/api/ce/complete"');
  });
});
