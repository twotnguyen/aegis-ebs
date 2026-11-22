// ---------------------------------------------------------------------------
// Aegis EBS — digital twin dashboard (Vite + Three.js)
// AEGIS Tower B01: 10 floors x 4 zones (2x2), assembled from the independent
// 3D modules in src/three/ (see CONTRACT.md). Each module build(ctx) adds its
// own group to ctx.root and returns { group, update?, setStatus?, dispose? }.
// Zone severity colors / heatmaps / device markers / alert beacons / ACK
// particles are owned by statusViz.js; the HUD legend + floor selector + view
// toggles + tooltip are owned by ui.js; navigation (orbit / fly-to-floor /
// cutaway / explode / isolation) by camera.js; day/night rig by lighting.js.
// Data: src/ws.js (live WebSocket → mock fallback), contract in src/data.js.
// Views (tabs, keys 1–6): Twin · Incidents (timeline/replay) · Chaos ·
// Reports (p50/p95/p99 + CSV/JSON) · Funnel (per-alert delivery funnel) ·
// Operator (REAL publish/cancel/fleet via the HTTP bridge at :9445; the
// Chaos tab remains mock-only).
// The 3D twin keeps rendering behind every panel; replay repaints it from
// INCIDENT_EVENT payload counters. If WebGL is unavailable the panels still
// work (a notice replaces the scene).
// ---------------------------------------------------------------------------

import * as THREE from 'three';
import './styles.css';
import {
  WS_URL,
  BUILDING_ID,
  SEVERITY_COLORS,
  applyDelta,
} from './data.js';
import { createStatusFeed } from './ws.js';
import { createEventStore, eventsFromDelta, makeEvent, makeMockIncident } from './events.js';
import { createReplay } from './replay.js';
import { createChaosEngine } from './chaos.js';
import { createFunnelTracker } from './funnel.js';
import { createMetricsHistory } from './reports.js';
import { createPanels } from './panels.js';

// --- 3D modules (foundation + 9 base modules + extras) -----------------------
import * as config from './three/config.js';
import { makeMaterials } from './three/materials.js';
import { build as buildStructure } from './three/structure.js';
import { build as buildFacade } from './three/facade.js';
import { build as buildInterior } from './three/interior.js';
import { build as buildSafety } from './three/safety.js';
import { build as buildEnvironment } from './three/environment.js';
import { build as buildLighting, applyRenderer as applyLightingRenderer, SKY } from './three/lighting.js';
import { build as buildStatusViz } from './three/statusViz.js';
import { build as buildCamera, attach as attachCamera } from './three/camera.js';
import { build as buildUi } from './three/ui.js';
import { build as buildRoofMech } from './three/roofMech.js';
import { build as buildCityContext } from './three/cityContext.js';
import { build as buildDecals } from './three/decals.js';
// weather.js: not present in src/three/ — skipped (no weather module to wire).

// --- DOM --------------------------------------------------------------------

const $ = (id) => document.getElementById(id);
const canvas = $('scene');
const badgeEl = $('conn-badge');
const alertsEl = $('alerts');
const alertCountEl = $('alert-count');
const tooltipEl = $('tooltip');
const feedModeEl = $('feed-mode');
const lastUpdateEl = $('last-update');
const replayBadgeEl = $('replay-badge');

// --- core objects -------------------------------------------------------------

let state = null;
let panels = null;
let demoCount = 0;

const store = createEventStore({ limit: 1000 });
const history = createMetricsHistory({ limit: 900 });
const funnel = createFunnelTracker({ limit: 100 });
const replay = createReplay({ tickMs: 800 });
const chaos = createChaosEngine({
  emit: (partial) => store.append(makeEvent(partial)),
  onLog: () => panels?.refreshIfActive('chaos'),
});

const replayActive = () => replay.playing || replay.index >= 0;

function appendDemo() {
  demoCount += 1;
  store.appendMany(
    makeMockIncident({
      incidentId: `INC-DEMO-${String(demoCount).padStart(3, '0')}`,
      startTs: Date.now() - 180000,
    }),
  );
}

// --- 3D twin ------------------------------------------------------------------

const ZONE_CODE_RE = /^F(\d{1,2})-([A-D])$/;
const SEVERITY_RANK = { online: 0, offline: 1, warning: 2, critical: 3 };

