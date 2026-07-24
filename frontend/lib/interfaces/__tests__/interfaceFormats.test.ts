import { describe, it, expect } from 'vitest';
import {
  DEFAULT_FORMAT_VIEWPORT,
  INTERFACE_FORMAT_PRESETS,
  isPortraitFormat,
  normalizeInterfaceFormat,
  resolveInterfaceFormat,
  resolveInterfaceFormatOrDefault,
} from '../interfaceFormats';

/**
 * The format util is the frontend mirror of the backend's InterfaceFormat.java:
 * preset table, alias table, custom "WxH" parsing and bounds must stay in sync.
 * These tests pin the frontend side of that contract.
 */
describe('interfaceFormats', () => {
  describe('preset resolution', () => {
    it('resolves every declared preset to its own dimensions', () => {
      for (const preset of INTERFACE_FORMAT_PRESETS) {
        expect(resolveInterfaceFormat(preset.name)).toEqual({
          width: preset.width,
          height: preset.height,
        });
      }
    });

    it('resolves the key presets to their documented dimensions', () => {
      expect(resolveInterfaceFormat('classic')).toEqual({ width: 1280, height: 800 });
      expect(resolveInterfaceFormat('widescreen')).toEqual({ width: 1920, height: 1080 });
      expect(resolveInterfaceFormat('vertical')).toEqual({ width: 1080, height: 1920 });
      expect(resolveInterfaceFormat('square')).toEqual({ width: 1080, height: 1080 });
    });

    it('is case-insensitive and trims whitespace', () => {
      expect(resolveInterfaceFormat('  Vertical ')).toEqual({ width: 1080, height: 1920 });
      expect(resolveInterfaceFormat('WIDESCREEN')).toEqual({ width: 1920, height: 1080 });
    });

    it('returns null for blank or unknown input', () => {
      expect(resolveInterfaceFormat(undefined)).toBeNull();
      expect(resolveInterfaceFormat(null)).toBeNull();
      expect(resolveInterfaceFormat('')).toBeNull();
      expect(resolveInterfaceFormat('   ')).toBeNull();
      expect(resolveInterfaceFormat('does_not_exist')).toBeNull();
    });
  });

  describe('aliases', () => {
    it.each([
      ['landscape', 'classic', { width: 1280, height: 800 }],
      ['horizontal', 'widescreen', { width: 1920, height: 1080 }],
      ['story', 'vertical', { width: 1080, height: 1920 }],
      ['og', 'social_card', { width: 1200, height: 630 }],
      ['16:9', 'widescreen', { width: 1920, height: 1080 }],
      ['9:16', 'vertical', { width: 1080, height: 1920 }],
      ['1:1', 'square', { width: 1080, height: 1080 }],
      ['4:5', 'portrait', { width: 1080, height: 1350 }],
    ])('alias %s resolves like preset %s', (alias, canonical, viewport) => {
      expect(resolveInterfaceFormat(alias)).toEqual(viewport);
      expect(normalizeInterfaceFormat(alias)).toBe(canonical);
    });
  });

  describe('custom "WxH"', () => {
    it('parses lowercase x separator', () => {
      expect(resolveInterfaceFormat('1080x1920')).toEqual({ width: 1080, height: 1920 });
    });

    it('parses the unicode multiplication sign separator', () => {
      expect(resolveInterfaceFormat('1080×1920')).toEqual({ width: 1080, height: 1920 });
    });

    it('parses uppercase X and inner whitespace', () => {
      expect(resolveInterfaceFormat(' 640 X 480 ')).toEqual({ width: 640, height: 480 });
    });

    it('floors odd custom dimensions to even (H.264 parity with the video pipeline)', () => {
      expect(resolveInterfaceFormat('1081x1921')).toEqual({ width: 1080, height: 1920 });
      expect(normalizeInterfaceFormat('1081x1921')).toBe('1080x1920');
    });

    it('rejects dimensions below the minimum (16)', () => {
      expect(resolveInterfaceFormat('15x100')).toBeNull();
      expect(resolveInterfaceFormat('100x15')).toBeNull();
    });

    it('rejects dimensions above the maximum (2160)', () => {
      expect(resolveInterfaceFormat('2161x100')).toBeNull();
      expect(resolveInterfaceFormat('100x2161')).toBeNull();
    });

    it('accepts the exact bounds', () => {
      expect(resolveInterfaceFormat('16x2160')).toEqual({ width: 16, height: 2160 });
    });

    it('rejects malformed input', () => {
      expect(resolveInterfaceFormat('1080')).toBeNull();
      expect(resolveInterfaceFormat('1080x')).toBeNull();
      expect(resolveInterfaceFormat('x1920')).toBeNull();
      expect(resolveInterfaceFormat('axb')).toBeNull();
      expect(resolveInterfaceFormat('-100x100')).toBeNull();
    });
  });

  describe('resolveInterfaceFormatOrDefault', () => {
    it('falls back to the classic 1280x800 viewport', () => {
      expect(resolveInterfaceFormatOrDefault(undefined)).toEqual(DEFAULT_FORMAT_VIEWPORT);
      expect(resolveInterfaceFormatOrDefault('garbage')).toEqual({ width: 1280, height: 800 });
    });

    it('keeps a valid format untouched', () => {
      expect(resolveInterfaceFormatOrDefault('banner')).toEqual({ width: 1500, height: 500 });
    });
  });

  describe('normalizeInterfaceFormat', () => {
    it('canonicalizes preset casing and whitespace', () => {
      expect(normalizeInterfaceFormat(' Vertical ')).toBe('vertical');
    });

    it('canonicalizes a custom pair to lowercase "WxH"', () => {
      expect(normalizeInterfaceFormat(' 1080 X 1920 ')).toBe('1080x1920');
      expect(normalizeInterfaceFormat('1080×1920')).toBe('1080x1920');
    });

    it('returns null for blank, unknown or out-of-range input', () => {
      expect(normalizeInterfaceFormat(undefined)).toBeNull();
      expect(normalizeInterfaceFormat('')).toBeNull();
      expect(normalizeInterfaceFormat('nope')).toBeNull();
      expect(normalizeInterfaceFormat('9999x9999')).toBeNull();
    });
  });

  describe('isPortraitFormat', () => {
    it('flags taller-than-wide formats', () => {
      expect(isPortraitFormat('vertical')).toBe(true);
      expect(isPortraitFormat('portrait')).toBe(true);
      expect(isPortraitFormat('400x900')).toBe(true);
    });

    it('does not flag landscape, square, blank or invalid formats', () => {
      expect(isPortraitFormat('classic')).toBe(false);
      expect(isPortraitFormat('square')).toBe(false);
      expect(isPortraitFormat(undefined)).toBe(false);
      expect(isPortraitFormat('garbage')).toBe(false);
    });
  });
});
