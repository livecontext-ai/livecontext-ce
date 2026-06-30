/**
 * Tests for the build-time edition resolver.
 *
 * The resolver evaluates env vars **once at module load** (via the `EDITION`
 * top-level const). Each test must therefore: (a) stub the env vars, (b) reset
 * Vitest's module cache, (c) dynamically import the module so the resolver
 * re-runs against the stubbed values.
 */

import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';

async function importEdition() {
    return await import('../../../lib/edition/edition');
}

describe('edition resolver - precedence', () => {
    let warnSpy: ReturnType<typeof vi.spyOn>;

    beforeEach(() => {
        vi.resetModules();
        warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    });

    afterEach(() => {
        vi.unstubAllEnvs();
        warnSpy.mockRestore();
    });

    it('APP_EDITION=ce → ce, no warn', async () => {
        vi.stubEnv('NEXT_PUBLIC_APP_EDITION', 'ce');
        vi.stubEnv('NEXT_PUBLIC_AUTH_MODE', '');
        const { EDITION, IS_CE, IS_CLOUD } = await importEdition();
        expect(EDITION).toBe('ce');
        expect(IS_CE).toBe(true);
        expect(IS_CLOUD).toBe(false);
        expect(warnSpy).not.toHaveBeenCalled();
    });

    it('APP_EDITION=cloud + AUTH_MODE=oidc → cloud, no warn', async () => {
        vi.stubEnv('NEXT_PUBLIC_APP_EDITION', 'cloud');
        vi.stubEnv('NEXT_PUBLIC_AUTH_MODE', 'oidc');
        const { EDITION } = await importEdition();
        expect(EDITION).toBe('cloud');
        expect(warnSpy).not.toHaveBeenCalled();
    });

    it('APP_EDITION=cloud + AUTH_MODE=embedded → ce (conflict warn)', async () => {
        vi.stubEnv('NEXT_PUBLIC_APP_EDITION', 'cloud');
        vi.stubEnv('NEXT_PUBLIC_AUTH_MODE', 'embedded');
        const { EDITION } = await importEdition();
        expect(EDITION).toBe('ce');
        expect(warnSpy).toHaveBeenCalledTimes(1);
        expect((warnSpy.mock.calls[0][0] as string)).toContain('conflicts');
    });

    it('APP_EDITION unset + AUTH_MODE=embedded → ce (legacy shim)', async () => {
        vi.stubEnv('NEXT_PUBLIC_APP_EDITION', '');
        vi.stubEnv('NEXT_PUBLIC_AUTH_MODE', 'embedded');
        const { EDITION } = await importEdition();
        expect(EDITION).toBe('ce');
        expect(warnSpy).not.toHaveBeenCalled();
    });

    it('APP_EDITION unset + AUTH_MODE unset → cloud (default)', async () => {
        vi.stubEnv('NEXT_PUBLIC_APP_EDITION', '');
        vi.stubEnv('NEXT_PUBLIC_AUTH_MODE', '');
        const { EDITION } = await importEdition();
        expect(EDITION).toBe('cloud');
        expect(warnSpy).not.toHaveBeenCalled();
    });

    it('APP_EDITION=invalid + AUTH_MODE=embedded → ce + warn on invalid', async () => {
        vi.stubEnv('NEXT_PUBLIC_APP_EDITION', 'enterprise');
        vi.stubEnv('NEXT_PUBLIC_AUTH_MODE', 'embedded');
        const { EDITION } = await importEdition();
        expect(EDITION).toBe('ce');
        expect(warnSpy).toHaveBeenCalledTimes(1);
        expect((warnSpy.mock.calls[0][0] as string)).toContain('Invalid');
    });

    it('APP_EDITION=CE (uppercase) → ce (case-insensitive)', async () => {
        vi.stubEnv('NEXT_PUBLIC_APP_EDITION', 'CE');
        vi.stubEnv('NEXT_PUBLIC_AUTH_MODE', '');
        const { EDITION } = await importEdition();
        expect(EDITION).toBe('ce');
    });

    it('APP_EDITION="  ce  " (whitespace) → ce (trimmed)', async () => {
        vi.stubEnv('NEXT_PUBLIC_APP_EDITION', '  ce  ');
        vi.stubEnv('NEXT_PUBLIC_AUTH_MODE', '');
        const { EDITION } = await importEdition();
        expect(EDITION).toBe('ce');
    });
});

describe('useEdition hook', () => {
    beforeEach(() => {
        vi.resetModules();
    });

    afterEach(() => {
        vi.unstubAllEnvs();
    });

    it('returns the resolved EDITION constant synchronously', async () => {
        vi.stubEnv('NEXT_PUBLIC_APP_EDITION', 'ce');
        vi.stubEnv('NEXT_PUBLIC_AUTH_MODE', '');
        const { useEdition } = await import('../../../lib/edition/useEdition');
        expect(useEdition()).toBe('ce');
    });
});
