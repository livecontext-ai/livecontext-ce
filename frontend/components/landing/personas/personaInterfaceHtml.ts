import { escapeHtml } from '@/app/workflows/builder/utils/interfaceHtmlUtils';
import { CREATOR_EXAMPLES, type CreatorExampleKey, type BusinessExampleKey, type PersonaKey } from './personas';
import { buildBusinessInterfaceHtml } from './businessInterfaceHtml';

type Copy = (key: string) => string;

const ICON_PATHS = {
  grid: '<rect x="3" y="3" width="7" height="7" rx="2"/><rect x="14" y="3" width="7" height="7" rx="2"/><rect x="3" y="14" width="7" height="7" rx="2"/><rect x="14" y="14" width="7" height="7" rx="2"/>',
  folder: '<path d="M3 7V5a2 2 0 0 1 2-2h5l3 4h6a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2Z"/>',
  check: '<path d="m5 12 4 4L19 6"/>',
  clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
  send: '<path d="m22 2-7 20-4-9L2 9Z"/><path d="m22 2-11 11"/>',
  file: '<path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8Z"/><path d="M14 2v6h6M8 13h8M8 17h5"/>',
  search: '<circle cx="10" cy="10" r="7"/><path d="m15 15 6 6"/>',
} as const;
const icon = (name: keyof typeof ICON_PATHS) => `<svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">${ICON_PATHS[name]}</svg>`;

