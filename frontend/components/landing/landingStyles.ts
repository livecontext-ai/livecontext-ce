export const landingStyles = `
  /* FAQ accordion: the chevron points down when collapsed and flips up when the
     <details> is open, so it reads as expandable. */
  .landing-root .faq-chevron {
    transition: transform 200ms ease;
  }

  .landing-root details.faq-item[open] .faq-chevron {
    transform: rotate(180deg);
  }

  .landing-root .eyebrow {
    font-size: 11px;
    text-transform: uppercase;
    letter-spacing: 0.08em;
    font-weight: 600;
    color: var(--text-muted);
  }

  .landing-root .hero-h1 {
    font-family: var(--font-outfit), 'Outfit', sans-serif;
    font-weight: 700;
    font-size: clamp(32px, 4.4vw, 50px);
    line-height: 1.08;
    letter-spacing: -0.025em;
    color: var(--text-primary);
  }

  .landing-root .hero-glow {
    position: absolute;
    inset: 0;
    pointer-events: none;
    background: var(--landing-hero-glow);
  }

  .landing-root .hero-prompt-caret {
    display: inline-block;
    width: 1.5px;
    height: 1.05em;
    margin-left: 2px;
    vertical-align: text-bottom;
    background: var(--text-primary);
    animation: hero-prompt-blink 1.05s steps(1) infinite;
  }

  @keyframes hero-prompt-blink {
    0%, 55% { opacity: 1; }
    56%, 100% { opacity: 0; }
  }

  /* ---- Persona showcase: the animated build-and-run scene in the hero. ---- */
  .landing-root .pshow-shell {
    max-width: 1100px;
    margin: 0 auto;
  }

  /* ---- "You, augmented": the agent squad + the selected agent's profile ---- */
  .landing-root .augment-squad {
    display: flex;
    justify-content: center;
    align-items: flex-end;
    padding: 26px 0 18px;
  }

  /* A stylised agent figure (head + shoulders bust), not a photo. The selected
     agent steps forward in colour; the others recede and grey out, so it reads
     as a squad standing behind you. */
  .landing-root .squad-bust {
    position: relative;
    display: flex;
    flex-direction: column;
    align-items: center;
    width: 118px;
    margin: 0 -10px;
    padding: 10px 8px 12px;
    border-radius: 18px;
    border: 1px solid transparent;
    background: transparent;
    cursor: pointer;
    transform: rotate(var(--squad-rot, 0deg)) translateY(var(--squad-drop, 0px)) scale(0.9);
    transform-origin: 50% 120%;
    filter: grayscale(1);
    opacity: 0.55;
    transition: transform 340ms cubic-bezier(0.22, 1, 0.36, 1), filter 340ms ease,
      opacity 340ms ease, background 340ms ease, border-color 340ms ease;
  }

  .landing-root .squad-bust:hover {
    opacity: 0.82;
    filter: grayscale(0.4);
  }

  .landing-root .squad-bust.active {
    transform: rotate(0deg) translateY(-14px) scale(1.12);
    filter: none;
    opacity: 1;
    border-color: var(--border-color);
    background: var(--bg-primary);
    box-shadow: var(--landing-frame-shadow);
  }

  .landing-root .squad-figure {
    position: relative;
    width: 78px;
    height: 78px;
    margin-bottom: 8px;
  }

  .landing-root .squad-figure-head {
    position: absolute;
    top: 2px;
    left: 50%;
    width: 30px;
    height: 30px;
    border-radius: 50%;
    transform: translateX(-50%);
    background: linear-gradient(160deg, var(--bg-tertiary), var(--bg-hover));
  }

  .landing-root .squad-figure-body {
    position: absolute;
    bottom: 0;
    left: 50%;
    width: 68px;
    height: 46px;
    border-radius: 34px 34px 14px 14px;
    transform: translateX(-50%);
    background: linear-gradient(160deg, var(--bg-tertiary), var(--bg-hover));
  }

  .landing-root .squad-figure-badge {
    position: absolute;
    bottom: 6px;
    left: 50%;
    display: flex;
    align-items: center;
    justify-content: center;
    width: 30px;
    height: 30px;
    border-radius: 50%;
    transform: translateX(-50%);
    color: var(--text-muted);
    background: var(--bg-primary);
    box-shadow: var(--landing-card-shadow);
  }

  .landing-root .squad-bust.active .squad-figure-head,
  .landing-root .squad-bust.active .squad-figure-body {
    background: var(--accent-primary);
  }

  .landing-root .squad-bust.active .squad-figure-badge {
    color: var(--accent-primary);
  }

  .landing-root .squad-label {
    font-size: 13px;
    font-weight: 700;
    color: var(--text-primary);
  }

  .landing-root .squad-role {
    font-size: 10px;
    color: var(--text-muted);
    opacity: 0;
    transition: opacity 220ms ease;
  }

  .landing-root .squad-bust.active .squad-role {
    opacity: 1;
  }

  @media (max-width: 640px) {
    .landing-root .augment-squad { flex-wrap: wrap; gap: 6px; }
    .landing-root .squad-bust { margin: 0; transform: scale(0.9); }
    .landing-root .squad-bust.active { transform: translateY(-4px) scale(1.02); }
  }

  /* The selected agent's profile: story on the left, live app (the star) on the
     right. Fixed columns so the layout is stable. */
  .landing-root .augment-stage {
    display: grid;
    grid-template-columns: minmax(0, 0.82fr) minmax(0, 1.18fr);
    gap: 22px;
    align-items: stretch;
    max-width: 1000px;
    margin: 8px auto 0;
  }

  @media (max-width: 899px) {
    .landing-root .augment-stage { grid-template-columns: 1fr; gap: 16px; }
  }

  .landing-root .augment-brief {
    display: flex;
    flex-direction: column;
    gap: 14px;
    min-width: 0;
  }

  .landing-root .augment-line {
    font-size: 15px;
    line-height: 1.5;
    color: var(--text-secondary);
  }

  .landing-root .augment-line-you {
    font-weight: 700;
    color: var(--text-primary);
  }

  .landing-root .augment-access {
    display: flex;
    flex-direction: column;
    gap: 10px;
    padding: 14px 16px;
    border-radius: 16px;
    border: 1px solid var(--border-color);
    background: var(--bg-primary);
    box-shadow: var(--landing-card-shadow);
  }

  .landing-root .augment-access-label {
    font-size: 11px;
    font-weight: 700;
    letter-spacing: 0.04em;
    text-transform: uppercase;
    color: var(--text-muted);
  }

  .landing-root .augment-access-logos {
    display: flex;
    flex-wrap: wrap;
    gap: 10px;
  }

  /* Each tool chip lights up (grey -> colour, scale) as the agent connects. */
  .landing-root .access-chip {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 42px;
    height: 42px;
    border-radius: 12px;
    border: 1px solid var(--border-color);
    background: var(--bg-secondary);
    filter: grayscale(1);
    opacity: 0.4;
    transform: scale(0.9);
    transition: filter 320ms ease, opacity 320ms ease,
      transform 320ms cubic-bezier(0.22, 1, 0.36, 1), box-shadow 320ms ease,
      border-color 320ms ease;
  }

  .landing-root .access-chip img {
    width: 22px;
    height: 22px;
    display: block;
  }

  .landing-root .access-chip.on {
    filter: none;
    opacity: 1;
    transform: scale(1);
    border-color: rgba(16, 185, 129, 0.45);
    box-shadow: 0 4px 12px -6px rgba(16, 185, 129, 0.4);
  }

  .landing-root .augment-app {
    display: flex;
    flex-direction: column;
    gap: 8px;
    min-width: 0;
  }

  /* The app card (reuses .pflow-inode chrome) fills the column and is the star. */
  .landing-root .augment-app .pflow-inode {
    height: 360px;
  }

  .landing-root .augment-underhood {
    font-size: 12px;
    text-align: center;
    color: var(--text-muted);
  }

  @media (max-width: 899px) {
    .landing-root .augment-app .pflow-inode { height: 320px; }
  }

  /* ---- "Meet your agents": cartoon roster + focused agent spotlight ---- */
  .landing-root .agentverse {
    max-width: 1080px;
    margin: 0 auto;
  }

  /* Your team: cartoon agents in a row, the focused one lifted, plus a "+1k"
     tile showing the team scales without limit. */
  .landing-root .roster-row {
    display: flex;
    justify-content: center;
    align-items: flex-start;
    gap: 4px;
    padding: 6px 0 8px;
  }

  .landing-root .roster {
    display: flex;
    justify-content: center;
    gap: 10px;
  }

  .landing-root .roster-agent {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 6px;
    padding: 6px;
    background: transparent;
    border: none;
    cursor: pointer;
    opacity: 0.6;
    filter: grayscale(0.55);
    transition: opacity 260ms ease, filter 260ms ease,
      transform 260ms cubic-bezier(0.22, 1, 0.36, 1);
  }

  .landing-root .roster-avatar {
    display: block;
    width: 62px;
    height: 62px;
    border-radius: 50%;
    overflow: hidden;
    background: var(--bg-tertiary);
    border: 2px solid transparent;
    box-shadow: var(--landing-card-shadow);
  }

  .landing-root .roster-avatar img {
    width: 100%;
    height: 100%;
    display: block;
  }

  .landing-root .roster-name {
    font-size: 11px;
    font-weight: 600;
    color: var(--text-muted);
  }

  .landing-root .roster-agent:hover {
    opacity: 0.9;
    filter: none;
  }

  .landing-root .roster-agent.active {
    opacity: 1;
    filter: none;
    transform: translateY(-4px) scale(1.12);
  }

  .landing-root .roster-agent.active .roster-avatar {
    border-color: var(--accent-primary);
    box-shadow: 0 0 0 3px rgba(16, 185, 129, 0.16), var(--landing-frame-shadow);
  }

  .landing-root .roster-agent.active .roster-name {
    color: var(--text-primary);
    font-weight: 700;
  }

  /* "+1k and more" tile: the agent team scales without limit. */
  .landing-root .roster-more {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 6px;
    padding: 6px;
    align-self: center;
  }

  .landing-root .roster-more-stack {
    position: relative;
    display: flex;
    align-items: center;
    height: 62px;
  }

  .landing-root .roster-more-face {
    width: 44px;
    height: 44px;
    border-radius: 50%;
    border: 2px solid var(--bg-primary);
    background: var(--bg-tertiary);
    margin-left: -18px;
    filter: grayscale(1);
    opacity: 0.6;
  }

  .landing-root .roster-more-face:first-child {
    margin-left: 0;
  }

  .landing-root .roster-more-plus {
    margin-left: -12px;
    z-index: 2;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    min-width: 36px;
    height: 34px;
    padding: 0 9px;
    border-radius: 999px;
    background: var(--accent-primary);
    color: var(--accent-foreground);
    font-size: 12px;
    font-weight: 700;
  }

  .landing-root .roster-caption {
    max-width: 540px;
    margin: 0 auto 16px;
    text-align: center;
    font-size: 12px;
    color: var(--text-muted);
  }

  .landing-root .spotlight {
    display: grid;
    grid-template-columns: minmax(0, 0.9fr) minmax(0, 1.1fr);
    gap: 22px;
    align-items: start;
    max-width: 1000px;
    margin: 0 auto;
  }

  @media (max-width: 899px) {
    .landing-root .spotlight { grid-template-columns: 1fr; gap: 18px; }
  }

  /* The focused agent + its possibilities. */
  .landing-root .agent-profile {
    display: flex;
    flex-direction: column;
    gap: 16px;
    padding: 20px;
    border-radius: 18px;
    border: 1px solid var(--border-color);
    background: var(--bg-primary);
    box-shadow: var(--landing-card-shadow);
  }

  .landing-root .agent-hero {
    display: flex;
    align-items: center;
    gap: 14px;
  }

  .landing-root .agent-hero-avatar {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 84px;
    height: 84px;
    border-radius: 50%;
    overflow: hidden;
    flex: none;
  }

  .landing-root .agent-hero-avatar img {
    width: 100%;
    height: 100%;
    display: block;
  }

  .landing-root .agent-hero-id {
    display: flex;
    flex-direction: column;
    gap: 2px;
    min-width: 0;
  }

  .landing-root .agent-hero-name {
    font-size: 18px;
    font-weight: 700;
    color: var(--text-primary);
  }

  .landing-root .agent-hero-role {
    font-size: 13px;
    color: var(--text-secondary);
  }

  .landing-root .agent-hero-model {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    margin-top: 2px;
    font-size: 11px;
    color: var(--text-muted);
  }

  .landing-root .agent-hero-augment {
    font-size: 14px;
    line-height: 1.5;
    color: var(--text-secondary);
  }

  .landing-root .agent-hero-you {
    font-weight: 700;
    color: var(--text-primary);
  }

  /* Customize affordance: a dashed, editable-looking button that says the whole
     agent is yours to shape. */
  .landing-root .agent-customize-row {
    display: flex;
    flex-direction: column;
    gap: 6px;
  }

  .landing-root .agent-customize {
    display: inline-flex;
    align-items: center;
    gap: 7px;
    align-self: flex-start;
    font-size: 13px;
    font-weight: 600;
    color: var(--accent-primary);
    background: var(--bg-secondary);
    border: 1px dashed var(--accent-primary);
    border-radius: 10px;
    padding: 8px 14px;
    cursor: pointer;
    transition: background 200ms ease, transform 200ms ease;
  }

  .landing-root .agent-customize:hover {
    background: var(--bg-tertiary);
    transform: translateY(-1px);
  }

  .landing-root .agent-customize-hint {
    font-size: 11px;
    color: var(--text-muted);
  }

  .landing-root .agent-kit {
    display: flex;
    flex-direction: column;
    gap: 14px;
    padding-top: 16px;
    border-top: 1px solid var(--border-color);
  }

  .landing-root .agent-kit-section {
    display: flex;
    flex-direction: column;
    gap: 7px;
  }

  .landing-root .agent-kit-label {
    font-size: 10px;
    font-weight: 700;
    letter-spacing: 0.05em;
    text-transform: uppercase;
    color: var(--text-muted);
  }

  .landing-root .agent-kit-resources {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
  }

  .landing-root .agent-kit-resource {
    display: inline-flex;
    align-items: center;
    gap: 5px;
    font-size: 12px;
    color: var(--text-secondary);
    background: var(--bg-secondary);
    border: 1px solid var(--border-color);
    border-radius: 9px;
    padding: 4px 9px;
  }

  .landing-root .agent-kit-tools {
    display: flex;
    gap: 8px;
  }

  .landing-root .agent-kit-tool {
    width: 26px;
    height: 26px;
  }

  .landing-root .agent-kit-foot {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
  }

  .landing-root .agent-kit-chip {
    display: inline-flex;
    align-items: center;
    gap: 5px;
    font-size: 11px;
    color: var(--text-secondary);
    background: var(--bg-tertiary);
    border-radius: 999px;
    padding: 4px 10px;
  }

  .landing-root .agent-credit {
    margin-top: 14px;
    text-align: center;
    font-size: 10px;
    color: var(--text-muted);
    opacity: 0.7;
  }

  /* The workpanel avatar now holds the cartoon avatar image. */
  .landing-root .workpanel-avatar {
    overflow: hidden;
    background: var(--bg-tertiary);
  }

  .landing-root .workpanel-avatar img {
    width: 100%;
    height: 100%;
    display: block;
  }

  @media (prefers-reduced-motion: reduce) {
    .landing-root .roster-agent { transition: none; }
  }

  .landing-root .agentverse-stage {
    display: grid;
    grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
    gap: 24px;
    align-items: center;
  }

  @media (max-width: 899px) {
    .landing-root .agentverse-stage { grid-template-columns: 1fr; gap: 18px; }
  }

  /* You in the middle, agents around you on a ring. */
  .landing-root .constellation {
    position: relative;
    width: 100%;
    max-width: 470px;
    margin: 0 auto;
    aspect-ratio: 1 / 0.92;
  }

  .landing-root .constellation-links {
    position: absolute;
    inset: 0;
    width: 100%;
    height: 100%;
    pointer-events: none;
  }

  .landing-root .constellation-link {
    stroke: var(--border-color);
    stroke-width: 0.4;
    stroke-dasharray: 1.4 1.6;
    opacity: 0.7;
  }

  .landing-root .constellation-link.active {
    stroke: #10b981;
    stroke-width: 0.6;
    opacity: 1;
    animation: constellation-flow 1s linear infinite;
  }

  @keyframes constellation-flow {
    to { stroke-dashoffset: -6; }
  }

  .landing-root .you-figure {
    position: absolute;
    left: 50%;
    top: 52%;
    transform: translate(-50%, -50%);
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 4px;
    z-index: 2;
    pointer-events: none;
  }

  .landing-root .you-svg {
    width: 58px;
    height: 58px;
  }

  .landing-root .you-svg circle,
  .landing-root .you-svg path {
    fill: none;
    stroke: var(--text-muted);
    stroke-width: 2;
    stroke-dasharray: 3 3;
    stroke-linecap: round;
    opacity: 0.6;
  }

  .landing-root .you-label {
    font-size: 11px;
    font-weight: 600;
    letter-spacing: 0.06em;
    text-transform: uppercase;
    color: var(--text-muted);
  }

  .landing-root .agent-node {
    position: absolute;
    transform: translate(-50%, -50%);
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 6px;
    padding: 0;
    background: transparent;
    border: none;
    cursor: pointer;
    z-index: 3;
    opacity: 0.72;
    transition: transform 280ms cubic-bezier(0.22, 1, 0.36, 1), opacity 280ms ease;
  }

  .landing-root .agent-node-avatar {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 54px;
    height: 54px;
    border-radius: 50%;
    background: var(--bg-primary);
    color: var(--text-secondary);
    border: 1px solid var(--border-color);
    box-shadow: var(--landing-card-shadow);
    transition: background 260ms ease, color 260ms ease, border-color 260ms ease,
      box-shadow 260ms ease;
  }

  .landing-root .agent-node-name {
    font-size: 11px;
    font-weight: 600;
    color: var(--text-muted);
  }

  .landing-root .agent-node:hover,
  .landing-root .agent-node.hovered {
    opacity: 1;
  }

  .landing-root .agent-node:hover .agent-node-avatar,
  .landing-root .agent-node.hovered .agent-node-avatar {
    border-color: var(--accent-primary);
  }

  .landing-root .agent-node.active {
    opacity: 1;
    transform: translate(-50%, -50%) scale(1.14);
    z-index: 6;
  }

  .landing-root .agent-node.active .agent-node-avatar {
    background: var(--accent-primary);
    color: var(--accent-foreground);
    border-color: var(--accent-primary);
    box-shadow: 0 0 0 4px rgba(16, 185, 129, 0.18), var(--landing-frame-shadow);
  }

  .landing-root .agent-node.active .agent-node-name {
    color: var(--text-primary);
    font-weight: 700;
  }

  /* Hover profile card, anchored above the agent. */
  .landing-root .agent-fiche-anchor {
    position: absolute;
    transform: translate(var(--fiche-shift, -50%), calc(-100% - 14px));
    z-index: 20;
    pointer-events: none;
  }

  .landing-root .agent-fiche {
    width: 226px;
    padding: 12px;
    border-radius: 14px;
    background: var(--bg-primary);
    border: 1px solid var(--border-color);
    box-shadow: var(--landing-frame-shadow);
    display: flex;
    flex-direction: column;
    gap: 10px;
    animation: fiche-in 180ms ease;
  }

  @keyframes fiche-in {
    from { opacity: 0; transform: translateY(4px); }
    to { opacity: 1; transform: none; }
  }

  .landing-root .agent-fiche-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
  }

  .landing-root .agent-fiche-name {
    font-size: 13px;
    font-weight: 700;
    color: var(--text-primary);
  }

  .landing-root .agent-fiche-model {
    display: inline-flex;
    align-items: center;
    gap: 3px;
    font-size: 10px;
    color: var(--text-muted);
    white-space: nowrap;
  }

  .landing-root .agent-fiche-section {
    display: flex;
    flex-direction: column;
    gap: 5px;
  }

  .landing-root .agent-fiche-label {
    font-size: 9px;
    font-weight: 700;
    letter-spacing: 0.05em;
    text-transform: uppercase;
    color: var(--text-muted);
  }

  .landing-root .agent-fiche-resources {
    display: flex;
    flex-wrap: wrap;
    gap: 5px;
  }

  .landing-root .agent-fiche-resource {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    font-size: 11px;
    color: var(--text-secondary);
    background: var(--bg-secondary);
    border: 1px solid var(--border-color);
    border-radius: 8px;
    padding: 3px 7px;
  }

  .landing-root .agent-fiche-tools {
    display: flex;
    gap: 6px;
  }

  .landing-root .agent-fiche-tool {
    width: 20px;
    height: 20px;
  }

  .landing-root .agent-fiche-foot {
    display: flex;
    flex-wrap: wrap;
    gap: 5px;
  }

  .landing-root .agent-fiche-chip {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    font-size: 10px;
    color: var(--text-secondary);
    background: var(--bg-tertiary);
    border-radius: 999px;
    padding: 3px 8px;
  }

  /* The focused agent at work. */
  .landing-root .agentverse-work {
    min-width: 0;
  }

  .landing-root .workpanel {
    display: flex;
    flex-direction: column;
    border-radius: 18px;
    border: 1px solid var(--border-color);
    background: var(--bg-primary);
    box-shadow: var(--landing-card-shadow);
    overflow: hidden;
  }

  .landing-root .workpanel-head {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 13px 16px;
    border-bottom: 1px solid var(--border-color);
    background: var(--bg-secondary);
  }

  .landing-root .workpanel-avatar {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 32px;
    height: 32px;
    border-radius: 50%;
    background: var(--accent-primary);
    color: var(--accent-foreground);
    flex: none;
  }

  .landing-root .workpanel-title {
    display: flex;
    flex-direction: column;
    min-width: 0;
    flex: 1;
  }

  .landing-root .workpanel-name {
    font-size: 13px;
    font-weight: 700;
    color: var(--text-primary);
  }

  .landing-root .workpanel-sub {
    font-size: 11px;
    color: var(--text-muted);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .landing-root .workpanel-you {
    color: var(--text-secondary);
    font-weight: 600;
  }

  .landing-root .workpanel-status {
    font-size: 10px;
    font-weight: 700;
    text-transform: uppercase;
    letter-spacing: 0.05em;
    color: #3b82f6;
    background: rgba(59, 130, 246, 0.12);
    border-radius: 999px;
    padding: 3px 9px;
    flex: none;
  }

  .landing-root .workpanel-status.done {
    color: #059669;
    background: rgba(16, 185, 129, 0.14);
  }

  .landing-root .workpanel-body {
    display: flex;
    flex-direction: column;
  }

  .landing-root .workpanel-timeline {
    padding: 12px 14px;
    display: flex;
    flex-direction: column;
    gap: 8px;
  }

  .landing-root .work-step {
    display: flex;
    align-items: center;
    gap: 10px;
    animation: work-step-in 280ms cubic-bezier(0.22, 1, 0.36, 1);
  }

  @keyframes work-step-in {
    from { opacity: 0; transform: translateX(-6px); }
    to { opacity: 1; transform: none; }
  }

  .landing-root .work-step-glyph {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 26px;
    height: 26px;
    border-radius: 8px;
    background: var(--bg-secondary);
    border: 1px solid var(--border-color);
    color: var(--text-secondary);
    flex: none;
  }

  .landing-root .agentverse-step-logo {
    width: 16px;
    height: 16px;
    display: block;
  }

  .landing-root .work-step-text {
    display: flex;
    align-items: baseline;
    gap: 8px;
    min-width: 0;
    flex: 1;
  }

  .landing-root .work-step-name {
    font-size: 13px;
    font-weight: 600;
    color: var(--text-primary);
  }

  .landing-root .work-step-detail {
    font-size: 12px;
    color: var(--text-muted);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .landing-root .work-step.is-reason .work-step-text {
    font-size: 12px;
    color: var(--text-muted);
    font-style: italic;
  }

  .landing-root .work-step.is-reason .work-step-glyph {
    background: transparent;
    border: none;
    color: var(--text-muted);
  }

  .landing-root .work-step-status {
    flex: none;
    color: #059669;
    display: flex;
    align-items: center;
  }

  .landing-root .work-step.running .work-step-status {
    color: #3b82f6;
  }

  .landing-root .work-spinner {
    display: block;
    width: 12px;
    height: 12px;
    border-radius: 50%;
    border: 2px solid rgba(59, 130, 246, 0.25);
    border-top-color: #3b82f6;
    animation: pshow-spin 720ms linear infinite;
  }

  /* The delivered interface, revealed at the very end like a generated image,
     shown as the content of a browser-style tab (no info header). */
  .landing-root .work-canvas {
    margin: 6px 14px 14px;
    border: 1px solid var(--border-color);
    border-radius: 12px;
    overflow: hidden;
    background: var(--bg-secondary);
    box-shadow: var(--landing-node-shadow);
    animation: work-step-in 300ms cubic-bezier(0.22, 1, 0.36, 1);
  }

  .landing-root .work-canvas-tabs {
    display: flex;
    align-items: flex-end;
    gap: 8px;
    padding: 6px 8px 0;
  }

  .landing-root .work-canvas-tab {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    max-width: 100%;
    font-size: 11px;
    font-weight: 600;
    color: var(--text-primary);
    background: var(--bg-primary);
    border: 1px solid var(--border-color);
    border-bottom: none;
    border-radius: 9px 9px 0 0;
    padding: 6px 12px;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .landing-root .work-canvas-body {
    height: 150px;
    overflow: hidden;
    background: #fbfaf8;
    border-top: 1px solid var(--border-color);
  }

  /* Image-generation reveal: blur -> sharp with a scan sweep. The iframe is
     rendered tall (fully laid out, no inner scroll / autofocus jump) and clipped
     by the body so the recognisable top of the app shows. */
  .landing-root .work-gen {
    position: relative;
    width: 100%;
    height: 100%;
    animation: work-gen-clear 1000ms ease-out;
  }

  .landing-root .work-gen .pshow-app-iframe {
    width: 100%;
    height: 460px;
    border: 0;
    display: block;
  }

  .landing-root .work-gen-scan {
    position: absolute;
    inset: 0;
    pointer-events: none;
    background: linear-gradient(120deg, transparent 34%, rgba(16, 185, 129, 0.3) 50%, transparent 66%);
    background-size: 300% 100%;
    animation: work-gen-scan 1000ms ease-out forwards;
  }

  @keyframes work-gen-clear {
    0% { filter: blur(16px) saturate(0.7); opacity: 0; transform: scale(1.03); }
    55% { filter: blur(5px); opacity: 1; }
    100% { filter: blur(0); opacity: 1; transform: none; }
  }

  @keyframes work-gen-scan {
    0% { background-position: 120% 0; opacity: 1; }
    100% { background-position: -140% 0; opacity: 0; }
  }

  /* Sub-agent avatars in the resource chips and the activity timeline. */
  .landing-root .agent-kit-resource-avatar {
    width: 18px;
    height: 18px;
    border-radius: 50%;
    background: var(--bg-tertiary);
    display: block;
  }

  .landing-root .agentverse-step-avatar {
    width: 22px;
    height: 22px;
    border-radius: 50%;
    background: var(--bg-tertiary);
    display: block;
  }

  /* Bare delivered-app content (no header / no tab): the whole app, scaled down
     to fit so nothing is cropped. */
  .landing-root .work-view {
    margin: 8px 14px 14px;
    border: 1px solid var(--border-color);
    border-radius: 12px;
    overflow: hidden;
    background: #fbfaf8;
    box-shadow: var(--landing-node-shadow);
  }

  .landing-root .work-view-scale {
    position: relative;
    width: 100%;
    overflow: hidden;
  }

  .landing-root .work-view-iframe {
    border: 0;
    display: block;
    transform-origin: top left;
    background: #fbfaf8;
  }

  @media (prefers-reduced-motion: reduce) {
    .landing-root .work-canvas,
    .landing-root .work-gen { animation: none; filter: none; }
    .landing-root .work-gen-scan { display: none; }
  }

  @media (prefers-reduced-motion: reduce) {
    .landing-root .constellation-link.active,
    .landing-root .work-step,
    .landing-root .work-spinner,
    .landing-root .agent-fiche {
      animation: none;
    }
    .landing-root .agent-node { transition: none; }
  }

  /* Fixed-size conversation card: constant height and width, so it never grows
     or shrinks with the message content (the prompt and the agent's status line
     always sit in the same box). */
  .landing-root .pshow-chat {
    display: flex;
    flex-direction: column;
    justify-content: center;
    gap: 10px;
    height: 128px;
    padding: 16px 18px;
    border-radius: 16px;
    border: 1px solid var(--border-color);
    background: var(--bg-primary);
    box-shadow: var(--landing-card-shadow);
  }

  .landing-root .pshow-agent-line {
    min-width: 0;
  }

  /* Persona fan: cards spread like a hand of cards, active card lifted upright.
     Extra top padding gives the rotated cards room so nothing clips against the
     section above. */
  .landing-root .pshow-fan {
    display: flex;
    justify-content: center;
    align-items: flex-end;
    padding: 22px 0 16px;
  }

  .landing-root .pshow-fan-card {
    display: flex;
    flex-direction: column;
    align-items: center;
    width: 120px;
    margin: 0 -9px;
    padding: 8px 8px 10px;
    border-radius: 16px;
    border: 1px solid var(--border-color);
    background: var(--bg-primary);
    box-shadow: var(--landing-card-shadow);
    cursor: pointer;
    transform: rotate(var(--fan-rot, 0deg)) translateY(var(--fan-drop, 0px));
    transform-origin: 50% 130%;
    transition: transform 320ms cubic-bezier(0.22, 1, 0.36, 1), box-shadow 320ms ease, border-color 320ms ease;
  }

  .landing-root .pshow-fan-card:hover {
    transform: rotate(var(--fan-rot, 0deg)) translateY(calc(var(--fan-drop, 0px) - 8px));
  }

  .landing-root .pshow-fan-card.active {
    z-index: 2;
    border-color: var(--accent-primary);
    box-shadow: var(--landing-frame-shadow);
    transform: rotate(0deg) translateY(-14px) scale(1.06);
  }

  /* On the fanned (inactive) cards the role line would collide with the
     neighbour card; show it only on the lifted active card. */
  .landing-root .pshow-fan-role {
    opacity: 0;
    transition: opacity 220ms ease;
  }

  .landing-root .pshow-fan-card.active .pshow-fan-role {
    opacity: 1;
  }

  .landing-root .pshow-fan-media {
    position: relative;
    display: block;
    width: 100%;
    aspect-ratio: 4 / 3;
    border-radius: 10px;
    overflow: hidden;
    background: var(--bg-tertiary);
  }

  .landing-root .pshow-fan-img {
    position: absolute;
    inset: 0;
    display: block;
    width: 100%;
    height: 100%;
    object-fit: cover;
    transition: opacity 240ms ease;
  }

  .landing-root .pshow-fan-fallback {
    position: absolute;
    inset: 0;
    display: flex;
    align-items: center;
    justify-content: center;
    color: var(--text-muted);
    background: linear-gradient(135deg, var(--bg-tertiary) 0%, var(--bg-hover) 100%);
  }

  .landing-root .pshow-fan-label {
    margin-top: 8px;
    font-size: 12px;
    font-weight: 700;
    color: var(--text-primary);
  }

  .landing-root .pshow-fan-role {
    font-size: 10px;
    color: var(--text-muted);
  }

  @media (max-width: 640px) {
    .landing-root .pshow-fan {
      flex-wrap: wrap;
      gap: 8px;
    }

    .landing-root .pshow-fan-card {
      width: 96px;
      margin: 0;
      transform: none;
    }

    .landing-root .pshow-fan-card.active {
      transform: translateY(-4px) scale(1.04);
    }
  }

  .landing-root .pshow-stage {
    display: flex;
    flex-direction: column;
    gap: 14px;
    max-width: 980px;
    margin: 16px auto 0;
  }

  /* Work row: the builder canvas on the left, a handoff arrow, and the interface
     card on the right. The columns are fixed so the layout is stable; the
     interface card fills in when the run reaches it. */
  .landing-root .pshow-work {
    display: grid;
    grid-template-columns: minmax(0, 1fr) 28px 344px;
    align-items: stretch;
    gap: 8px;
  }

  .landing-root .pshow-handoff {
    display: flex;
    align-items: center;
    justify-content: center;
    color: var(--text-muted);
    opacity: 0.5;
    transition: color 320ms ease, opacity 320ms ease;
  }

  .landing-root .pshow-work.showapp .pshow-handoff {
    color: #10b981;
    opacity: 1;
  }

  .landing-root .pshow-side {
    min-width: 0;
    height: 340px;
  }

  /* The interface app, revealed inside the card when the run completes. */
  .landing-root .pshow-app-reveal {
    width: 100%;
    height: 100%;
    animation: pshow-reveal 440ms cubic-bezier(0.22, 1, 0.36, 1);
  }

  @media (max-width: 899px) {
    .landing-root .pshow-work {
      grid-template-columns: 1fr;
      gap: 10px;
    }

    .landing-root .pshow-handoff {
      transform: rotate(90deg);
    }

    .landing-root .pshow-side {
      height: 300px;
    }
  }

  /* The live builder canvas frame: a fixed-height, self-contained box that holds
     the read-only ReactFlow. Its height stays constant across every phase, so
     the whole scene has a stable footprint. */
  .landing-root .pflow-frame {
    position: relative;
    width: 100%;
    height: 340px;
    border-radius: 16px;
    border: 1px solid var(--border-color);
    background: var(--bg-secondary);
    box-shadow: var(--landing-card-shadow);
    overflow: hidden;
  }

  .landing-root .pflow-frame-skeleton {
    width: 100%;
    height: 100%;
    background: var(--bg-secondary);
    animation: pshow-breathe 2.4s ease-in-out infinite;
  }

  /* ReactFlow root: transparent so the frame surface and dotted Background show
     through. */
  .landing-root .pflow-canvas {
    width: 100%;
    height: 100%;
    background: transparent;
  }

  @media (max-width: 640px) {
    .landing-root .pflow-frame {
      height: 300px;
    }
  }

  .landing-root .showcase-bubble {
    display: flex;
    align-items: flex-start;
    gap: 8px;
    max-width: 92%;
    padding: 10px 14px;
    border-radius: 14px;
    font-size: 13px;
    line-height: 1.45;
    text-align: left;
  }

  .landing-root .showcase-bubble-user {
    align-self: flex-end;
    min-height: 40px;
    background: var(--accent-primary);
    color: var(--accent-foreground);
    border-bottom-right-radius: 4px;
  }

  .landing-root .showcase-bubble-user .hero-prompt-caret {
    background: var(--accent-foreground);
  }

  .landing-root .showcase-bubble-agent {
    align-self: flex-start;
    background: var(--bg-tertiary);
    color: var(--text-secondary);
    border-bottom-left-radius: 4px;
    animation: pshow-pop 320ms cubic-bezier(0.22, 1, 0.36, 1);
  }

  /* Real builder node card on the ReactFlow canvas: same shell the builder
     renders (rounded, border-2, ~190px, surface, p-3) + the real NodeHeader
     inside. The border color comes from the platform's own getStatusBorderColor,
     so pending/running/completed match a live run exactly. */
  .landing-root .pflow-node {
    position: relative;
    width: 190px;
    padding: 12px;
    border-radius: 16px;
    border: 2px solid var(--border-color);
    background: var(--bg-primary);
    box-shadow: var(--landing-node-shadow);
    /* Entrance: each node slides up + scales in as the workflow creates it. */
    opacity: 0;
    transform: translateY(16px) scale(0.92);
    transition: border-color 200ms ease, box-shadow 200ms ease,
      opacity 340ms cubic-bezier(0.22, 1, 0.36, 1),
      transform 340ms cubic-bezier(0.22, 1, 0.36, 1);
  }

  .landing-root .pflow-node.is-visible {
    opacity: 1;
    transform: translateY(0) scale(1);
  }

  .landing-root .pflow-node[data-status='running'] {
    box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.15), var(--landing-node-shadow);
  }

  /* ReactFlow handles as round dots, same look as the builder (dot in the border
     color, ring in the surface color). */
  .landing-root .pflow-handle.react-flow__handle {
    width: 10px;
    height: 10px;
    min-width: 0;
    min-height: 0;
    border-radius: 999px;
    background: var(--border-color);
    border: 2px solid var(--bg-primary);
  }

  /* Blue running "scan" overlay, identical to the canvas node's shimmer. */
  .landing-root .pshow-node-shimmer {
    position: absolute;
    inset: 0;
    pointer-events: none;
    border-radius: 16px;
    background: linear-gradient(90deg, transparent 0%, rgba(59, 130, 246, 0.15) 50%, transparent 100%);
    background-size: 200% 100%;
    animation: shimmer-scan 2.5s ease-in-out infinite;
    z-index: 5;
  }

  /* Run-status chip, bottom-right like the builder's NodeStatusBadge. */
  .landing-root .pshow-node-status {
    position: absolute;
    bottom: 8px;
    right: 8px;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 18px;
    height: 18px;
    border-radius: 999px;
    color: #059669;
    z-index: 6;
  }

  .landing-root .pflow-node[data-status='completed'] .pshow-node-status {
    background: rgba(16, 185, 129, 0.14);
  }

  .landing-root .pshow-spinner {
    width: 12px;
    height: 12px;
    border-radius: 999px;
    border: 2px solid rgba(59, 130, 246, 0.25);
    border-top-color: #3b82f6;
    animation: pshow-spin 720ms linear infinite;
  }

  /* The interface NODE on the canvas: same shell as the builder's
     InterfacePreviewNode (rounded, status border-2, node header on top, the live
     interface filling the body). Fixed size so the ReactFlow layout is stable. */
  .landing-root .pflow-inode {
    position: relative;
    display: flex;
    flex-direction: column;
    width: 100%;
    height: 100%;
    border-radius: 16px;
    border: 2px solid var(--border-color);
    background: var(--bg-primary);
    box-shadow: var(--landing-node-shadow);
    overflow: hidden;
    transition: border-color 300ms ease;
  }

  .landing-root .pshow-work.showapp .pflow-inode {
    animation: pshow-card-pop 440ms cubic-bezier(0.22, 1, 0.36, 1);
  }

  .landing-root .pshow-inode-head {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 8px 10px;
    border-bottom: 1px solid var(--border-color);
    background: var(--bg-secondary);
  }

  .landing-root .pshow-inode-label {
    font-size: 13px;
    font-weight: 600;
    color: var(--text-primary);
    white-space: nowrap;
  }

  .landing-root .pshow-inode-url {
    flex: 1;
    min-width: 0;
    text-align: right;
    font-size: 11px;
    color: var(--text-muted);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .landing-root .pshow-inode-badge {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 18px;
    height: 18px;
    border-radius: 999px;
    background: rgba(16, 185, 129, 0.14);
    color: #059669;
    flex: none;
  }

  .landing-root .pshow-inode-body {
    flex: 1;
    min-height: 0;
    display: flex;
  }

  /* The live interface iframe (real HTML/CSS/JS, sandboxed). */
  .landing-root .pshow-app-iframe {
    width: 100%;
    height: 100%;
    min-height: 0;
    border: 0;
    display: block;
    background: #fbfaf8;
  }

  /* Placeholder shown in the interface node body until the run completes. */
  .landing-root .pflow-inode-wait {
    flex: 1;
    display: flex;
    align-items: center;
    justify-content: center;
    background: var(--bg-secondary);
  }

  .landing-root .pshow-placeholder-label {
    font-size: 13px;
    color: var(--text-muted);
    animation: pshow-breathe 2.4s ease-in-out infinite;
  }

  @keyframes pshow-pop {
    0% { opacity: 0; transform: translateY(8px) scale(0.97); }
    100% { opacity: 1; transform: translateY(0) scale(1); }
  }

  @keyframes pshow-grow {
    0% { transform: scaleY(0); }
    100% { transform: scaleY(1); }
  }

  @keyframes pshow-flow {
    0% { background-position: 0 -100%; }
    100% { background-position: 0 100%; }
  }

  @keyframes pshow-spin {
    to { transform: rotate(360deg); }
  }

  @keyframes pshow-breathe {
    0%, 100% { opacity: 0.55; }
    50% { opacity: 1; }
  }

  @keyframes pshow-reveal {
    0% { opacity: 0; transform: translateY(10px) scale(0.98); }
    100% { opacity: 1; transform: translateY(0) scale(1); }
  }

  @keyframes pshow-card-pop {
    0% { transform: scale(0.985); }
    55% { transform: scale(1.012); }
    100% { transform: scale(1); }
  }

  @media (prefers-reduced-motion: reduce) {
    .landing-root .showcase-bubble-agent,
    .landing-root .pshow-placeholder-label,
    .landing-root .pshow-spinner,
    .landing-root .pshow-app-reveal,
    .landing-root .pshow-work.showapp .pflow-inode {
      animation: none;
    }

    /* Nodes appear in place, no slide. */
    .landing-root .pflow-node {
      opacity: 1;
      transform: none;
      transition: none;
    }
  }

  /* Aggregate counters (SocialProofStrip). */
  .landing-root .metric-value {
    font-family: var(--font-outfit), 'Outfit', sans-serif;
    font-weight: 700;
    font-size: clamp(28px, 3.4vw, 38px);
    letter-spacing: -0.02em;
    color: var(--text-primary);
  }

  /* HowItWorks step badges. */
  .landing-root .step-number {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 30px;
    height: 30px;
    border-radius: 999px;
    font-size: 13px;
    font-weight: 700;
    background: var(--accent-primary);
    color: var(--accent-foreground);
  }

  /* The demo backdrop: the panel the hero's app window floats on, and now also the one
     under the agents and agenda showcases, so the three product shots on a page read as
     one family instead of three loose cards. Its texture was inlined in the old hero's
     HTML; it is a file now, and a small one: under an 86% veil at saturate(.1) almost
     nothing of the photograph survives, so it ships at half resolution as webp (7 KB
     against the 107 KB jpg it replaced). */
  .landing-root .landing-demo-panel {
    position: relative;
    overflow: hidden;
    border-radius: 24px;
    /* Wide enough for the backdrop to read as a frame, like the hero's own bands. */
    padding: 40px;
    background: #eef1f7;
    box-shadow: 0 30px 44px -24px rgba(16, 22, 38, .2);
  }

  .landing-root .landing-demo-panel::before {
    content: "";
    position: absolute;
    inset: 0;
    z-index: 0;
    pointer-events: none;
    background: linear-gradient(rgba(249, 250, 253, .86), rgba(249, 250, 253, .86)), url(/landing/hero-panel-texture.webp) center center / cover no-repeat;
    filter: saturate(.1) brightness(1.08);
  }

  .landing-root.dark .landing-demo-panel {
    background: #1f1e1b;
    box-shadow: 0 30px 44px -24px rgba(0, 0, 0, .55);
  }

  .landing-root.dark .landing-demo-panel::before {
    filter: saturate(.06) brightness(.32);
  }

  .landing-root .landing-demo-panel > * {
    position: relative;
    z-index: 1;
  }

  @media (max-width: 640px) {
    .landing-root .landing-demo-panel {
      padding: 12px;
      border-radius: 18px;
    }
  }

  /* A showcase can BLEED off the panel on the sides it names, so the backdrop reads as a
     frame on the other sides only and the window looks like a view into something larger.
     Agents bleeds right and bottom (band on the left and top), the agenda bleeds bottom
     (band on the left, right and top). */
  .landing-root .landing-demo-panel[data-bleed~='right'] {
    padding-right: 0;
  }

  .landing-root .landing-demo-panel[data-bleed~='bottom'] {
    padding-bottom: 0;
  }

  /* A rounded corner on a side that bleeds leaves a sliver of backdrop showing through
     it, which reads as a mistake rather than a crop. */
  .landing-root .landing-demo-panel[data-bleed~='right'] > * {
    border-top-right-radius: 0;
    border-bottom-right-radius: 0;
  }

  .landing-root .landing-demo-panel[data-bleed~='bottom'] > * {
    border-bottom-left-radius: 0;
    border-bottom-right-radius: 0;
  }

  /* The agenda runs SMALLER than the window it is drawn at, and is held at roughly 70% of
     it: four weeks of the month, cut where it plainly continues. Scaling rather than
     cropping harder is what gives the backdrop its wider band on top and down the sides.
     26.5rem = the 64px top band plus 0.7 of the 607px window taken at 0.85. */
  /* The agenda takes the hero's proportions, which are cohere.com/fr's: a band of roughly
     11% down each side, a deep band on top, and no band at all at the bottom, where the
     calendar runs off the backdrop. The window shrinks rather than the backdrop growing,
     because the point is the FRAME: at 40px of padding the panel read as a border around a
     screenshot instead of a ground the product sits on.

     The side padding is the REMAINDER of the band, not the whole of it: the 0.78 scale
     below already insets the window by about 8.6% a side, so 4% of padding lands the
     visible band at roughly 12.5%, which is Cohere's. Setting 11% here gave 19.6%. */
  .landing-root .landing-demo-panel[data-crop='agenda'] {
    height: 31rem;
    padding: 88px 4% 0;
  }

  .landing-root .landing-demo-panel[data-crop='agenda'] > * {
    transform: scale(.78);
    transform-origin: top center;
  }

  @media (max-width: 640px) {
    .landing-root .landing-demo-panel[data-crop='agenda'] {
      height: 19rem;
      padding: 28px 18px 0;
    }
  }

  /* Persona pills floating on the hero demo card. They were drawn inside the old
     hero iframe; that iframe is no longer the hero visual, and these are the only
     in-page links to the /for/<persona> pages, so they live here now. */
  .landing-root .persona-hero-stage {
    position: relative;
  }

  /* On the backdrop panel, where they were drawn inside the old hero: the panel's top
     band is theirs, so they sit above the window without covering its top bar. Centred in
     that band (100px tall, the pills 44), which is why this is a measured number and not
     a margin. */
  .landing-root .persona-hero-nav {
    position: absolute;
    top: 28px;
    left: 50%;
    transform: translateX(-50%);
    z-index: 8;
    display: inline-flex;
    gap: 2px;
    max-width: calc(100% - 24px);
    padding: 4px;
    border: 1px solid var(--border-color);
    border-radius: 13px;
    background: color-mix(in srgb, var(--bg-primary) 92%, transparent);
    backdrop-filter: blur(10px);
    box-shadow: 0 8px 24px rgba(16, 22, 38, .14);
  }

  .landing-root.dark .persona-hero-nav {
    box-shadow: 0 8px 24px rgba(0, 0, 0, .4);
  }

  .landing-root .persona-hero-nav a {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    padding: 7px 12px;
    border-radius: 9px;
    font-size: 13px;
    font-weight: 600;
    white-space: nowrap;
    color: var(--text-muted);
    transition: color 180ms ease, background 180ms ease;
  }

  .landing-root .persona-hero-nav a:hover {
    color: var(--text-primary);
    background: var(--bg-hover);
  }

  /* The two values actually emitted: "page" on a persona page, "true" on the home page,
     where the pill marks the persona the hero is PLAYING rather than the page being read.
     Named rather than a bare [aria-current], which would also match the "false" that the
     common React idiom (aria-current={isCurrent}) renders on every OTHER pill. */
  .landing-root .persona-hero-nav a[aria-current="page"],
  .landing-root .persona-hero-nav a[aria-current="true"] {
    color: var(--bg-primary);
    background: var(--text-primary);
  }

  /* Five labelled pills do not fit on a phone: the icon carries the link there,
     and each link keeps its accessible name from aria-label. */
  @media (max-width: 720px) {
    .landing-root .persona-hero-nav {
      top: 8px;
      gap: 1px;
      padding: 3px;
      border-radius: 11px;
    }

    .landing-root .persona-hero-nav a {
      padding: 5px 7px;
    }

    .landing-root .persona-hero-nav-label {
      display: none;
    }
  }

  /* Persona tabs. */
  .landing-root .persona-tab-row {
    display: flex;
    flex-wrap: wrap;
    justify-content: center;
    gap: 8px;
  }

  .landing-root .persona-tab {
    display: inline-flex;
    align-items: center;
    gap: 8px;
    padding: 9px 16px;
    border-radius: 999px;
    font-size: 14px;
    font-weight: 600;
    border: 1px solid var(--border-color);
    background: var(--bg-primary);
    color: var(--text-secondary);
    cursor: pointer;
    transition: background 180ms ease, color 180ms ease, border-color 180ms ease;
  }

  .landing-root .persona-tab:hover {
    color: var(--text-primary);
    border-color: var(--text-muted);
  }

  .landing-root .persona-tab.active {
    background: var(--accent-primary);
    border-color: var(--accent-primary);
    color: var(--accent-foreground);
  }

  .landing-root .persona-card {
    transition: transform 300ms ease, box-shadow 300ms ease;
  }

  .landing-root .persona-card:hover {
    transform: translateY(-2px);
  }

  .landing-root .cta-glow {
    position: absolute;
    inset: 0;
    pointer-events: none;
    background: var(--landing-cta-glow);
  }

  /* Offset in-page anchor scrolling for the sticky header (h-20 = 80px). */
  .landing-root section[id] {
    scroll-margin-top: 96px;
  }

  .landing-root .feature-flow {
    display: flex;
    align-items: center;
    justify-content: center;
  }

  .landing-root .feature-flow-item {
    display: inline-flex;
    align-items: center;
  }

  .landing-root .feature-flow-link {
    flex: 0 0 16px;
    width: 16px;
    height: 2px;
    border-radius: 2px;
    background: linear-gradient(90deg, rgba(94, 90, 84, 0) 0%, var(--border-color) 35%, var(--border-color) 65%, rgba(94, 90, 84, 0) 100%);
  }

  .landing-root .feature-node {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 44px;
    height: 44px;
    border-radius: 9999px;
    overflow: hidden;
    box-shadow: var(--landing-node-shadow);
    transition: transform 220ms cubic-bezier(0.22, 1, 0.36, 1);
  }

  /* Node-flow icon color follows the LANDING theme (decoupled from <body> .dark). */
  .landing-root .feature-node-icon {
    color: var(--landing-icon-color);
  }

  .landing-root .feature-card:hover .feature-node {
    transform: translateY(-2px);
  }

  .landing-root .hero-stack-shell {
    position: relative;
    perspective: 2400px;
    perspective-origin: 50% 50%;
  }

  .landing-root .hero-stack {
    position: relative;
    width: 100%;
    max-width: 960px;
    margin: 0 auto;
    display: grid;
    grid-template-areas: "stack";
    transform-style: preserve-3d;
  }

  .landing-root .hero-stack-card {
    grid-area: stack;
    border-radius: 16px;
    cursor: pointer;
    transform-origin: center;
    will-change: transform, opacity, filter, box-shadow;
    transition:
      transform 900ms cubic-bezier(0.22, 1, 0.36, 1),
      opacity 700ms ease-out,
      filter 700ms ease-out,
      box-shadow 700ms ease-out;
  }

  .landing-root .hero-stack-card[data-pos="0"] {
    z-index: 5;
    transform: translate3d(0, 0, 0);
    opacity: 1;
    filter: none;
  }

  .landing-root .hero-stack-card[data-pos="1"] {
    z-index: 4;
    transform: translate3d(40px, 22px, -50px) rotateY(-3deg) scale(0.96);
    opacity: 0.85;
    filter: saturate(0.95);
  }

  .landing-root .hero-stack-card[data-pos="2"] {
    z-index: 3;
    transform: translate3d(80px, 44px, -100px) rotateY(-5deg) scale(0.92);
    opacity: 0.6;
    filter: saturate(0.85) brightness(0.95);
  }

  .landing-root .hero-stack-card[data-pos="3"] {
    z-index: 2;
    transform: translate3d(120px, 66px, -150px) rotateY(-7deg) scale(0.88);
    opacity: 0.35;
    filter: saturate(0.7) brightness(0.92);
  }

  .landing-root .hero-stack-card[data-pos="4"] {
    z-index: 1;
    transform: translate3d(160px, 88px, -200px) rotateY(-9deg) scale(0.84);
    opacity: 0.18;
    filter: saturate(0.5) brightness(0.88);
  }

  .landing-root .hero-stack-card .browser-frame {
    box-shadow: var(--landing-frame-shadow-strong);
  }

  .landing-root .hero-stack-card .browser-body {
    aspect-ratio: 16 / 9;
  }

  .landing-root .hero-stack-photo {
    position: relative;
    width: 100%;
    height: 100%;
    overflow: hidden;
    background: var(--bg-primary);
    background-position: center;
    background-repeat: no-repeat;
    background-size: cover;
  }

  .landing-root .hero-stack-video {
    display: block;
    width: 100%;
    height: 100%;
    object-fit: cover;
    background: var(--bg-primary);
  }

  .landing-root .hero-stack-card[data-active="true"] {
    cursor: zoom-in;
  }

  .landing-root .hero-stack-expand {
    position: absolute;
    top: 44px;
    right: 12px;
    z-index: 5;
    display: flex;
    align-items: center;
    justify-content: center;
    width: 30px;
    height: 30px;
    border-radius: 8px;
    border: 1px solid rgba(237, 236, 234, 0.25);
    background: rgba(23, 22, 20, 0.65);
    color: #edecea;
    cursor: pointer;
    opacity: 0;
    transition: opacity 160ms ease, background 160ms ease;
  }

  .landing-root .hero-stack-card[data-active="true"]:hover .hero-stack-expand,
  .landing-root .hero-stack-expand:focus-visible {
    opacity: 1;
  }

  .landing-root .hero-stack-expand:hover {
    background: rgba(23, 22, 20, 0.9);
  }

  /* Prev/next arrows - large screens only (the dot tray handles smaller widths).
     The focus tray is position:absolute top:100%, so the shell height equals the
     card height → top:50% centers the arrows on the active screenshot. */
  .landing-root .hero-stack-arrow {
    position: absolute;
    top: 50%;
    transform: translateY(-50%);
    z-index: 6;
    display: none;
    opacity: 0;
    align-items: center;
    justify-content: center;
    width: 44px;
    height: 44px;
    border-radius: 999px;
    border: 1px solid rgba(255, 255, 255, 0.16);
    background: rgba(23, 22, 20, 0.72);
    color: #fff;
    cursor: pointer;
    backdrop-filter: blur(6px);
    -webkit-backdrop-filter: blur(6px);
    transition: opacity 200ms ease, background 200ms ease, transform 200ms ease;
  }

  .landing-root .hero-stack-arrow:hover {
    background: rgba(23, 22, 20, 0.92);
    transform: translateY(-50%) scale(1.06);
  }

  .landing-root .hero-stack-arrow:focus-visible {
    outline: 2px solid var(--landing-accent, #fff);
    outline-offset: 2px;
  }

  .landing-root .hero-stack-arrow-prev {
    left: max(8px, calc(50% - 532px));
  }

  .landing-root .hero-stack-arrow-next {
    right: max(8px, calc(50% - 532px));
  }

  @media (min-width: 1024px) {
    .landing-root .hero-stack-arrow {
      display: flex;
    }

    .landing-root .hero-stack-shell:hover .hero-stack-arrow,
    .landing-root .hero-stack-arrow:focus-visible {
      opacity: 1;
    }
  }

  .landing-root .hero-lightbox {
    position: fixed;
    inset: 0;
    z-index: 200;
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 32px;
    background: rgba(10, 9, 8, 0.88);
    backdrop-filter: blur(6px);
    cursor: zoom-out;
  }

  .landing-root .hero-lightbox-content {
    max-width: min(1720px, 96vw);
    max-height: 92vh;
    cursor: default;
    border-radius: 12px;
    overflow: hidden;
    box-shadow: 0 40px 120px rgba(0, 0, 0, 0.6);
  }

  .landing-root .hero-lightbox-media {
    display: block;
    max-width: min(1720px, 96vw);
    max-height: 92vh;
    width: auto;
    height: auto;
  }

  .landing-root .hero-lightbox-close {
    position: absolute;
    top: 20px;
    right: 24px;
    z-index: 201;
    display: flex;
    align-items: center;
    justify-content: center;
    width: 40px;
    height: 40px;
    border-radius: 50%;
    border: 1px solid rgba(237, 236, 234, 0.3);
    background: rgba(23, 22, 20, 0.7);
    color: #edecea;
    font-size: 16px;
    cursor: pointer;
  }

  .landing-root .hero-lightbox-close:hover {
    background: rgba(42, 41, 37, 0.95);
  }

  /* Prev/next arrows inside the enlarged view - large screens only. */
  .landing-root .hero-lightbox-arrow {
    position: absolute;
    top: 50%;
    transform: translateY(-50%);
    z-index: 201;
    display: none;
    align-items: center;
    justify-content: center;
    width: 52px;
    height: 52px;
    border-radius: 50%;
    border: 1px solid rgba(237, 236, 234, 0.3);
    background: rgba(23, 22, 20, 0.7);
    color: #edecea;
    cursor: pointer;
    backdrop-filter: blur(6px);
    -webkit-backdrop-filter: blur(6px);
    transition: background 200ms ease, transform 200ms ease;
  }

  .landing-root .hero-lightbox-arrow:hover {
    background: rgba(42, 41, 37, 0.95);
    transform: translateY(-50%) scale(1.06);
  }

  .landing-root .hero-lightbox-arrow:focus-visible {
    outline: 2px solid var(--landing-accent, #fff);
    outline-offset: 2px;
  }

  .landing-root .hero-lightbox-arrow-prev {
    left: 24px;
  }

  .landing-root .hero-lightbox-arrow-next {
    right: 24px;
  }

  @media (min-width: 1024px) {
    .landing-root .hero-lightbox-arrow {
      display: flex;
    }
  }

  .landing-root .marketplace-marquee {
    height: 920px;
    overflow: hidden;
    -webkit-mask-image: linear-gradient(to bottom, transparent, black 6%, black 94%, transparent);
    mask-image: linear-gradient(to bottom, transparent, black 6%, black 94%, transparent);
  }

  .landing-root .marketplace-col {
    display: flex;
    flex-direction: column;
    animation: marketplace-scroll-up 48s linear infinite;
  }

  .landing-root .marketplace-col.scroll-down {
    animation-name: marketplace-scroll-down;
    animation-duration: 56s;
  }

  .landing-root .marketplace-col-item {
    margin-bottom: 24px;
  }

  .landing-root .marketplace-marquee:hover .marketplace-col {
    animation-play-state: paused;
  }

  @keyframes marketplace-scroll-up {
    from { transform: translateY(0); }
    to { transform: translateY(-50%); }
  }

  @keyframes marketplace-scroll-down {
    from { transform: translateY(-50%); }
    to { transform: translateY(0); }
  }

  @media (prefers-reduced-motion: reduce) {
    .landing-root .marketplace-col {
      animation: none;
    }
  }

  .landing-root .hero-focus-tray {
    position: absolute;
    top: 100%;
    left: 50%;
    transform: translateX(-50%);
    width: min(640px, calc(100% - 48px));
    margin: 48px 0 0 0;
    display: grid;
    gap: 12px;
  }

  .landing-root .hero-focus-copy {
    position: relative;
    min-height: 58px;
  }

  .landing-root .hero-focus-panel {
    display: grid;
    gap: 7px;
    align-items: start;
    justify-items: center;
    text-align: center;
  }

  .landing-root .hero-focus-heading {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    gap: 12px;
    width: 100%;
    min-width: 0;
    font-family: inherit;
    font-size: 12px;
    line-height: 1.25;
    text-transform: none;
    letter-spacing: 0;
  }

  .landing-root .hero-focus-count {
    display: inline-grid;
    grid-template-columns: 2ch auto 2ch;
    column-gap: 7px;
    align-items: center;
    justify-items: center;
    min-width: 62px;
    text-align: center;
  }

  .landing-root .hero-focus-index {
    color: var(--text-primary);
    font-weight: 700;
    justify-self: end;
  }

  .landing-root .hero-focus-slash {
    color: var(--text-muted);
  }

  .landing-root .hero-focus-total {
    justify-self: start;
  }

  .landing-root .hero-focus-total,
  .landing-root .hero-focus-separator {
    color: var(--text-muted);
  }

  .landing-root .hero-focus-separator {
    margin: 0 2px;
  }

  .landing-root .hero-focus-label {
    color: var(--text-primary);
    font-weight: 700;
  }

  .landing-root .hero-focus-description {
    max-width: 430px;
    margin: 0 auto;
    font-size: 13px;
    line-height: 1.45;
    text-align: center;
    color: var(--text-secondary);
  }

  .landing-root .hero-focus-dashes {
    display: inline-flex;
    gap: 6px;
    width: auto;
    justify-self: center;
  }

  .landing-root .hero-focus-dash {
    position: relative;
    height: 3px;
    width: 18px;
    flex: 0 0 18px;
    overflow: hidden;
    border-radius: 2px;
    border: 0;
    background: var(--landing-dash-track);
    cursor: pointer;
    padding: 0;
    transition: width 240ms ease-out, flex-basis 240ms ease-out, background 240ms ease-out;
  }

  .landing-root .hero-focus-dash.active {
    width: 56px;
    flex-basis: 56px;
    background: var(--landing-dash-track-active);
  }

  .landing-root .hero-focus-dash-fill {
    position: absolute;
    inset: 0;
    width: 100%;
    border-radius: inherit;
    background: var(--text-primary);
    transform: scaleX(0);
    transform-origin: left;
  }

  .landing-root .hero-focus-dash.active .hero-focus-dash-fill {
    animation: hero-focus-dash-fill 4200ms linear forwards;
  }

  .landing-root .hero-stack-shell.is-paused .hero-focus-dash-fill {
    animation-play-state: paused;
  }

  @keyframes hero-focus-dash-fill {
    0% {
      transform: scaleX(0);
    }
    100% {
      transform: scaleX(1);
    }
  }

  @media (max-width: 1023px) {
    .landing-root .hero-stack-shell {
      perspective: 1800px;
    }
  }

  @media (max-width: 1023px) {
    .landing-root .hero-stack {
      max-width: 720px;
    }
  }

  @media (max-width: 820px) {
    .landing-root .hero-stack-shell {
      perspective: 1500px;
    }

    .landing-root .hero-stack-card[data-pos="0"] {
      transform: translate3d(0, 0, 0) rotateY(-3deg) rotateX(1deg) scale(1.015);
    }

    .landing-root .hero-stack-card[data-pos="1"] {
      transform: translate3d(0, 10px, -35px) rotateY(-4deg) rotateX(1deg) scale(0.97);
    }

    .landing-root .hero-stack-card[data-pos="2"] {
      transform: translate3d(0, 20px, -70px) rotateY(-5deg) rotateX(1deg) scale(0.94);
    }

    .landing-root .hero-stack-card[data-pos="3"] {
      transform: translate3d(0, 30px, -105px) rotateY(-6deg) rotateX(1deg) scale(0.91);
    }

    .landing-root .hero-stack-card[data-pos="4"] {
      transform: translate3d(0, 40px, -140px) rotateY(-7deg) rotateX(1deg) scale(0.88);
    }
  }

  @media (min-width: 721px) and (max-width: 820px) {
    .landing-root .hero-stack-shell {
      perspective: 1800px;
    }

    .landing-root .hero-stack-card[data-pos="0"] {
      transform: translate3d(0, 0, 0) rotateY(-6deg) rotateX(2deg) scale(1.02);
    }

    .landing-root .hero-stack-card[data-pos="1"] {
      transform: translate3d(42px, 18px, -50px) rotateY(-8deg) rotateX(2deg) scale(0.96);
    }

    .landing-root .hero-stack-card[data-pos="2"] {
      transform: translate3d(84px, 36px, -100px) rotateY(-10deg) rotateX(2deg) scale(0.92);
    }

    .landing-root .hero-stack-card[data-pos="3"] {
      transform: translate3d(126px, 54px, -150px) rotateY(-12deg) rotateX(2deg) scale(0.88);
    }

    .landing-root .hero-stack-card[data-pos="4"] {
      transform: translate3d(168px, 72px, -200px) rotateY(-14deg) rotateX(2deg) scale(0.84);
    }

    .landing-root .hero-focus-tray {
      margin-top: 86px;
    }
  }

  @media (max-width: 720px) {
    .landing-root .hero-stack-shell {
      perspective: 1200px;
    }

    .landing-root .hero-focus-tray {
      width: calc(100% - 16px);
      margin: 34px 0 0 16px;
    }

    .landing-root .hero-focus-copy {
      min-height: 82px;
    }

    .landing-root .hero-focus-description {
      font-size: 12px;
    }
  }

  @media (prefers-reduced-motion: reduce) {
    .landing-root .hero-stack-card {
      transition: none;
    }

    .landing-root .hero-focus-dash,
    .landing-root .hero-focus-dash-fill {
      animation: none;
      transition: none;
    }
  }

  .landing-root .hero-flow {
    display: inline-flex;
    align-items: center;
    flex-wrap: wrap;
    justify-content: center;
    gap: 0;
    font-size: 11px;
    text-transform: uppercase;
    letter-spacing: 0.12em;
    color: var(--text-muted);
  }

  .landing-root .hero-flow > span {
    color: var(--text-secondary);
    font-weight: 600;
  }

  .landing-root .browser-frame {
    border-radius: 16px;
    overflow: hidden;
    border: 1px solid var(--border-color);
    background: var(--bg-secondary);
    box-shadow: var(--landing-frame-shadow);
    margin: 0;
  }

  .landing-root .browser-chrome {
    display: flex;
    align-items: center;
    gap: 8px;
    height: 36px;
    padding: 0 12px;
    background: var(--bg-tertiary);
    border-bottom: 1px solid var(--border-color);
  }

  .landing-root .browser-dot {
    width: 11px;
    height: 11px;
    border-radius: 50%;
    display: inline-block;
    flex-shrink: 0;
  }

  .landing-root .browser-url {
    flex: 1;
    text-align: center;
    font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
    font-size: 11px;
    color: var(--text-muted);
    background: var(--bg-secondary);
    padding: 4px 12px;
    border-radius: 6px;
    max-width: 70%;
    margin: 0 auto;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .landing-root .browser-body {
    background: var(--bg-primary);
  }

  .landing-root .logo-tile {
    transition: all 0.2s ease;
  }

  .landing-root .logo-tile:hover {
    background: var(--bg-tertiary) !important;
    transform: translateY(-2px);
  }

  .landing-root .install-pill {
    transition: background 0.2s ease, color 0.2s ease;
  }

  .landing-root a:hover .install-pill {
    background: var(--accent-primary) !important;
    color: var(--accent-foreground) !important;
  }

  .landing-root .org-pipe {
    width: 1px;
    height: 16px;
    background: var(--border-color);
  }

  .landing-root .logo-color {
    opacity: 0.85;
    transition: opacity 0.2s ease;
  }

  .landing-root .logo-color:hover {
    opacity: 1;
  }

  @media (prefers-reduced-motion: reduce) {
    .landing-root *, .landing-root *::before, .landing-root *::after {
      animation-duration: 0.01ms !important;
      transition-duration: 0.01ms !important;
    }
  }

  /* The integrations strip under the hero. A chip is a link, so it needs a hover state
     that reads as one without turning the row into a colour field. */
  .landing-root .integration-chip {
    background: var(--bg-primary);
    transition: border-color 140ms ease, color 140ms ease, transform 140ms ease;
  }

  .landing-root .integration-chip:hover {
    border-color: var(--accent);
    color: var(--text-primary);
    transform: translateY(-1px);
  }

  .landing-root .integration-strip-all {
    color: var(--text-primary);
    font-weight: 600;
    text-decoration: underline;
    text-underline-offset: 3px;
  }

  /* On a phone the 25 chips wrap into a dozen rows, which rebuilds the wall between the
     hero and the rest of the page that got this band deleted last time. The tail is HIDDEN
     rather than sliced in the component, so every integration link stays in the served
     HTML for a crawler while a visitor sees a band. Twelve is four rows at 390px. */
  @media (max-width: 767px) {
    .landing-root .integration-chips > li:nth-child(n + 13) {
      display: none;
    }
  }

  /* The six role cards, one per persona page, each showing that page's own screen.

     Every colour is mixed from ONE custom property, --role-tint, which the component sets
     inline from PERSONA_TINTS (the same triplet the persona page paints itself with). So
     the six cards are one rule, not six, and a card cannot drift from the page it opens.

     THE ROW IS ASYMMETRIC, and that is the studios' rhythm rather than decoration: five
     columns holding a 3 and a 2, with the wide slot changing sides from row to row, exactly
     as OpsBuildStudio lays its cards out. Which card is wide is decided in the markup
     (data-span), never here, because it is the same decision as which screen goes where:
     the landscape workspaces want the wide half and Creator's portrait story wants a narrow
     one. Below the breakpoint the row is a single column and the spans stop applying, which
     is the only sensible phone layout for a card whose subject is a screenshot. */
  .landing-root .role-row {
    display: grid;
    gap: 1.25rem;
  }

  @media (min-width: 1024px) {
    .landing-root .role-row {
      grid-template-columns: repeat(5, minmax(0, 1fr));
      gap: 1.5rem;
    }

    .landing-root .role-card[data-span="wide"] { grid-column: span 3; }
    .landing-root .role-card[data-span="narrow"] { grid-column: span 2; }
  }

  .landing-root .role-card {
    display: flex;
    flex-direction: column;
    overflow: hidden;
    border-radius: 1.5rem;
    background-color: var(--bg-tertiary);
    border: 1px solid rgba(var(--role-tint), .28);
    box-shadow: var(--landing-card-shadow);
    transition: transform .2s ease, border-color .2s ease;
  }

  .landing-root .role-card:hover {
    transform: translateY(-2px);
    border-color: rgba(var(--role-tint), .5);
  }

  /* The stage carries the tint and the screen floats on it, the way a studio card's stage
     does. It takes the room and the copy sits under it in a quiet block. */
  .landing-root .role-card-stage {
    display: flex;
    flex: 1;
    align-items: center;
    justify-content: center;
    padding: 1.75rem 1.25rem;
    background-image: linear-gradient(135deg, rgba(var(--role-tint), .16), rgba(var(--role-tint), .06) 55%, rgba(var(--role-tint), .14));
  }

  .landing-root.dark .role-card-stage {
    background-image: linear-gradient(135deg, rgba(var(--role-tint), .22), rgba(var(--role-tint), .09) 55%, rgba(var(--role-tint), .19));
  }

  @media (min-width: 768px) {
    .landing-root .role-card-stage {
      padding: 2.25rem 1.75rem;
    }
  }

  /* The wired composition: what fires the run, the screen it produces, and where the result
     goes, with StudioWires curving an edge between them.

     IT SWITCHES ON THE CARD'S OWN WIDTH, not the window's, which is why this is a container
     query and not a media query. The wide and narrow cards sit side by side at the same
     viewport, so any breakpoint in screen pixels would flip both at once and one of them
     would be wrong: a media query cannot express "this card is the narrow one". Stacked is
     the DEFAULT, so a browser without container queries gets the layout that works at every
     width rather than a row crushed into 371px. */
  .landing-root .role-card-stage {
    container-type: inline-size;
  }

  .landing-root .role-wire-stage {
    width: 100%;
  }

  .landing-root .role-wire-row {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 1rem;
  }

  .landing-root .role-wire-dests {
    display: flex;
    flex-wrap: wrap;
    justify-content: center;
    gap: .375rem;
  }

  @container (min-width: 520px) {
    .landing-root .role-wire-row {
      flex-direction: row;
      justify-content: center;
      gap: 1.25rem;
    }

    .landing-root .role-wire-dests {
      flex-direction: column;
      flex-wrap: nowrap;
      align-items: flex-start;
    }
  }

  /* The tiles the wires attach to. z-10 keeps them above the SVG, which StudioWires paints
     at z-0 across the whole stage. */
  .landing-root .role-wire-pill {
    position: relative;
    z-index: 10;
    display: inline-flex;
    align-items: center;
    gap: .5rem;
    flex-shrink: 0;
    max-width: 100%;
    border-radius: .75rem;
    padding: .4rem .625rem;
    font-size: .6875rem;
    font-weight: 500;
    line-height: 1.2;
    background: var(--bg-primary);
    border: 1px solid var(--border-color);
    box-shadow: 0 10px 24px rgba(16, 22, 38, .12);
    color: var(--text-secondary);
  }

  /* The screen never runs the full width of its card: at 3/5 of a 1104px row it would be
     620px of interface over two lines of text, and the card would read as a screenshot with
     a caption instead of a card. It also has to leave room for the trigger and the
     destinations beside it once the composition is a row.

     The last cap is by SHAPE rather than by slot: a portrait story is 1.78 times as tall as
     it is wide, so at a workspace's width it would be half again as tall and drag its whole
     row down with it. */
  .landing-root .role-stage-box {
    position: relative;
    z-index: 10;
    width: 100%;
    max-width: 320px;
  }

  .landing-root .role-card .role-stage-box[data-shape="portrait"] {
    max-width: 210px;
  }

  @container (min-width: 520px) {
    .landing-root .role-stage-box {
      max-width: 300px;
    }

    .landing-root .role-card .role-stage-box[data-shape="portrait"] {
      max-width: 190px;
    }
  }

  /* Holds the tile's exact shape until the screen mounts, so nothing moves under the
     visitor when it arrives. */
  .landing-root .role-stage-placeholder {
    width: 100%;
    border-radius: 1rem;
    border: 1px solid var(--border-color);
    background: var(--bg-primary);
    box-shadow: 0 14px 32px rgba(16, 22, 38, .16);
  }

  .landing-root .role-card-body {
    padding: 1.25rem;
  }

  @media (min-width: 768px) {
    .landing-root .role-card-body {
      padding: 1.5rem 1.75rem 1.75rem;
    }
  }

  .landing-root .role-card-label {
    display: inline-flex;
    align-items: center;
    gap: .5rem;
    border-radius: 9999px;
    padding: .25rem .625rem;
    font-size: .75rem;
    font-weight: 600;
    background: var(--bg-primary);
    border: 1px solid rgba(var(--role-tint), .3);
    color: rgb(var(--role-tint));
    color: color-mix(in srgb, rgb(var(--role-tint)) 55%, #000000);
  }

  .landing-root.dark .role-card-label {
    color: rgb(var(--role-tint));
    color: color-mix(in srgb, rgb(var(--role-tint)) 50%, #ffffff);
  }

  .landing-root .role-card-title {
    margin-top: .75rem;
    font-size: 1.0625rem;
    font-weight: 600;
    line-height: 1.35;
    color: var(--text-primary);
  }

  .landing-root .role-card-summary {
    margin-top: .5rem;
    max-width: 44rem;
    font-size: .875rem;
    line-height: 1.625;
    color: var(--text-secondary);
  }

  /* Tinted TEXT on a tinted ground rather than white on solid colour: the six hues include
     an amber at 217,140,20, where white text is about 2:1 and unreadable.

     The mix is what makes all six pass, and the numbers were MEASURED, not guessed: see
     personaTintContrast.test.ts, which composites the real grounds and fails under 4.5:1.
     color-mix keeps x% OF THE HUE, so a LOWER number sits further from the ground. Light mode
     at 60% left the amber at 4.47:1 and dark mode at 55% left the pink at 4.41:1, both under
     the 4.5:1 that 14px text at weight 500 needs; 55% and 50% put the worst of the twelve
     combinations at 5.05:1 and 4.71:1, and every button still reads as its card's colour.

     The plain rgb() line before each color-mix() is what a browser without color-mix() gets,
     and it is NOT equivalent: the raw hue on its own tinted ground measures about 2.1:1 for
     the amber. It is a floor those browsers do not clear, kept because a coloured button
     beats an unstyled one there; the supported path is the mix. */
  .landing-root .role-card-cta {
    margin-top: 1.25rem;
    display: inline-flex;
    align-items: center;
    gap: .5rem;
    height: 2.25rem;
    padding: 0 .875rem;
    border-radius: .75rem;
    font-size: .875rem;
    font-weight: 500;
    background: rgba(var(--role-tint), .14);
    border: 1px solid rgba(var(--role-tint), .34);
    color: rgb(var(--role-tint));
    color: color-mix(in srgb, rgb(var(--role-tint)) 55%, #000000);
    transition: background-color .2s ease, border-color .2s ease;
  }

  .landing-root.dark .role-card-cta {
    background: rgba(var(--role-tint), .2);
    color: rgb(var(--role-tint));
    color: color-mix(in srgb, rgb(var(--role-tint)) 50%, #ffffff);
  }

  .landing-root .role-card-cta:hover {
    background: rgba(var(--role-tint), .24);
    border-color: rgba(var(--role-tint), .5);
  }

  /* The focus ring is the page's accent, NOT the card's hue, and that is a contrast rule
     rather than a style choice: outline-offset draws it on the page ground, where the amber
     measures 2.7:1 and the 3:1 floor for a focus indicator is normative. Same declaration
     the persona pages already use for their links. */
  .landing-root .role-card-cta:focus-visible {
    outline: 2px solid var(--accent-primary);
    outline-offset: 2px;
  }

  @media (prefers-reduced-motion: reduce) {
    .landing-root .role-card,
    .landing-root .role-card:hover {
      transition: none;
      transform: none;
    }
  }

  /* "What you can build": two rows sliding in opposite directions.

     The viewport is a plain block at its parent's width, and that parent is now the SECTION
     rather than the 1104px content box (see the bleed slot on Section). Clipped at the
     content box, the band cut its cards mid-sentence two inches inside the layout, which
     reads as a broken card rather than as a row running off the screen; at 768px it was the
     whole right-hand card. It is still not full-bleed by measuring the viewport: an earlier
     version used width:100vw + margin-left:calc(50% - 50vw), which is wrong on any desktop
     browser with a classic scrollbar, because 100vw includes the scrollbar, the row
     overflows its container by that width, and nothing on this page sets overflow-x, so the
     WHOLE page gains a horizontal scrollbar. The section element is already exactly the
     page's width with the scrollbar excluded, so the bleed is structural and costs nothing.

     The fade is in PIXELS, not percent. At 7% it scaled with the band: 77px of fade at 1104,
     but only 54px at 768, where the cards are the same size and need MORE of it, and 134px
     at 1920, where it ate a whole card. A fixed 96px fades the same amount of one card
     whatever the screen, which is what the effect is actually about. */
  .landing-root .build-row-viewport {
    overflow: hidden;
    -webkit-mask-image: linear-gradient(to right, transparent, black 96px, black calc(100% - 96px), transparent);
    mask-image: linear-gradient(to right, transparent, black 96px, black calc(100% - 96px), transparent);
  }

  /* width: max-content stops flex from shrinking the duplicated pass to fit, which would
     make -50% land somewhere other than the seam.

     There is deliberately NO flex gap here: see BuildableAutomations. With a gap, 2N children
     carry 2N-1 gaps, so one pass is not half the row and every loop snaps by half a gap.
     The spacing is a margin on .build-card instead, which keeps one pass exactly 50%. */
  .landing-root .build-row {
    width: max-content;
    animation: build-scroll-left 64s linear infinite;
  }

  .landing-root .build-row-reverse {
    animation-name: build-scroll-right;
    animation-duration: 76s;
  }

  .landing-root .build-card {
    margin-right: 16px;
  }

  @media (min-width: 768px) {
    .landing-root .build-card {
      margin-right: 20px;
    }
  }

  /* Pause on hover so a card can be read, and on focus so a keyboard visitor who has
     tabbed to the row (it is focusable for the reduced-motion case) is not reading a
     moving target. */
  .landing-root .build-row-viewport:hover .build-row,
  .landing-root .build-row-viewport:focus .build-row,
  .landing-root .build-row-viewport:focus-within .build-row {
    animation-play-state: paused;
  }

  @keyframes build-scroll-left {
    from { transform: translateX(0); }
    to { transform: translateX(-50%); }
  }

  @keyframes build-scroll-right {
    from { transform: translateX(-50%); }
    to { transform: translateX(0); }
  }

  /* On a phone the marquee is the wrong shape, for the same reason the grid exists below
     eight cards: a row needs about four cards on screen to read as a wall in motion. A
     431px viewport fits one, so the band was two HALF cards sliding past with their
     sentences cut on both sides and the edge mask fading what survived. Nothing in it could
     be read, which is the whole job of the section.

     So below this width it becomes what a phone expects: the row holds still and the
     visitor swipes it, one card at a time. That is the same regime reduced motion already
     asks for, and it keeps every card reachable rather than trusting a visitor to catch one
     mid-transit. The mask goes with the motion, since a fade that sold "it runs past the
     edge" only greys the card being read once the row has stopped. */
  @media (max-width: 767px) {
    .landing-root .build-row {
      animation: none;
    }

    .landing-root .build-row-viewport {
      overflow-x: auto;
      scroll-snap-type: x mandatory;
      overscroll-behavior-x: contain;
      -webkit-mask-image: none;
      mask-image: none;
    }

    /* The band runs edge to edge now, so a stopped row would start its first card flush
       against the screen. The gutter puts it back on the page's own 24px margin.
       It is set in the two STOPPED regimes only: padding on a row that is animating would
       add to its width, and one pass would stop being exactly half of it, which is the
       seam the -50% translation lands on.
       (A matching scroll-padding-inline was here and did nothing: the cards snap on CENTER,
       and a symmetric inset leaves the snapport's centre exactly where the scrollport's
       was. The gutter is the padding alone.) */
    .landing-root .build-row {
      padding-inline: 24px;
    }

    /* The duplicate pass exists only to give the animation a seam to land on; with the
       animation gone it is the same cards a second time. */
    .landing-root .build-row-echo {
      display: none;
    }

    .landing-root .build-card {
      scroll-snap-align: center;
      /* The card is a fixed 300px, which is wider than a small phone's content box. The cap
         keeps the whole sentence on screen instead of running it off the right edge.
         The viewport unit includes a classic scrollbar, the trap the full-bleed note above
         warns about, and it is safe here for a reason rather than by luck: a MAX-width inside a
         clipped row, so an over-estimate of 17px eats into the 24px peek of the next card and
         can never widen anything or reach the page. */
      max-width: calc(100vw - 72px);
    }
  }

  @media (prefers-reduced-motion: reduce) {
    /* Stop it dead and hand the row back to the visitor. Without overflow-x the cards past
       the fold would simply be unreachable once the animation is gone, and the viewport
       carries tabIndex so the scroller is reachable by keyboard and not by pointer only. */
    .landing-root .build-row {
      animation: none;
    }

    /* Same treatment as the phone regime above, and for the same reason rather than by
       imitation: once the row has stopped, the edge fade no longer sells "it runs past the
       edge", it just greys the card being read, and a row the visitor drives wants to come
       to rest on a card rather than between two. This block used to stop the animation and
       keep the fade, which is exactly the state the phone rules call wrong. */
    .landing-root .build-row-viewport {
      overflow-x: auto;
      scroll-snap-type: x mandatory;
      overscroll-behavior-x: contain;
      -webkit-mask-image: none;
      mask-image: none;
    }

    /* Same gutter as the phone regime, and for the same reason: a row the visitor drives
       starts at the page margin, not under the edge of the screen. */
    .landing-root .build-row {
      padding-inline: 24px;
    }

    .landing-root .build-card {
      scroll-snap-align: center;
    }

    /* The duplicate pass exists only to give the animation a seam to land on. With the
       animation gone it is just the same six cards a second time, so a visitor scrolling
       the row would read the band twice. */
    .landing-root .build-row-echo {
      display: none;
    }

    .landing-root .integration-chip:hover {
      transform: none;
    }
  }
`;
