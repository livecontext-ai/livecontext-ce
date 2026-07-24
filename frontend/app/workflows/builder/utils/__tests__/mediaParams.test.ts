import { describe, it, expect } from 'vitest';
import {
  buildMediaPlanParams,
  clampMediaDimension,
  clampMediaNonNegative,
  clampMediaNormalizeLufs,
  clampMediaOpacity,
  clampMediaSpeed,
  clampMediaTargetFps,
  clampMediaTransitionSeconds,
  clampMediaVolume,
  clampMediaWidthPercent,
  extractMediaDataFromPlanParams,
  isMediaOperation,
  MEDIA_NORMALIZE_LUFS_DEFAULT,
  MEDIA_OPACITY_DEFAULT,
  MEDIA_SPEED_DEFAULT,
  MEDIA_TRANSITION_SECONDS_DEFAULT,
  MEDIA_VOLUME_DEFAULT,
  MEDIA_WIDTH_PERCENT_DEFAULT,
} from '../mediaParams';

/**
 * The media inspector commits numeric fields through these clamps (mirroring
 * the backend contract bounds: volume 0-400, speed 0.5-2.0, LUFS -70..-5,
 * everything time-like >= 0) and (de)serializes the plan's generic params map
 * through buildMediaPlanParams / extractMediaDataFromPlanParams with the EXACT
 * contract field names, omitting values equal to the contract defaults.
 */
describe('media clamps (inspector numeric fields)', () => {
  it('clamps volume to 0-400 and falls back to 100 on junk', () => {
    expect(clampMediaVolume(500)).toBe(400);
    expect(clampMediaVolume(-10)).toBe(0);
    expect(clampMediaVolume(250)).toBe(250);
    expect(clampMediaVolume('abc')).toBe(MEDIA_VOLUME_DEFAULT);
    expect(clampMediaVolume(undefined)).toBe(MEDIA_VOLUME_DEFAULT);
    expect(clampMediaVolume('300')).toBe(300);
  });

  it('clamps speed to 0.5-2.0 and falls back to 1.0 on junk', () => {
    expect(clampMediaSpeed(3)).toBe(2.0);
    expect(clampMediaSpeed(0.1)).toBe(0.5);
    expect(clampMediaSpeed(1.5)).toBe(1.5);
    expect(clampMediaSpeed('junk')).toBe(MEDIA_SPEED_DEFAULT);
  });

  it('clamps non-negative values to >= 0 and returns undefined on junk/empty (param omitted)', () => {
    expect(clampMediaNonNegative(-3)).toBe(0);
    expect(clampMediaNonNegative(2.5)).toBe(2.5);
    expect(clampMediaNonNegative('')).toBeUndefined();
    expect(clampMediaNonNegative('abc')).toBeUndefined();
    expect(clampMediaNonNegative(undefined)).toBeUndefined();
    expect(clampMediaNonNegative('4')).toBe(4);
  });

  it('clamps a custom loudness target to -70..-5 LUFS and falls back to -16 on junk', () => {
    expect(clampMediaNormalizeLufs(-80)).toBe(-70);
    expect(clampMediaNormalizeLufs(0)).toBe(-5);
    expect(clampMediaNormalizeLufs(-14)).toBe(-14);
    expect(clampMediaNormalizeLufs('junk')).toBe(MEDIA_NORMALIZE_LUFS_DEFAULT);
  });

  it('isMediaOperation accepts exactly the 7 contract operations', () => {
    expect(isMediaOperation('probe')).toBe(true);
    expect(isMediaOperation('mux_audio')).toBe(true);
    expect(isMediaOperation('mix')).toBe(true);
    expect(isMediaOperation('extract_audio')).toBe(true);
    expect(isMediaOperation('concat')).toBe(true);
    expect(isMediaOperation('frame')).toBe(true);
    expect(isMediaOperation('overlay')).toBe(true);
    expect(isMediaOperation('transcode')).toBe(false);
    expect(isMediaOperation(undefined)).toBe(false);
  });

  it('clamps a crossfade duration to 0.1-5.0 and falls back to 0.5 on junk', () => {
    expect(clampMediaTransitionSeconds(0.05)).toBe(0.1);
    expect(clampMediaTransitionSeconds(9)).toBe(5.0);
    expect(clampMediaTransitionSeconds(1.2)).toBe(1.2);
    expect(clampMediaTransitionSeconds('junk')).toBe(MEDIA_TRANSITION_SECONDS_DEFAULT);
  });

  it('clamps a pixel dimension to 16-4096, rounds odd values down to even, and returns undefined on junk/empty (param omitted)', () => {
    expect(clampMediaDimension(8)).toBe(16);
    expect(clampMediaDimension(5000)).toBe(4096);
    expect(clampMediaDimension(1921)).toBe(1920); // even enforced like the backend canvas
    expect(clampMediaDimension(1080)).toBe(1080);
    expect(clampMediaDimension('')).toBeUndefined();
    expect(clampMediaDimension('abc')).toBeUndefined();
  });

  it('clamps a target fps to 1-60 and returns undefined on junk/empty (default: first input fps)', () => {
    expect(clampMediaTargetFps(0)).toBe(1);
    expect(clampMediaTargetFps(120)).toBe(60);
    expect(clampMediaTargetFps(29.97)).toBe(29.97);
    expect(clampMediaTargetFps('')).toBeUndefined();
  });

  it('clamps overlay width_percent to 1-100 and opacity to 0-1 with their defaults on junk', () => {
    expect(clampMediaWidthPercent(0)).toBe(1);
    expect(clampMediaWidthPercent(150)).toBe(100);
    expect(clampMediaWidthPercent('junk')).toBe(MEDIA_WIDTH_PERCENT_DEFAULT);
    expect(clampMediaOpacity(-1)).toBe(0);
    expect(clampMediaOpacity(2)).toBe(1);
    expect(clampMediaOpacity(0.35)).toBe(0.35);
    expect(clampMediaOpacity('junk')).toBe(MEDIA_OPACITY_DEFAULT);
  });
});

