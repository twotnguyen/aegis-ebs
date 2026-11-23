// ---------------------------------------------------------------------------
// AEGIS Tower B01 — digital twin HUD (ui.js)
// DOM overlay per src/three/CONTRACT.md: status legend, floor selector,
// view toggles, zone tooltip, title bar + SIMULATION badge. Dark glassmorphism
// matching src/styles.css. Fully self-contained: creates its own DOM + <style>,
// no dependency on main.js and no scene geometry (group is an empty Group).
//
// build(ctx) -> { group, setStatus, bindCamera, showTooltip, hideTooltip, dispose }
//   ctx = { THREE, config, materials, root, helpers }
//   - helpers.uiHost: optional mount container (default: document.body)
//   - helpers.onFloorChange({ floor, floorNumber }) / helpers.onViewChange(view):
//     optional callbacks (floor is 0-based index, null = all floors)
//   - setStatus(status): live status/1 model (floors[].zones[]) -> legend counts
//     + per-floor worst-severity dots on the floor selector
//   - bindCamera(cameraApi): buttons call the first available method:
//       floor i (0-based): focusFloor | setFloor | flyToFloor | isolateFloor (i)
//       all floors:        showAll | showAllFloors | resetView | reset ()
//       view id:           setView | setMode (id), else cameraApi[id]()
//     Every click also emits a window CustomEvent — 'aegis-ui:floor'
//     (detail { floor, floorNumber }) and 'aegis-ui:view' (detail { view }) —
//     so the assembler can wire camera/lighting modules without coupling.
//   - showTooltip(zoneData, x, y): x/y are viewport (client) coords; zoneData is
//     a status/1 zone { zoneCode, devicesTotal, online, received, acknowledged,
//     failed, severity }. hideTooltip() hides it.
//   - dispose(): removes DOM, injected style, observers and listeners.
//
// Node-safe: importing this module never touches the DOM; build() returns a
// no-op API when `document` is undefined (dynamic-import verified).
// The HUD auto-hides when body[data-tab] != 'twin' (dashboard tab switch) and
// measures #hud-top / #hud-foot so it never overlaps the existing chrome.
// ---------------------------------------------------------------------------

import { BUILDING, STATUS_COLORS, zoneCode } from './config.js';
import { t, subscribe } from '../i18n.js';

const ROOT_ID = 'at3-hud';
const STYLE_ID = 'at3-hud-style';
const SEVS = ['online', 'warning', 'critical', 'offline'];
const SEV_RANK = { online: 0, offline: 1, warning: 2, critical: 3 };
const RANK_SEV = ['online', 'offline', 'warning', 'critical'];
const VIEWS = [
  { id: 'overview', key: 'view.overview', glyph: '▦' },
  { id: 'cutaway', key: 'view.cutaway', glyph: '◧' },
  { id: 'explode', key: 'view.explode', glyph: '✳' },
  { id: 'night', key: 'view.night', glyph: '☾' },
];

const hex = (n) => `#${(Number(n) >>> 0).toString(16).padStart(6, '0')}`;
const fmt = (v) => (typeof v === 'number' && Number.isFinite(v) ? v.toLocaleString('en-US') : '–');
const sevOf = (z) => (SEVS.includes(z?.severity) ? z.severity : 'offline');
const floorLabel = (i) => `F${String(i + 1).padStart(2, '0')}`;