function initTwin() {
  const renderer = new THREE.WebGLRenderer({ canvas, antialias: true });
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
  renderer.setSize(window.innerWidth, window.innerHeight);
  applyLightingRenderer(renderer); // PCFSoft shadows + ACESFilmic tone mapping

  const scene = new THREE.Scene();
  scene.background = new THREE.Color(SKY.day.background);
  // Sky dome (r=480) and skyline silhouettes are fog:false; fog only hazes the
  // far city geometry. lighting.js cross-fades the fog color day <-> night.
  scene.fog = new THREE.Fog(SKY.day.fog, 150, 470);

  const root = new THREE.Group();
  root.name = 'aegis-twin-root';
  scene.add(root);

  const materials = makeMaterials();
  const camera = new THREE.PerspectiveCamera(45, window.innerWidth / window.innerHeight, 0.1, 1600);
  camera.name = 'twin-camera';
  const controls = attachCamera(camera, canvas); // tuned OrbitControls (camera.js)

  const api = {};            // module name -> build() result
  const moduleErrors = [];   // [{ module, message }] — reported, never fatal

  const helpers = {
    // ui.js view toggles: 'night' is owned by lighting.js, 'overview' returns
    // to day; cutaway/explode are camera modes and leave the lighting alone.
    onViewChange: (view) => {
      if (view === 'night') api.lighting?.setMode?.('night');
      else if (view === 'overview') api.lighting?.setMode?.('day');
    },
  };

  const ctx = {
    THREE, config, materials, root, helpers,
    camera, controls, domElement: canvas, renderer,
    aspect: window.innerWidth / window.innerHeight,
  };

  function safeBuild(name, fn) {
    try {
      api[name] = fn(ctx);
    } catch (err) {
      moduleErrors.push({ module: name, message: String(err?.message ?? err) });
      console.error(`[twin] module "${name}" failed to build:`, err);
    }
    return api[name];
  }

  // Build order: surroundings → structure → envelope → interiors → safety →
  // paint → roof mechanics → light rig → status overlays → camera → DOM HUD.
  // Every module adds its own group to ctx.root (CONTRACT.md).
  safeBuild('environment', buildEnvironment);
  safeBuild('cityContext', buildCityContext);
  safeBuild('structure', buildStructure);
  safeBuild('facade', buildFacade);
  safeBuild('interior', buildInterior);
  safeBuild('safety', buildSafety);
  safeBuild('decals', buildDecals);
  safeBuild('roofMech', buildRoofMech);
  safeBuild('lighting', buildLighting);
  safeBuild('statusViz', buildStatusViz);
  safeBuild('camera', buildCamera);
  safeBuild('ui', buildUi);

  // Fallbacks so a single broken module never blacks out the twin.
  if (!api.lighting) {
    const hemi = new THREE.HemisphereLight(SKY.day.hemiSky, SKY.day.hemiGround, 1.0);
    const sun = new THREE.DirectionalLight(0xfff2dd, 3.0);
    sun.position.set(70, 95, 45);
    root.add(hemi, sun);
  }
  const cameraApi = api.camera ?? null;
  const lightingApi = api.lighting ?? null;
  const statusVizApi = api.statusViz ?? null;
  const uiApi = api.ui ?? null;
  if (uiApi?.bindCamera && cameraApi) uiApi.bindCamera(cameraApi);

  // --- tagged zone meshes (interior floor plates) for tooltip + highlight ----
  const zoneMeshes = new Map(); // zoneCode -> mesh
  const zoneMeshList = [];
  root.traverse((obj) => {
    if (obj.isMesh && typeof obj.userData?.zoneCode === 'string'
        && ZONE_CODE_RE.test(obj.userData.zoneCode) && !obj.userData.statusVizProxy) {
      zoneMeshes.set(obj.userData.zoneCode, obj);
      zoneMeshList.push(obj);
    }
  });
  const zoneData = new Map(); // zoneCode -> live status/1 zone (for tooltips)

  // --- roof severity beacon (assembler-owned; worst severity in building) ----
  const B = config.BUILDING;
  const H = config.totalHeight();
  const beaconMat = new THREE.MeshStandardMaterial({
    color: SEVERITY_COLORS.offline,
    emissive: SEVERITY_COLORS.offline,
    emissiveIntensity: 0.6,
    roughness: 0.3,
    transparent: true,
    opacity: 0.95,
  });
  const beaconGroup = new THREE.Group();
  beaconGroup.name = 'twin-severity-beacon';
  beaconGroup.userData = { floor: 'roof', part: 'severity-beacon' };
  const beaconBase = new THREE.Mesh(new THREE.CylinderGeometry(0.14, 0.2, 0.5, 12), materials.steel);
  beaconBase.position.y = 0.25;
  beaconBase.castShadow = true;
  const beaconBulb = new THREE.Mesh(new THREE.SphereGeometry(0.3, 16, 12), beaconMat);
  beaconBulb.position.y = 0.72;
  beaconGroup.add(beaconBase, beaconBulb);
  // Front-left roof quadrant, clear of the facade shield mast (-11, +7.5),
  // helipad (+11, +6.5), core overrun and HVAC units.
  beaconGroup.position.set(-B.width / 4, H, B.depth / 4 - 6);
  root.add(beaconGroup);

  // --- replay highlight cage (scene-level: camera.js must not move it) -------
  const zr = config.zoneRect(0);
  const highlightMesh = new THREE.Mesh(
    new THREE.BoxGeometry(zr.w + 0.4, B.floorHeight * 0.92, zr.d + 0.4),
    new THREE.MeshBasicMaterial({ color: 0x38bdf8, wireframe: true, transparent: true, opacity: 0.9, depthWrite: false }),
  );
  highlightMesh.name = 'replay-highlight';
  highlightMesh.visible = false;
  highlightMesh.renderOrder = 6;
  scene.add(highlightMesh);
  let highlightTarget = null;
  const _hlPos = new THREE.Vector3();

  // --- painting ---------------------------------------------------------------
  let paintedState = null; // last model handed to paint() (live or replay)

  function paint(s) {
    if (!s) return;
    paintedState = s;
    zoneData.clear();
    let worst = 'online';
    for (const floor of s.floors ?? []) {
      for (const zone of floor.zones ?? []) {
        if (!zone?.zoneCode) continue;
        zoneData.set(zone.zoneCode, zone);
        const mesh = zoneMeshes.get(zone.zoneCode);
        if (mesh) mesh.userData.zone = zone;
        if ((SEVERITY_RANK[zone.severity] ?? 0) > SEVERITY_RANK[worst]) worst = zone.severity;
      }
    }
    if (statusVizApi) {
      statusVizApi.setStatus(s); // zone tint + heatmap + markers + beacons + ACK
    } else {
      // Fallback: direct plate recolor (interior plates own unique materials).
      for (const [code, mesh] of zoneMeshes) {
        const zone = zoneData.get(code);
        if (!zone) continue;
        const color = SEVERITY_COLORS[zone.severity] ?? SEVERITY_COLORS.offline;
        mesh.material.color.setHex(color);
        mesh.material.emissive.setHex(color);
        mesh.material.emissiveIntensity = zone.severity === 'critical' ? 0.35 : 0.12;
      }
    }
    uiApi?.setStatus?.(s); // HUD legend counts + per-floor severity dots
    const beaconColor = SEVERITY_COLORS[worst] ?? SEVERITY_COLORS.offline;
    beaconMat.color.setHex(beaconColor);
    beaconMat.emissive.setHex(beaconColor);
  }

  function highlight(zoneCode) {
    if (!zoneCode) {
      highlightTarget = null;
      highlightMesh.visible = false;
      return;
    }
    const mesh = zoneMeshes.get(zoneCode);
    if (!mesh) {
      highlightTarget = null;
      highlightMesh.visible = false;
      return;
    }
    highlightTarget = mesh;
    highlightMesh.visible = true;
  }

  // --- hover tooltip (raycast zone plates; ui.js renders the card) ------------
  const raycaster = new THREE.Raycaster();
  const pointer = new THREE.Vector2();
  let pointerPx = { x: 0, y: 0 };
  let pointerDirty = false;

  canvas.addEventListener('pointermove', (ev) => {
    pointer.x = (ev.clientX / window.innerWidth) * 2 - 1;
    pointer.y = -(ev.clientY / window.innerHeight) * 2 + 1;
    pointerPx = { x: ev.clientX, y: ev.clientY };
    pointerDirty = true;
  });
  canvas.addEventListener('pointerleave', () => {
    tooltipEl.hidden = true;
    uiApi?.hideTooltip?.();
    pointerDirty = false;
  });

  function chainVisible(obj) {
    let o = obj;
    while (o) {
      if (!o.visible) return false;
      if (o === root || o === scene) return true;
      o = o.parent;
    }
    return false;
  }

  function updateTooltip() {
    if (!pointerDirty) return;
    pointerDirty = false;
    raycaster.setFromCamera(pointer, camera);
    const hits = raycaster.intersectObjects(zoneMeshList, false);
    const hit = hits.find((h) => chainVisible(h.object));
    const code = hit?.object.userData.zoneCode;
    const zone = code ? (zoneData.get(code) ?? hit.object.userData.zoneStatus ?? hit.object.userData.zone) : null;
    if (!zone) {
      uiApi?.hideTooltip?.();
      tooltipEl.hidden = true;
      return;
    }
    if (uiApi?.showTooltip) {
      uiApi.showTooltip(zone, pointerPx.x, pointerPx.y);
      tooltipEl.hidden = true;
      return;
    }
    // Legacy fallback tooltip (ui.js unavailable).
    tooltipEl.innerHTML = '';
    const title = document.createElement('div');
    title.className = 'tt-title';
    title.textContent = `${zone.zoneCode} · ${String(zone.severity ?? 'offline').toUpperCase()}`;
    const body = document.createElement('div');
    body.textContent =
      `devices ${zone.online}/${zone.devicesTotal} online · ` +
      `recv ${zone.received} · ack ${zone.acknowledged} · failed ${zone.failed}`;
    tooltipEl.append(title, body);
    tooltipEl.style.left = `${Math.min(pointerPx.x + 14, window.innerWidth - 320)}px`;
    tooltipEl.style.top = `${Math.min(pointerPx.y + 14, window.innerHeight - 70)}px`;
    tooltipEl.hidden = false;
  }

  // --- resize -------------------------------------------------------------------
  window.addEventListener('resize', () => {
    const w = window.innerWidth;
    const h = window.innerHeight;
    renderer.setSize(w, h);
    if (cameraApi?.resize) cameraApi.resize(w, h);
    else {
      camera.aspect = w / h;
      camera.updateProjectionMatrix();
    }
  });

  // --- render loop ----------------------------------------------------------------
  const clock = new THREE.Clock();
  renderer.setAnimationLoop(() => {
    const dt = Math.min(clock.getDelta(), 0.1);
    const t = clock.elapsedTime;
    const s = paintedState;

    if (cameraApi) cameraApi.update(dt); // flights, explode, re-scan, controls
    else controls.update();
    lightingApi?.update?.(dt);           // day/night cross-fade + exposure
    statusVizApi?.update?.(s, dt);       // pulses, beacons, ACK particles
    api.facade?.update?.(s, dt);         // roof shield-logo pulse
    api.safety?.update?.(s, dt);         // alarm strobes per zone severity
    api.roofMech?.update?.(s, dt);       // HVAC fans + aviation beacons

    beaconMat.emissiveIntensity = 0.55 + 0.45 * (0.5 + 0.5 * Math.sin(t * 2.4));

    if (highlightMesh.visible && highlightTarget) {
      highlightTarget.getWorldPosition(_hlPos);
      highlightMesh.position.set(_hlPos.x, _hlPos.y + B.floorHeight / 2 - 0.03, _hlPos.z);
      highlightMesh.material.opacity = 0.55 + 0.35 * (0.5 + 0.5 * Math.sin(t * 6));
    }

    updateTooltip();
    renderer.render(scene, camera);
  });

  // Debug/verification handle (used by smoke tests; harmless in production).
  window.__AEGIS_DEBUG__ = { renderer, scene, camera, root, controls, api, moduleErrors, zoneMeshes, paint, highlight };

  if (moduleErrors.length) {
    console.warn('[twin] modules failed:', moduleErrors.map((e) => `${e.module}: ${e.message}`).join(' | '));
  }
  return { paint, highlight, moduleErrors };
}