describe('buildMediaPlanParams (builder data -> plan params map)', () => {
  it('probe emits only operation + input (empty string when unset)', () => {
    expect(buildMediaPlanParams('probe', { input: '{{f}}' })).toEqual({ operation: 'probe', input: '{{f}}' });
    expect(buildMediaPlanParams('probe', {})).toEqual({ operation: 'probe', input: '' });
    // params from other operations never leak into probe
    expect(buildMediaPlanParams('probe', { input: '{{f}}', video: '{{v}}', volume: 250 }))
      .toEqual({ operation: 'probe', input: '{{f}}' });
  });

  it('mux_audio always emits video and audio, and omits defaults', () => {
    const params = buildMediaPlanParams('mux_audio', {
      video: '{{v}}',
      audio: '{{a}}',
      volume: MEDIA_VOLUME_DEFAULT,       // default -> omitted
      offset_seconds: 0,                  // default -> omitted
      loop: false,                        // default -> omitted
      fade_in_seconds: 0,                 // default -> omitted
      fade_out_seconds: 1.0,              // mux default -> omitted
      keep_original_audio: false,         // default -> omitted
      audio_fit: 'pad',                   // default -> omitted
      normalize: true,                    // default -> omitted
      audio_bitrate: '192k',              // default -> omitted
    });
    expect(params).toEqual({ operation: 'mux_audio', video: '{{v}}', audio: '{{a}}' });
  });

  it('mux_audio keeps non-default values with their real JSON types', () => {
    const params = buildMediaPlanParams('mux_audio', {
      video: '{{v}}',
      audio: '{{a}}',
      volume: 250,
      offset_seconds: 3.5,
      loop: true,
      fade_out_seconds: 0,
      keep_original_audio: true,
      original_volume: 40,
      audio_fit: 'shortest',
      normalize: -14,
      audio_bitrate: '256k',
    });
    expect(params).toEqual({
      operation: 'mux_audio',
      video: '{{v}}',
      audio: '{{a}}',
      volume: 250,
      offset_seconds: 3.5,
      loop: true,
      fade_out_seconds: 0,
      keep_original_audio: true,
      original_volume: 40,
      audio_fit: 'shortest',
      normalize: -14,
      audio_bitrate: '256k',
    });
    expect(typeof params.volume).toBe('number');
    expect(typeof params.loop).toBe('boolean');
  });

  it('mux_audio omits original_volume when keep_original_audio is off', () => {
    const params = buildMediaPlanParams('mux_audio', {
      video: '{{v}}', audio: '{{a}}', original_volume: 40,
    });
    expect(params).not.toHaveProperty('original_volume');
  });

  it('normalize: false survives (non-default) while true is omitted', () => {
    expect(buildMediaPlanParams('mux_audio', { video: '{{v}}', audio: '{{a}}', normalize: false }).normalize).toBe(false);
    expect(buildMediaPlanParams('mux_audio', { video: '{{v}}', audio: '{{a}}', normalize: true })).not.toHaveProperty('normalize');
  });

  it('mix always emits tracks (each with a source), omits per-track defaults, and keeps duck params only with duck_under', () => {
    const params = buildMediaPlanParams('mix', {
      video: '{{v}}',
      tracks: [
        { source: '{{a}}', id: 'voice', volume: 100, speed: 1.0, fade_in_seconds: 0 },
        { source: '{{b}}', volume: 30, duck_under: 'voice', duck_amount_db: 12, duck_attack_ms: 50 },
        {},
      ],
      output_format: 'mp3',
    });
    expect(params.operation).toBe('mix');
    expect(params.video).toBe('{{v}}');
    expect(params.tracks).toEqual([
      { id: 'voice', source: '{{a}}' },
      { source: '{{b}}', volume: 30, duck_under: 'voice', duck_attack_ms: 50 },
      { source: '' },
    ]);
    expect(params).not.toHaveProperty('output_format'); // mp3 is the default
  });

  it('mix drops duck timing params when there is no duck_under', () => {
    const params = buildMediaPlanParams('mix', {
      tracks: [{ source: '{{a}}', duck_amount_db: 6, duck_release_ms: 100 }],
    });
    expect(params.tracks[0]).toEqual({ source: '{{a}}' });
  });

  it('extract_audio emits input plus non-default format/bitrate/trims', () => {
    expect(buildMediaPlanParams('extract_audio', { input: '{{f}}' }))
      .toEqual({ operation: 'extract_audio', input: '{{f}}' });
    expect(buildMediaPlanParams('extract_audio', {
      input: '{{f}}', output_format: 'wav', audio_bitrate: '128k', trim_start_seconds: 1, trim_end_seconds: 9,
    })).toEqual({
      operation: 'extract_audio', input: '{{f}}', output_format: 'wav', audio_bitrate: '128k',
      trim_start_seconds: 1, trim_end_seconds: 9,
    });
  });

  it('concat always emits inputs (each with a source), omitting per-item and global defaults', () => {
    const params = buildMediaPlanParams('concat', {
      inputs: [
        { source: '{{a}}', speed: 1.0 },             // speed default -> omitted
        { source: '{{b}}', trim_start_seconds: 2, trim_end_seconds: 9, speed: 1.5 },
        {},
      ],
      transition: 'cut',       // default -> omitted
      fade_in_seconds: 0,      // default -> omitted
      fade_out_seconds: 0,     // default -> omitted
      normalize: false,        // concat default -> omitted
      audio_bitrate: '192k',   // default -> omitted
    });
    expect(params).toEqual({
      operation: 'concat',
      inputs: [
        { source: '{{a}}' },
        { source: '{{b}}', trim_start_seconds: 2, trim_end_seconds: 9, speed: 1.5 },
        { source: '' },
      ],
    });
  });

  it('concat keeps non-default globals with real JSON types (crossfade, canvas, fps, fades, bitrate)', () => {
    const params = buildMediaPlanParams('concat', {
      inputs: [{ source: '{{a}}' }, { source: '{{b}}' }],
      transition: 'crossfade',
      transition_seconds: 1.2,
      target_width: 1920,
      target_height: 1080,
      target_fps: 30,
      fade_in_seconds: 0.5,
      fade_out_seconds: 2,
      audio_bitrate: '256k',
    });
    expect(params).toEqual({
      operation: 'concat',
      inputs: [{ source: '{{a}}' }, { source: '{{b}}' }],
      transition: 'crossfade',
      transition_seconds: 1.2,
      target_width: 1920,
      target_height: 1080,
      target_fps: 30,
      fade_in_seconds: 0.5,
      fade_out_seconds: 2,
      audio_bitrate: '256k',
    });
    expect(typeof params.target_width).toBe('number');
  });

  it('concat normalize default is FALSE: false/undefined omitted, true and a LUFS number survive (differs from mux/mix)', () => {
    const inputs = [{ source: '{{a}}' }];
    expect(buildMediaPlanParams('concat', { inputs, normalize: false })).not.toHaveProperty('normalize');
    expect(buildMediaPlanParams('concat', { inputs })).not.toHaveProperty('normalize');
    expect(buildMediaPlanParams('concat', { inputs, normalize: true }).normalize).toBe(true);
    expect(buildMediaPlanParams('concat', { inputs, normalize: -14 }).normalize).toBe(-14);
    // mux/mix keep their true default: true omitted, false survives
    expect(buildMediaPlanParams('mix', { tracks: [{ source: '{{a}}' }], normalize: true })).not.toHaveProperty('normalize');
  });

  it('concat drops a stale transition_seconds once the transition is back to cut - the field only exists WITH a crossfade', () => {
    const params = buildMediaPlanParams('concat', {
      inputs: [{ source: '{{a}}' }, { source: '{{b}}' }],
      transition_seconds: 1.5, // left over from a crossfade config
    });
    expect(params).not.toHaveProperty('transition');
    expect(params).not.toHaveProperty('transition_seconds');
  });

  it('frame emits input plus non-default at_seconds/image_format/width (at_seconds absent = middle of the video)', () => {
    expect(buildMediaPlanParams('frame', { input: '{{f}}', image_format: 'jpeg' }))
      .toEqual({ operation: 'frame', input: '{{f}}' });
    expect(buildMediaPlanParams('frame', {
      input: '{{f}}', at_seconds: 12.5, image_format: 'png', width: 640,
    })).toEqual({
      operation: 'frame', input: '{{f}}', at_seconds: 12.5, image_format: 'png', width: 640,
    });
  });

  it('overlay always emits video and image, omitting defaults (position/margin/width_percent/opacity)', () => {
    expect(buildMediaPlanParams('overlay', {
      video: '{{v}}', image: '{{i}}',
      position: 'bottom_right', margin_px: 24, width_percent: 15, opacity: 1.0,
    })).toEqual({ operation: 'overlay', video: '{{v}}', image: '{{i}}' });
    expect(buildMediaPlanParams('overlay', {
      video: '{{v}}', image: '{{i}}',
      position: 'top_left', margin_px: 48, width_percent: 30, opacity: 0.6,
      start_seconds: 2, end_seconds: 8,
    })).toEqual({
      operation: 'overlay', video: '{{v}}', image: '{{i}}',
      position: 'top_left', margin_px: 48, width_percent: 30, opacity: 0.6,
      start_seconds: 2, end_seconds: 8,
    });
  });

  it('params from other operations never leak into concat/frame/overlay exports', () => {
    const concat = buildMediaPlanParams('concat', { inputs: [{ source: '{{a}}' }], video: '{{v}}', tracks: [{ source: '{{t}}' }], volume: 250 });
    expect(concat).not.toHaveProperty('video');
    expect(concat).not.toHaveProperty('tracks');
    expect(concat).not.toHaveProperty('volume');
    const frame = buildMediaPlanParams('frame', { input: '{{f}}', inputs: [{ source: '{{a}}' }], position: 'center' });
    expect(frame).not.toHaveProperty('inputs');
    expect(frame).not.toHaveProperty('position');
    const overlay = buildMediaPlanParams('overlay', { video: '{{v}}', image: '{{i}}', at_seconds: 3, audio: '{{a}}' });
    expect(overlay).not.toHaveProperty('at_seconds');
    expect(overlay).not.toHaveProperty('audio');
  });

  it('unset operation emits { operation: "" } so validation can flag it', () => {
    expect(buildMediaPlanParams(undefined, { input: '{{f}}' })).toEqual({ operation: '' });
  });

  it('template strings for numeric params pass through verbatim', () => {
    const params = buildMediaPlanParams('mux_audio', {
      video: '{{v}}', audio: '{{a}}', volume: '{{loudness}}', offset_seconds: '{{start}}',
    });
    expect(params.volume).toBe('{{loudness}}');
    expect(params.offset_seconds).toBe('{{start}}');
  });
});