const CSS = `
.at3-root{position:fixed;inset:0;z-index:6;pointer-events:none;
  font-family:"Inter","Segoe UI",system-ui,-apple-system,sans-serif;
  color:var(--text,#dbe4f5);--at3-top:74px;--at3-bottom:44px;--at3-accent:#38bdf8;
  --at3-glass:rgba(13,19,38,.72);--at3-line:rgba(120,140,190,.22);--at3-muted:var(--muted,#8fa0c2);}
.at3-root[hidden]{display:none!important}
.at3-root .at3-card{background:linear-gradient(160deg,rgba(38,50,84,.5),rgba(13,19,38,.18)),var(--at3-glass);
  border:1px solid var(--at3-line);border-radius:14px;
  backdrop-filter:blur(14px) saturate(1.4);-webkit-backdrop-filter:blur(14px) saturate(1.4);
  box-shadow:0 10px 34px rgba(2,6,18,.5),inset 0 1px 0 rgba(190,210,255,.08);}
.at3-root .at3-card-head{margin:0;font-size:10px;font-weight:700;text-transform:uppercase;
  letter-spacing:1.6px;color:var(--at3-muted);display:flex;align-items:center;justify-content:space-between;gap:6px;}
.at3-root .at3-pill{background:rgba(120,140,190,.18);border-radius:999px;padding:1px 8px;
  font-size:10px;font-weight:600;color:var(--text,#dbe4f5);font-variant-numeric:tabular-nums;}

/* --- title bar --- */
.at3-root .at3-title{position:absolute;top:var(--at3-top);left:14px;padding:10px 14px;
  display:flex;flex-direction:column;gap:3px;max-width:min(460px,calc(100vw - 28px));pointer-events:none;}
.at3-root .at3-title-row{display:flex;align-items:center;gap:10px;}
.at3-root .at3-logo{width:20px;height:20px;flex:none;color:var(--at3-accent);
  filter:drop-shadow(0 0 6px rgba(56,189,248,.5));}
.at3-root .at3-title-text{margin:0;font-size:14.5px;font-weight:800;letter-spacing:2.2px;white-space:nowrap;
  text-shadow:0 1px 8px rgba(2,6,18,.6);}
.at3-root .at3-badge{display:inline-flex;align-items:center;gap:6px;font-size:9.5px;font-weight:800;
  letter-spacing:1.6px;color:var(--at3-accent);border:1px solid rgba(56,189,248,.55);
  background:rgba(56,189,248,.1);padding:3px 9px;border-radius:999px;white-space:nowrap;}
.at3-root .at3-badge-dot{width:6px;height:6px;border-radius:50%;background:var(--at3-accent);
  box-shadow:0 0 8px var(--at3-accent);animation:at3-pulse 1.6s ease-in-out infinite;}
.at3-root .at3-sub{font-size:10px;color:var(--at3-muted);letter-spacing:.9px;text-transform:uppercase;}
@keyframes at3-pulse{50%{opacity:.3}}

/* --- floor selector --- */
.at3-root .at3-floors{position:absolute;top:calc(var(--at3-top) + 76px);left:14px;width:134px;
  padding:10px;display:flex;flex-direction:column;gap:7px;pointer-events:auto;
  max-height:calc(100vh - var(--at3-top) - var(--at3-bottom) - 176px);}
.at3-root .at3-floor-list{display:flex;flex-direction:column;gap:3px;overflow-y:auto;padding-right:2px;
  scrollbar-width:thin;scrollbar-color:rgba(120,140,190,.35) transparent;}
.at3-root .at3-floor-list::-webkit-scrollbar{width:5px}
.at3-root .at3-floor-list::-webkit-scrollbar-thumb{background:rgba(120,140,190,.35);border-radius:3px}
.at3-root .at3-floor-btn{display:flex;align-items:center;justify-content:space-between;gap:8px;width:100%;
  padding:5px 9px;font:inherit;font-size:11.5px;font-weight:700;letter-spacing:.8px;
  font-variant-numeric:tabular-nums;color:var(--text,#dbe4f5);background:rgba(120,140,190,.07);
  border:1px solid transparent;border-radius:8px;cursor:pointer;
  transition:background .15s,border-color .15s,color .15s;}
.at3-root .at3-floor-btn:hover{background:rgba(120,140,190,.22)}
.at3-root .at3-floor-btn[aria-pressed="true"]{background:rgba(56,189,248,.16);
  border-color:rgba(56,189,248,.6);color:var(--at3-accent);}
.at3-root .at3-floor-btn[data-floor="all"]{margin-bottom:4px;background:rgba(120,140,190,.14);}
.at3-root .at3-floor-dot{width:8px;height:8px;border-radius:50%;flex:none;background:#64748b;}

/* --- legend --- */
.at3-root .at3-legend{position:absolute;left:14px;bottom:var(--at3-bottom);width:214px;
  padding:10px 12px;pointer-events:none;}
.at3-root .at3-legend-grid{display:grid;grid-template-columns:1fr 1fr;gap:6px 10px;margin-top:8px;}
.at3-root .at3-legend-item{display:flex;align-items:center;gap:6px;font-size:11px;color:var(--at3-muted);}
.at3-root .at3-sw{width:10px;height:10px;border-radius:3px;flex:none;}
.at3-root .at3-legend-count{margin-left:auto;font-weight:700;color:var(--text,#dbe4f5);
  font-variant-numeric:tabular-nums;}

/* --- view toggles --- */
.at3-root .at3-views{position:absolute;left:50%;transform:translateX(-50%);bottom:var(--at3-bottom);
  display:flex;gap:4px;padding:5px;border-radius:999px;pointer-events:auto;}
.at3-root .at3-view-btn{display:inline-flex;align-items:center;gap:6px;border:0;background:transparent;
  color:var(--at3-muted);font:inherit;font-size:11px;font-weight:700;letter-spacing:1.1px;
  text-transform:uppercase;padding:7px 14px;border-radius:999px;cursor:pointer;white-space:nowrap;
  transition:background .15s,color .15s,box-shadow .15s;}
.at3-root .at3-view-btn:hover{color:var(--text,#dbe4f5);background:rgba(120,140,190,.16)}
.at3-root .at3-view-btn[aria-pressed="true"]{background:rgba(56,189,248,.18);color:var(--at3-accent);
  box-shadow:inset 0 0 0 1px rgba(56,189,248,.45);}
.at3-root .at3-view-glyph{font-size:12px;line-height:1;}

/* --- zone tooltip --- */
.at3-root .at3-tooltip{position:absolute;z-index:30;min-width:212px;max-width:264px;padding:10px 12px;
  border-radius:12px;pointer-events:none;font-size:11.5px;
  background:linear-gradient(160deg,rgba(38,50,84,.55),rgba(13,19,38,.2)),var(--at3-glass);
  border:1px solid var(--at3-line);backdrop-filter:blur(14px) saturate(1.4);
  -webkit-backdrop-filter:blur(14px) saturate(1.4);
  box-shadow:0 12px 38px rgba(2,6,18,.55),inset 0 1px 0 rgba(190,210,255,.08);
  animation:at3-tt .12s ease;}
.at3-root .at3-tooltip[hidden]{display:none!important}
.at3-root .at3-tt-head{display:flex;align-items:center;justify-content:space-between;gap:8px;margin-bottom:7px;}
.at3-root .at3-tt-code{font-weight:800;font-size:13px;letter-spacing:1.2px;}
.at3-root .at3-tt-chip{font-size:9px;font-weight:800;letter-spacing:1.2px;text-transform:uppercase;
  padding:2px 8px;border-radius:999px;border:1px solid currentColor;background:rgba(11,16,32,.4);}
.at3-root .at3-tt-bar{height:5px;border-radius:3px;background:rgba(120,140,190,.16);overflow:hidden;margin-bottom:8px;}
.at3-root .at3-tt-fill{height:100%;border-radius:3px;transition:width .2s ease;}
.at3-root .at3-tt-rows{display:grid;grid-template-columns:auto 1fr;gap:3px 12px;}
.at3-root .at3-tt-k{color:var(--at3-muted);}
.at3-root .at3-tt-v{text-align:right;font-weight:700;font-variant-numeric:tabular-nums;}
.at3-root .at3-bad{color:#ef4444}
.at3-root .at3-warn{color:#facc15}
.at3-root .at3-good{color:#22c55e}
@keyframes at3-tt{from{opacity:0;transform:translateY(3px)}to{opacity:1;transform:none}}

/* --- mid-width: lift view bar above the bottom-left legend --- */
@media (min-width:761px) and (max-width:1020px){
  .at3-root .at3-views{bottom:calc(var(--at3-bottom) + 88px);}
}

/* --- small screens: stack the HUD under the header, keep the bottom free --- */
@media (max-width:760px){
  .at3-root .at3-title{left:8px;right:8px;max-width:none;padding:8px 12px;}
  .at3-root .at3-title-text{font-size:12px;letter-spacing:1.4px;white-space:normal;}
  .at3-root .at3-sub{display:none;}
  .at3-root .at3-floors{top:calc(var(--at3-top) + var(--at3-m1,50px));left:8px;right:8px;width:auto;
    max-height:none;padding:8px;}
  .at3-root .at3-floor-list{flex-direction:row;overflow-x:auto;overflow-y:hidden;gap:4px;padding-bottom:2px;}
  .at3-root .at3-floor-btn{width:auto;min-width:48px;flex:none;justify-content:center;gap:5px;padding:5px 8px;}
  .at3-root .at3-floor-btn[data-floor="all"]{margin-bottom:0;}
  .at3-root .at3-views{top:calc(var(--at3-top) + var(--at3-m2,122px));bottom:auto;left:8px;right:8px;transform:none;
    justify-content:center;flex-wrap:wrap;border-radius:14px;}
  .at3-root .at3-view-btn{padding:6px 10px;font-size:10px;}
  .at3-root .at3-legend{top:calc(var(--at3-top) + var(--at3-m3,172px));bottom:auto;left:8px;right:8px;width:auto;padding:8px 12px;}
  .at3-root .at3-legend-grid{grid-template-columns:repeat(4,1fr);gap:6px;margin-top:6px;}
  .at3-root .at3-legend-item{font-size:10px;gap:4px;}
}

@media (prefers-reduced-motion:reduce){
  .at3-root .at3-badge-dot{animation:none}
  .at3-root .at3-tooltip{animation:none}
  .at3-root .at3-floor-btn,.at3-root .at3-view-btn,.at3-root .at3-tt-fill{transition:none}
}
`;