let twin = null;
try {
  twin = initTwin();
} catch (err) {
  console.error('WebGL init failed — panels remain usable:', err);
  const notice = document.createElement('div');
  notice.className = 'panel webgl-notice';
  notice.textContent = `WebGL is not available in this browser (${err.message}). The 3D twin is disabled; timeline, chaos, reports and funnel panels still work.`;
  $('app').append(notice);
}

const paint = (s = state) => twin?.paint(s);
const highlightZone = (code) => twin?.highlight(code);

// --- HUD ------------------------------------------------------------------------

const fmt = (v) => (typeof v === 'number' ? v.toLocaleString('en-US') : '–');
function renderHud(s = state) {
  if (!s) return;
  const m = s.metrics ?? {};
  $('m-connected').textContent = fmt(m.connected);
  $('m-p50').textContent = fmt(m.p50);
  $('m-p95').textContent = fmt(m.p95);
  $('m-p99').textContent = fmt(m.p99);
  $('m-retry').textContent = fmt(m.retryCount);

  const alerts = s.activeAlerts ?? [];
  alertCountEl.textContent = String(alerts.length);
  alertsEl.replaceChildren(
    ...alerts.map((a) => {
      const li = document.createElement('li');
      li.className = `alert alert-${a.severity}`;
      li.title = 'Open delivery funnel for this alert';
      const head = document.createElement('strong');
      head.textContent = a.zoneCode;
      li.append(head, document.createTextNode(` ${a.message}`));
      li.addEventListener('click', () => panels?.selectAlert(a.alertId));
      return li;
    }),
  );
  if (alerts.length === 0) {
    const li = document.createElement('li');
    li.className = 'alert alert-none';
    li.textContent = 'No active alerts';
    alertsEl.replaceChildren(li);
  }
  lastUpdateEl.textContent = `last update: ${new Date(s.ts).toLocaleTimeString()}`;
}