/** App compositions at 1280 x 800, with Creator content in its portrait format. */
export function buildPersonaInterfaceHtml(persona: PersonaKey, theme: 'light' | 'dark', t: Copy, example: CreatorExampleKey = 'product', businessExample?: BusinessExampleKey): string {
  const text = (key: string) => escapeHtml(t(key));
  if (persona !== 'creator') return buildBusinessInterfaceHtml(persona, theme, t, businessExample, icon);
  if (persona === 'creator') {
    const CREATOR_STORY = CREATOR_EXAMPLES[example];
    const copy = (key: string) => text(`examples.${example}.${key}`);
    const visual = CREATOR_STORY.kind === 'video'
      ? `<video id="creator-video" muted autoplay loop playsinline preload="none" poster="${escapeHtml(CREATOR_STORY.poster ?? '')}" src="${escapeHtml(CREATOR_STORY.src)}" aria-label="${copy('title')}"></video><div class="creator-captions" aria-live="off">${(['subtitleFirst', 'subtitleSecond', 'subtitleThird'] as const).map((key, index) => `<span data-caption="${index}"${index ? ' hidden' : ''}>${copy(key)}</span>`).join('')}</div>`
      : `<img src="${escapeHtml(CREATOR_STORY.src)}" width="${CREATOR_STORY.width}" height="${CREATOR_STORY.height}" alt="${copy('title')}">`;
    // Captions stay escaped DOM text. This fixed script never interpolates copy.
    const playback = CREATOR_STORY.kind === 'video' ? `<script>(function(){const video=document.getElementById('creator-video');const captions=document.querySelectorAll('[data-caption]');const motion=window.matchMedia('(prefers-reduced-motion: reduce)');const cuts=${example === 'cuts'};function sync(){if(cuts&&!motion.matches&&!video.seeking){const time=video.currentTime;const next=time>=15?0:time>=9&&time<13?13:time>=3&&time<6?6:time;if(next!==time)video.currentTime=next;}const index=cuts?(video.currentTime>=13?2:video.currentTime>=6?1:0):Math.min(2,Math.floor(video.currentTime/4));captions.forEach(function(caption,i){caption.hidden=i!==index;});document.querySelectorAll('[data-segment]').forEach(function(segment){segment.dataset.active=String(Number(segment.dataset.segment)===index);});}function update(){if(motion.matches){video.autoplay=false;video.pause();}else{video.muted=true;video.play().catch(function(){});}sync();}video.addEventListener('timeupdate',sync);video.addEventListener('seeked',sync);motion.addEventListener('change',update);update();})();</script>` : '';
    const timeline = example === 'cuts' ? `<section class="creator-timeline" aria-label="${copy('timelineTitle')}"><h2>${copy('timelineTitle')}</h2><div class="timeline-labels"><span>${copy('originalLabel')}</span><strong>${copy('editedLabel')}</strong></div><div class="timeline-track"><span class="kept" data-segment="0" data-active="true" style="flex:3">0:00</span><span class="removed" style="flex:3"></span><span class="kept" data-segment="1" style="flex:3">0:06</span><span class="removed" style="flex:4"></span><span class="kept" data-segment="2" style="flex:2">0:13</span></div><div class="timeline-legend"><span><i class="kept"></i>${copy('keptLabel')}</span><span><i class="removed"></i>${copy('removedLabel')}</span></div></section>` : '';
    return `<!doctype html><html data-theme="${theme}"><head><meta charset="utf-8"><meta name="viewport" content="width=${CREATOR_STORY.width},initial-scale=1"><title>${copy('title')}</title><style>
      *{box-sizing:border-box}html,body{margin:0;width:${CREATOR_STORY.width}px;height:${CREATOR_STORY.height}px;overflow:hidden;background:#171717}
      main{position:relative;width:100%;height:100%;font-family:Arial,sans-serif;color:${theme === 'dark' ? '#f8f8fa' : '#24262b'}}
      img,video{display:block;width:100%;height:100%;object-fit:cover}
      .creator-format,.creator-location,.creator-annotations{background:${theme === 'dark' ? 'rgba(21,24,30,.84)' : 'rgba(255,255,255,.9)'};border:1px solid ${theme === 'dark' ? 'rgba(255,255,255,.22)' : 'rgba(255,255,255,.75)'};backdrop-filter:blur(24px);box-shadow:0 12px 48px #00000018}
      .creator-format,.creator-location{position:absolute;top:40px;left:40px;max-width:1000px;border-radius:48px;padding:18px 28px;font-size:36px;font-weight:600;letter-spacing:.3px}
      .creator-location{top:150px;font-size:32px}.creator-captions{position:absolute;top:48%;left:70px;right:70px;text-align:center;font-size:58px;line-height:1.25;font-weight:700;color:#fff;text-shadow:0 2px 8px #0009}.creator-captions span{background:#111c;padding:10px 18px;box-decoration-break:clone;border-radius:12px}.creator-captions [hidden]{display:none}[data-example=product] .creator-annotations{border-top:8px solid #bc7959}[data-example=product] .creator-location{letter-spacing:3px;text-transform:uppercase}
      .creator-annotations{position:absolute;bottom:40px;left:40px;right:40px;border-radius:32px;padding:30px 36px}
      .creator-annotations h1{display:flex;align-items:center;gap:14px;margin:0;font-size:40px;line-height:1.2;font-weight:600}
      .creator-annotations svg{width:42px;height:42px;flex-shrink:0}
      .creator-description{margin:18px 0 24px;font-size:40px;line-height:1.3}
      .creator-annotations h2{margin:0 0 10px;font-size:34px;line-height:1.2;font-weight:600;opacity:.65}
      .creator-timeline{margin-top:22px}.creator-timeline h2{opacity:1}.timeline-labels,.timeline-legend{display:flex;justify-content:space-between;gap:16px;font-size:30px}.timeline-track{display:flex;gap:6px;height:64px;margin:14px 0}.timeline-track span{min-width:0;display:grid;place-items:center;font-size:24px;border-radius:8px}.kept{background:#358b71;color:#fff}.removed{background:repeating-linear-gradient(135deg,#87929f55 0 8px,transparent 8px 16px);border:1px solid #87929f66}.timeline-track [data-active=true]{box-shadow:inset 0 0 0 4px #baf4db}.timeline-legend span{display:flex;align-items:center;gap:10px}.timeline-legend i{width:22px;height:22px;border-radius:4px}[data-example=cuts] .creator-description{font-size:34px;margin-bottom:18px}[data-example=cuts] .creator-annotations{padding:28px 32px}[data-example=cuts] .creator-captions{top:43%}
      .creator-hashtags{margin:0;font-size:38px;line-height:1.3;overflow-wrap:anywhere}
      </style></head><body><main data-layout="creator-story" data-example="${example}" data-width="${CREATOR_STORY.width}" data-height="${CREATOR_STORY.height}">${visual}<div class="creator-format">${copy('format')}</div><div class="creator-location">${copy('location')}</div><section class="creator-annotations" aria-label="${copy('descriptionLabel')}"><h1>${icon('check')}${copy('descriptionLabel')}</h1><p class="creator-description">${copy('description')}</p>${timeline || `<h2>${copy('hashtagsLabel')}</h2><p class="creator-hashtags">${copy('hashtags')}</p>`}</section></main>${playback}</body></html>`;
  }
}