const LOGO_SVG =
  '<svg class="at3-logo" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" aria-hidden="true">' +
  '<rect x="6" y="3" width="12" height="18" rx="1.5"/>' +
  '<path d="M6 7.5h12M6 12h12M6 16.5h12M12 3v18"/>' +
  '<path d="M3.5 21h17" stroke-linecap="round"/></svg>';

/** Create an element with class + text in one call. */
function el(tag, cls, text) {
  const n = document.createElement(tag);
  if (cls) n.className = cls;
  if (text != null) n.textContent = text;
  return n;
}

export function build(ctx = {}) {
  // Empty scene group — the HUD lives in the DOM, not in the 3D scene.
  const THREE = ctx.THREE ?? null;
  const group = typeof THREE?.Group === 'function'
    ? new THREE.Group()
    : { name: ROOT_ID, children: [], add() {}, remove() {} };
  group.name = ROOT_ID;
  if (typeof ctx.root?.add === 'function') ctx.root.add(group);

  const noop = { group, setStatus() {}, bindCamera() {}, showTooltip() {}, hideTooltip() {}, dispose() {} };
  if (typeof document === 'undefined') return noop; // Node / SSR guard

  // --- mount -----------------------------------------------------------------
  const helpers = ctx.helpers ?? {};
  const host = typeof helpers.uiHost?.appendChild === 'function' ? helpers.uiHost : (document.body ?? document.documentElement);
  document.getElementById(ROOT_ID)?.remove(); // idempotent re-build
  document.getElementById(STYLE_ID)?.remove();
  const style = document.createElement('style');
  style.id = STYLE_ID;
  style.textContent = CSS;
  document.head.appendChild(style);

  const root = el('div', 'at3-root');
  root.id = ROOT_ID;
  if (host !== document.body) root.style.position = 'absolute';
  host.appendChild(root);

  let disposed = false;
  let cameraApi = null;
  let lastStatus = null;
  const sevLabels = {};
  const ttLabels = {};
  const viewLabels = new Map();

  // --- title bar ---------------------------------------------------------------
  const title = el('section', 'at3-card at3-title');
  const titleRow = el('div', 'at3-title-row');
  const logoWrap = el('span');
  logoWrap.innerHTML = LOGO_SVG; // static markup, no user data
  const titleText = el('h1', 'at3-title-text', '');
  const badge = el('span', 'at3-badge');
  const badgeText = document.createTextNode('');
  badge.append(el('span', 'at3-badge-dot'), badgeText);
  titleRow.append(logoWrap.firstChild, titleText, badge);
  const sub = el('div', 'at3-sub', '');
  title.append(titleRow, sub);
  root.appendChild(title);

  // --- floor selector (ALL + F10..F01, worst-severity dot per floor) ------------
  const floorsCard = el('section', 'at3-card at3-floors');
  const floorsHead = el('h2', 'at3-card-head', '');
  floorsCard.append(floorsHead);
  const floorList = el('div', 'at3-floor-list');
  floorList.setAttribute('role', 'group');
  const floorBtns = new Map(); // 'all' | 0-based index -> { btn, dot }

  function makeFloorBtn(key) {
    const btn = el('button', 'at3-floor-btn');
    btn.type = 'button';
    btn.dataset.floor = String(key);
    btn.setAttribute('aria-pressed', String(key === 'all'));
    const dot = el('span', 'at3-floor-dot');
    const label = el('span', 'at3-floor-label', '');
    btn.append(label, dot);
    btn.addEventListener('click', () => selectFloor(key === 'all' ? null : Number(key)));
    floorList.appendChild(btn);
    floorBtns.set(key, { btn, dot, label });
  }
  makeFloorBtn('all');
  for (let i = BUILDING.floors - 1; i >= 0; i--) makeFloorBtn(i);
  floorsCard.appendChild(floorList);
  root.appendChild(floorsCard);

  // --- legend (live zone counts per severity) -----------------------------------
  const legend = el('section', 'at3-card at3-legend');
  const legendHead = el('h2', 'at3-card-head');
  const legendLabel = el('span', null, '');
  const legendTotal = el('span', 'at3-pill', '0');
  legendHead.append(legendLabel, legendTotal);
  const legendGrid = el('div', 'at3-legend-grid');
  const countEls = {};
  for (const s of SEVS) {
    const item = el('div', 'at3-legend-item');
    const sw = el('i', 'at3-sw');
    const c = hex(STATUS_COLORS[s]);
    sw.style.background = c;
    sw.style.boxShadow = `0 0 7px ${c}66`;
    const count = el('span', 'at3-legend-count', '0');
    countEls[s] = count;
    const label = el('span', null, '');
    sevLabels[s] = label;
    item.append(sw, label, count);
    legendGrid.appendChild(item);
  }
  legend.append(legendHead, legendGrid);
  root.appendChild(legend);

  // --- view toggles ---------------------------------------------------------------
  const viewsBar = el('nav', 'at3-card at3-views');
  const viewBtns = new Map();
  for (const v of VIEWS) {
    const btn = el('button', 'at3-view-btn');
    btn.type = 'button';
    btn.dataset.view = v.id;
    btn.setAttribute('aria-pressed', String(v.id === 'overview'));
    const label = el('span', null, '');
    btn.append(el('span', 'at3-view-glyph', v.glyph), label);
    btn.addEventListener('click', () => selectView(v.id));
    viewsBar.appendChild(btn);
    viewBtns.set(v.id, btn);
    viewLabels.set(v.id, { btn, label, key: v.key });
  }
  root.appendChild(viewsBar);

  // --- zone tooltip ----------------------------------------------------------------
  const tt = el('div', 'at3-tooltip');
  tt.id = 'at3-tooltip';
  tt.hidden = true;
  tt.setAttribute('role', 'tooltip');
  const ttHead = el('div', 'at3-tt-head');
  const ttCode = el('span', 'at3-tt-code', '–');
  const ttChip = el('span', 'at3-tt-chip', '–');
  ttHead.append(ttCode, ttChip);
  const ttBar = el('div', 'at3-tt-bar');
  const ttFill = el('div', 'at3-tt-fill');
  ttBar.appendChild(ttFill);
  const ttRows = el('div', 'at3-tt-rows');
  const ttVals = {};
  for (const [key, labelKey] of [
    ['devices', 'tooltip.devices'], ['online', 'tooltip.online'], ['received', 'tooltip.received'],
    ['acknowledged', 'tooltip.acknowledged'], ['failed', 'tooltip.failed'], ['unacked', 'tooltip.unacked'],
  ]) {
    const v = el('span', 'at3-tt-v', '–');
    const k = el('span', 'at3-tt-k', '');
    ttVals[key] = v;
    ttLabels[key] = { node: k, key: labelKey };
    ttRows.append(k, v);
  }
  tt.append(ttHead, ttBar, ttRows);
  root.appendChild(tt);

  // --- camera / event wiring ---------------------------------------------------------
  function callCamera(names, ...args) {
    if (!cameraApi) return false;
    for (const n of names) {
      if (typeof cameraApi[n] === 'function') {
        try { cameraApi[n](...args); return true; }
        catch (err) { console.warn(`[at3-hud] cameraApi.${n} failed:`, err); }
      }
    }
    return false;
  }

  function selectFloor(i) {
    if (disposed) return;
    for (const [key, { btn }] of floorBtns) {
      btn.setAttribute('aria-pressed', String(key === (i === null ? 'all' : i)));
    }
    if (i === null) {
      if (!callCamera(['showAll', 'showAllFloors', 'resetView', 'reset'])) callCamera(['focusFloor'], null);
    } else {
      callCamera(['focusFloor', 'setFloor', 'flyToFloor', 'isolateFloor'], i);
    }
    const detail = { floor: i, floorNumber: i === null ? null : i + 1 };
    try { helpers.onFloorChange?.(detail); } catch (err) { console.warn('[at3-hud] onFloorChange failed:', err); }
    window.dispatchEvent(new CustomEvent('aegis-ui:floor', { detail }));
  }

  function selectView(id) {
    if (disposed) return;
    for (const [vid, btn] of viewBtns) btn.setAttribute('aria-pressed', String(vid === id));
    if (!callCamera(['setView', 'setMode'], id)) callCamera([id]);
    try { helpers.onViewChange?.(id); } catch (err) { console.warn('[at3-hud] onViewChange failed:', err); }
    window.dispatchEvent(new CustomEvent('aegis-ui:view', { detail: { view: id } }));
  }

  // --- public API ---------------------------------------------------------------------
  function setStatus(status) {
    if (disposed || !Array.isArray(status?.floors)) return;
    lastStatus = status;
    const counts = { online: 0, warning: 0, critical: 0, offline: 0 };
    const worst = new Map(); // 0-based floor index -> rank
    status.floors.forEach((f, fi) => {
      const idx = Number.isFinite(f?.floorNumber) ? f.floorNumber - 1 : fi;
      for (const z of f?.zones ?? []) {
        const s = sevOf(z);
        counts[s] += 1;
        const r = SEV_RANK[s];
        if (r > (worst.get(idx) ?? -1)) worst.set(idx, r);
      }
    });
    let total = 0;
    for (const s of SEVS) { countEls[s].textContent = fmt(counts[s]); total += counts[s]; }
    legendTotal.textContent = fmt(total);
    for (const [key, { btn, dot }] of floorBtns) {
      if (key === 'all') {
        const ranks = [...worst.values()];
        const r = ranks.length ? Math.max(...ranks) : SEV_RANK.offline;
        paintDot(dot, RANK_SEV[r]);
        btn.title = t('hud.allWorst', { sev: t(`sev.${RANK_SEV[r]}`) });
        continue;
      }
      const s = RANK_SEV[worst.get(key) ?? SEV_RANK.offline];
      paintDot(dot, s);
      btn.title = t('hud.floorWorst', { n: Number(key) + 1, sev: t(`sev.${s}`) });
    }
  }

  function paintDot(dot, sev) {
    const c = hex(STATUS_COLORS[sev]);
    dot.style.background = c;
    dot.style.boxShadow = `0 0 6px ${c}`;
  }

  function showTooltip(zoneData, x, y) {
    if (disposed || !zoneData) return;
    const sev = sevOf(zoneData);
    const c = hex(STATUS_COLORS[sev]);
    const code = zoneData.zoneCode
      ?? (Number.isFinite(zoneData.floor) && Number.isFinite(zoneData.zone)
        ? zoneCode(zoneData.floor, zoneData.zone) : 'ZONE');
    ttCode.textContent = code;
    ttChip.textContent = sev.toUpperCase();
    ttChip.style.color = c;
    const total = Number(zoneData.devicesTotal) || 0;
    const online = Number(zoneData.online) || 0;
    const received = Number(zoneData.received) || 0;
    const ack = Number(zoneData.acknowledged) || 0;
    const failed = Number(zoneData.failed) || 0;
    const unacked = Math.max(0, received - ack);
    ttFill.style.width = `${total > 0 ? Math.min(100, (online / total) * 100) : 0}%`;
    ttFill.style.background = c;
    ttVals.devices.textContent = fmt(total);
    ttVals.online.textContent = fmt(online);
    ttVals.online.className = `at3-tt-v ${online === 0 ? 'at3-bad' : online < total ? 'at3-warn' : 'at3-good'}`;
    ttVals.received.textContent = fmt(received);
    ttVals.acknowledged.textContent = fmt(ack);
    ttVals.acknowledged.className = `at3-tt-v ${received > 0 && ack >= received ? 'at3-good' : ''}`;
    ttVals.failed.textContent = fmt(failed);
    ttVals.failed.className = `at3-tt-v ${failed > 0 ? 'at3-bad' : ''}`;
    ttVals.unacked.textContent = fmt(unacked);
    ttVals.unacked.className = `at3-tt-v ${unacked > 0 ? 'at3-warn' : ''}`;
    tt.hidden = false;
    // Position in viewport space, then convert to root-local coords.
    const r = root.getBoundingClientRect();
    const w = tt.offsetWidth;
    const h = tt.offsetHeight;
    const vw = window.innerWidth;
    const vh = window.innerHeight;
    let vx = x + 16;
    if (vx + w > vw - 8) vx = x - w - 16;
    vx = Math.max(8, Math.min(vx, vw - w - 8));
    let vy = y + 16;
    if (vy + h > vh - 8) vy = y - h - 16;
    vy = Math.max(8, Math.min(vy, vh - h - 8));
    tt.style.left = `${Math.round(vx - r.left)}px`;
    tt.style.top = `${Math.round(vy - r.top)}px`;
  }

  function hideTooltip() {
    tt.hidden = true;
  }

  /** Re-translate every dynamic HUD label (called on VI/EN switch). */
  function refreshTexts() {
    if (disposed) return;
    titleText.textContent = t('hud.title', { name: BUILDING.name.toUpperCase() });
    badgeText.textContent = t('hud.simulation');
    sub.textContent = t('hud.subtitle', {
      floors: BUILDING.floors,
      zones: BUILDING.zonesPerFloor,
      sectors: BUILDING.floors * BUILDING.zonesPerFloor,
    });
    floorsHead.textContent = t('hud.floor');
    floorList.setAttribute('aria-label', t('hud.floorSelector'));
    for (const [key, { btn, label }] of floorBtns) {
      label.textContent = key === 'all' ? t('hud.all') : floorLabel(Number(key));
      btn.title = key === 'all' ? t('hud.showAllFloors') : t('hud.floorN', { n: Number(key) + 1 });
    }
    legendLabel.textContent = t('hud.zoneStatus');
    for (const s of SEVS) sevLabels[s].textContent = t(`sev.${s}`);
    viewsBar.setAttribute('aria-label', t('hud.viewMode'));
    for (const { btn, label, key } of viewLabels.values()) {
      const text = t(key);
      label.textContent = text;
      btn.title = t('hud.viewTitle', { view: text });
    }
    for (const { node, key } of Object.values(ttLabels)) node.textContent = t(key);
    if (lastStatus) setStatus(lastStatus);
  }
  refreshTexts();
  const unsubscribeI18n = subscribe(() => refreshTexts());

  function bindCamera(api) {
    cameraApi = api ?? null;
  }

  // --- responsive layout + tab awareness ------------------------------------------------
  function layout() {
    if (disposed) return;
    const header = document.getElementById('hud-top');
    const foot = document.getElementById('hud-foot');
    const hh = header ? header.getBoundingClientRect().height : 0;
    const fh = foot ? foot.getBoundingClientRect().height : 0;
    root.style.setProperty('--at3-top', `${Math.round(hh) + 10}px`);
    root.style.setProperty('--at3-bottom', `${Math.round(fh) + 10}px`);
    // Mobile stack: derive offsets from actual card heights (view bar may wrap).
    if (window.innerWidth <= 760) {
      const gap = 8;
      const m1 = Math.round(title.offsetHeight + gap);
      const m2 = m1 + Math.round(floorsCard.offsetHeight + gap);
      const m3 = m2 + Math.round(viewsBar.offsetHeight + gap);
      root.style.setProperty('--at3-m1', `${m1}px`);
      root.style.setProperty('--at3-m2', `${m2}px`);
      root.style.setProperty('--at3-m3', `${m3}px`);
    }
  }
  function syncTab() {
    if (disposed) return;
    const tab = document.body?.dataset?.tab;
    root.hidden = tab != null && tab !== 'twin';
    if (root.hidden) hideTooltip();
  }
  window.addEventListener('resize', layout);
  const mo = new MutationObserver(syncTab);
  if (document.body) mo.observe(document.body, { attributes: true, attributeFilter: ['data-tab'] });
  let ro = null;
  if (typeof ResizeObserver !== 'undefined') {
    ro = new ResizeObserver(layout);
    const header = document.getElementById('hud-top');
    const foot = document.getElementById('hud-foot');
    if (header) ro.observe(header);
    if (foot) ro.observe(foot);
  }
  layout();
  syncTab();
  if (document.fonts?.ready) document.fonts.ready.then(layout).catch(() => {});

  function dispose() {
    if (disposed) return;
    disposed = true;
    hideTooltip();
    mo.disconnect();
    ro?.disconnect();
    unsubscribeI18n();
    window.removeEventListener('resize', layout);
    root.remove();
    style.remove();
    cameraApi = null;
  }

  return { group, setStatus, bindCamera, showTooltip, hideTooltip, dispose };
}