const BADGE = {
  connecting: ['CONNECTING…', 'badge-connecting'],
  live: ['LIVE', 'badge-live'],
  reconnecting: ['RECONNECTING…', 'badge-reconnecting'],
  mock: ['MOCK FEED', 'badge-mock'],
  closed: ['CLOSED', 'badge-offline'],
};

function setMode(mode) {
  const [text, cls] = BADGE[mode] ?? BADGE.connecting;
  badgeEl.textContent = text;
  badgeEl.className = `badge ${cls}`;
  feedModeEl.textContent =
    mode === 'live' ? `feed: live ${feed.url}` : mode === 'mock' ? 'feed: mock (server offline)' : `feed: ${mode}`;
  panels?.refreshIfActive('chaos');
}

// --- replay wiring ----------------------------------------------------------------

replay.onPaint = (replayState, event) => {
  if (replayState) {
    paint(replayState);
    renderHud(replayState);
    highlightZone(event?.payload?.zoneCode ?? null);
    replayBadgeEl.hidden = false;
  } else {
    paint(state);
    renderHud(state);
    highlightZone(null);
    replayBadgeEl.hidden = true;
  }
};

// --- panels ------------------------------------------------------------------------

panels = createPanels({
  store,
  chaos,
  funnel,
  history,
  replay,
  feed: null, // assigned right after createStatusFeed below
  getState: () => state,
  buildingId: BUILDING_ID,
  appendDemo,
});
replay.onCursor = (cursor) => panels.updateCursor(cursor);
store.subscribe(() => panels.refreshIfActive('incidents'));

// --- data feed ---------------------------------------------------------------------

const wsUrl = new URLSearchParams(window.location.search).get('ws') || WS_URL;

const feed = createStatusFeed({
  url: wsUrl,
  mockTransform: (prev, delta) => chaos.transformDelta(prev, delta),
  onSnapshot: (snapshot, meta) => {
    state = snapshot;
    history.sample(state);
    funnel.update(state);
    if (meta.source === 'mock' && store.size === 0) appendDemo(); // seed timeline in mock mode
    afterData();
  },
  onDelta: (delta) => {
    if (!state) return; // server must send a snapshot first
    const prev = state;
    state = applyDelta(state, delta);
    for (const evt of eventsFromDelta(prev, state)) store.append(evt);
    history.sample(state);
    funnel.update(state);
    afterData();
  },
  onEvent: (evt) => store.append(evt), // live INCIDENT_EVENT frames
  onMode: setMode,
});
panels.setFeed(feed);

function afterData() {
  if (!replayActive()) {
    paint();
    renderHud();
  }
  panels.refreshIfActive(panels.activeTab);
}