describe('extractMediaDataFromPlanParams (plan params map -> builder data)', () => {
  it('reads the operation and keeps known params verbatim (types preserved)', () => {
    const { mediaOperation, mediaParams } = extractMediaDataFromPlanParams({
      operation: 'mux_audio',
      video: '{{v}}',
      audio: '{{a}}',
      volume: 250,
      loop: true,
      normalize: -14,
    });
    expect(mediaOperation).toBe('mux_audio');
    expect(mediaParams).toEqual({ video: '{{v}}', audio: '{{a}}', volume: 250, loop: true, normalize: -14 });
    expect(typeof mediaParams.volume).toBe('number');
  });

  it('drops an unknown operation and unknown param keys', () => {
    const { mediaOperation, mediaParams } = extractMediaDataFromPlanParams({
      operation: 'transcode',
      input: '{{f}}',
      ffmpeg_args: '-y -i x',
    });
    expect(mediaOperation).toBeUndefined();
    expect(mediaParams).toEqual({ input: '{{f}}' });
  });

  it('prunes tracks to the known per-track contract keys', () => {
    const { mediaParams } = extractMediaDataFromPlanParams({
      operation: 'mix',
      tracks: [
        { source: '{{a}}', id: 'voice', speed: 1.5, custom_filter: 'reverb' },
        'not-a-track',
        { source: '{{b}}', duck_under: 'voice', duck_amount_db: 6 },
      ],
    });
    expect(mediaParams.tracks).toEqual([
      { source: '{{a}}', id: 'voice', speed: 1.5 },
      { source: '{{b}}', duck_under: 'voice', duck_amount_db: 6 },
    ]);
  });

  it('handles absent params cleanly', () => {
    const { mediaOperation, mediaParams } = extractMediaDataFromPlanParams(undefined);
    expect(mediaOperation).toBeUndefined();
    expect(mediaParams).toEqual({});
  });

  it('passes LITERAL FileRef objects through the export untouched - a builder re-save must not wipe an agent-configured literal ref (audit regression)', () => {
    const ref = { _type: 'file', path: '1/general/files/x_clip.mp4', name: 'clip.mp4', mimeType: 'video/mp4', size: 123 };
    const aref = { _type: 'file', path: '1/general/files/y_bed.mp3', name: 'bed.mp3', mimeType: 'audio/mpeg', size: 45 };

    expect(buildMediaPlanParams('probe', { input: ref }).input).toEqual(ref);
    const mux = buildMediaPlanParams('mux_audio', { video: ref, audio: aref });
    expect(mux.video).toEqual(ref);
    expect(mux.audio).toEqual(aref);
    const mix = buildMediaPlanParams('mix', { video: ref, tracks: [{ source: aref }] });
    expect(mix.video).toEqual(ref);
    expect(mix.tracks[0].source).toEqual(aref);

    // Full import -> export roundtrip keeps the objects
    const { mediaOperation, mediaParams } = extractMediaDataFromPlanParams(mux);
    expect(buildMediaPlanParams(mediaOperation!, mediaParams)).toEqual(mux);

    // A random object WITHOUT a path is still coerced to '' (not a FileRef)
    expect(buildMediaPlanParams('probe', { input: { foo: 'bar' } }).input).toBe('');
  });

  it('prunes concat inputs to the known per-item contract keys and drops non-object items', () => {
    const { mediaParams } = extractMediaDataFromPlanParams({
      operation: 'concat',
      inputs: [
        { source: '{{a}}', trim_start_seconds: 1, speed: 1.5, custom_filter: 'sharpen' },
        'not-an-input',
        { source: '{{b}}', trim_end_seconds: 9 },
      ],
    });
    expect(mediaParams.inputs).toEqual([
      { source: '{{a}}', trim_start_seconds: 1, speed: 1.5 },
      { source: '{{b}}', trim_end_seconds: 9 },
    ]);
  });

  it('reads the new scalar params (concat globals, frame, overlay) verbatim with types preserved', () => {
    const { mediaOperation, mediaParams } = extractMediaDataFromPlanParams({
      operation: 'overlay',
      video: '{{v}}',
      image: '{{i}}',
      position: 'top_left',
      margin_px: 48,
      width_percent: 30,
      opacity: 0.6,
      start_seconds: 2,
      end_seconds: 8,
    });
    expect(mediaOperation).toBe('overlay');
    expect(mediaParams).toEqual({
      video: '{{v}}', image: '{{i}}', position: 'top_left',
      margin_px: 48, width_percent: 30, opacity: 0.6, start_seconds: 2, end_seconds: 8,
    });
    expect(typeof mediaParams.opacity).toBe('number');

    const frame = extractMediaDataFromPlanParams({
      operation: 'frame', input: '{{f}}', at_seconds: 3, image_format: 'png', width: 640,
    });
    expect(frame.mediaParams).toEqual({ input: '{{f}}', at_seconds: 3, image_format: 'png', width: 640 });
  });

  it('regression: concat with LITERAL FileRef sources survives a builder re-save roundtrip untouched - a re-save must NOT wipe agent-configured literal refs', () => {
    const clipA = { _type: 'file', path: '1/general/files/a_intro.mp4', name: 'intro.mp4', mimeType: 'video/mp4', size: 100 };
    const clipB = { _type: 'file', path: '1/general/files/b_main.mp4', name: 'main.mp4', mimeType: 'video/mp4', size: 200 };

    const exported = buildMediaPlanParams('concat', {
      inputs: [{ source: clipA, speed: 1.5 }, { source: clipB }],
      transition: 'crossfade',
      transition_seconds: 1.0,
    });
    expect(exported.inputs[0].source).toEqual(clipA);
    expect(exported.inputs[1].source).toEqual(clipB);

    // import -> re-export (the builder save cycle) keeps the objects byte-identical
    const { mediaOperation, mediaParams } = extractMediaDataFromPlanParams(exported);
    expect(buildMediaPlanParams(mediaOperation!, mediaParams)).toEqual(exported);

    // a random object WITHOUT a path is still coerced to '' (not a FileRef)
    expect(buildMediaPlanParams('concat', { inputs: [{ source: { foo: 'bar' } }] }).inputs[0].source).toBe('');
  });

  it('regression: frame input and overlay video/image accept LITERAL FileRefs and survive a re-save roundtrip', () => {
    const vid = { _type: 'file', path: '1/general/files/v_clip.mp4', name: 'clip.mp4', mimeType: 'video/mp4', size: 300 };
    const img = { _type: 'file', path: '1/general/files/l_logo.png', name: 'logo.png', mimeType: 'image/png', size: 12 };

    const frame = buildMediaPlanParams('frame', { input: vid, at_seconds: 2 });
    expect(frame.input).toEqual(vid);
    const frameRoundtrip = extractMediaDataFromPlanParams(frame);
    expect(buildMediaPlanParams(frameRoundtrip.mediaOperation!, frameRoundtrip.mediaParams)).toEqual(frame);

    const overlay = buildMediaPlanParams('overlay', { video: vid, image: img, opacity: 0.5 });
    expect(overlay.video).toEqual(vid);
    expect(overlay.image).toEqual(img);
    const overlayRoundtrip = extractMediaDataFromPlanParams(overlay);
    expect(buildMediaPlanParams(overlayRoundtrip.mediaOperation!, overlayRoundtrip.mediaParams)).toEqual(overlay);
  });

  it('removing a middle concat input from builder data leaves the other items intact on export (no cross-item corruption)', () => {
    const clipA = { _type: 'file', path: '1/f/a.mp4', name: 'a.mp4', mimeType: 'video/mp4', size: 1 };
    const inputs = [
      { source: clipA, speed: 1.5 },
      { source: '{{b}}', trim_start_seconds: 2 },
      { source: '{{c}}', trim_end_seconds: 9 },
    ];
    // the form removes index 1 with a plain filter - export the survivors
    const survivors = inputs.filter((_, i) => i !== 1);
    const params = buildMediaPlanParams('concat', { inputs: survivors });
    expect(params.inputs).toEqual([
      { source: clipA, speed: 1.5 },
      { source: '{{c}}', trim_end_seconds: 9 },
    ]);
  });

  it('drops a stale keep_original_audio from an audio-only mix export - the toggle only exists WITH a video and the backend rejects it otherwise', () => {
    // video was removed AFTER the user enabled keep_original_audio: the UI hides
    // the toggle but the stale value must not reach the plan.
    const params = buildMediaPlanParams('mix', {
      tracks: [{ source: '{{a}}' }],
      keep_original_audio: true,
      original_volume: 60,
    });
    expect(params.keep_original_audio).toBeUndefined();
    expect(params.original_volume).toBeUndefined();
  });
});
