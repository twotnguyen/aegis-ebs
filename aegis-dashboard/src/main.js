// ---------------------------------------------------------------------------
// Aegis EBS — digital twin dashboard (Vite + Three.js)
// 10-floor building, 4 zones per floor (2x2). Each zone box is colored by
// severity: green=online/acked, yellow=warning, red=critical, gray=offline.
// Data: src/ws.js (live WebSocket → mock fallback), contract in src/data.js.
// Views (tabs, keys 1–5): Twin · Incidents (timeline/replay) · Chaos ·
// Reports (p50/p95/p99 + CSV/JSON) · Funnel (per-alert delivery funnel).
// The 3D twin keeps rendering behind every panel; replay repaints it from
// INCIDENT_EVENT payload counters. If WebGL is unavailable the panels still
// work (a notice replaces the scene).
// ---------------------------------------------------------------------------

import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';
import './styles.css';
import {
  WS_URL,
  BUILDING_ID,
  FLOOR_COUNT,
  ZONE_LETTERS,
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

function initTwin() {
  const renderer = new THREE.WebGLRenderer({ canvas, antialias: true });
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
  renderer.setSize(window.innerWidth, window.innerHeight);
  renderer.shadowMap.enabled = true;
  renderer.shadowMap.type = THREE.PCFSoftShadowMap;

  const scene = new THREE.Scene();
  scene.background = new THREE.Color(0x0b1020);
  scene.fog = new THREE.Fog(0x0b1020, 60, 120);

  const camera = new THREE.PerspectiveCamera(45, window.innerWidth / window.innerHeight, 0.1, 300);
  camera.position.set(20, 15, 24);

  const controls = new OrbitControls(camera, canvas);
  controls.target.set(0, 5.5, 0);
  controls.enableDamping = true;
  controls.dampingFactor = 0.06;
  controls.minDistance = 8;
  controls.maxDistance = 80;
  controls.maxPolarAngle = Math.PI * 0.495;

  scene.add(new THREE.HemisphereLight(0x8899ff, 0x10141f, 0.8));
  const sun = new THREE.DirectionalLight(0xffffff, 1.7);
  sun.position.set(18, 32, 14);
  sun.castShadow = true;
  sun.shadow.mapSize.set(2048, 2048);
  sun.shadow.camera.left = -22;
  sun.shadow.camera.right = 22;
  sun.shadow.camera.top = 26;
  sun.shadow.camera.bottom = -10;
  sun.shadow.camera.far = 90;
  scene.add(sun);

  const ground = new THREE.Mesh(
    new THREE.PlaneGeometry(240, 240),
    new THREE.MeshStandardMaterial({ color: 0x0d1326, roughness: 1 }),
  );
  ground.rotation.x = -Math.PI / 2;
  ground.position.y = -0.02;
  ground.receiveShadow = true;
  scene.add(ground);
  scene.add(new THREE.GridHelper(60, 30, 0x2a3550, 0x1a2238));

  // building: 10 stacked floors × 4 zone boxes
  const FLOOR_H = 1.3;
  const ZONE_SIZE = { w: 3.5, h: 0.95, d: 3.5 };
  const ZONE_OFFSET = 1.9;
  const ZONE_POS = [
    [-ZONE_OFFSET, -ZONE_OFFSET], // A
    [ZONE_OFFSET, -ZONE_OFFSET], //  B
    [-ZONE_OFFSET, ZONE_OFFSET], //  C
    [ZONE_OFFSET, ZONE_OFFSET], //   D
  ];

  const zoneGeometry = new THREE.BoxGeometry(ZONE_SIZE.w, ZONE_SIZE.h, ZONE_SIZE.d);
  const slabGeometry = new THREE.BoxGeometry(8.2, 0.14, 8.2);
  const slabMaterial = new THREE.MeshStandardMaterial({ color: 0x1c2540, roughness: 0.9 });

  const zoneMeshes = new Map(); // zoneCode -> mesh
  const zoneMeshList = [];
  const criticalMeshes = new Set();
  const building = new THREE.Group();
  scene.add(building);

  function makeLabelSprite(text) {
    const c = document.createElement('canvas');
    c.width = 128;
    c.height = 64;
    const ctx = c.getContext('2d');
    ctx.font = 'bold 40px ui-monospace, monospace';
    ctx.fillStyle = '#93a4c8';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillText(text, 64, 34);
    const tex = new THREE.CanvasTexture(c);
    tex.colorSpace = THREE.SRGBColorSpace;
    const sprite = new THREE.Sprite(new THREE.SpriteMaterial({ map: tex, transparent: true }));
    sprite.scale.set(1.7, 0.85, 1);
    return sprite;
  }

  for (let i = 0; i < FLOOR_COUNT; i++) {
    const floorNumber = i + 1;
    const group = new THREE.Group();
    group.position.y = i * FLOOR_H + 0.7;

    const slab = new THREE.Mesh(slabGeometry, slabMaterial);
    slab.position.y = -0.62;
    slab.castShadow = true;
    slab.receiveShadow = true;
    group.add(slab);

    ZONE_LETTERS.forEach((letter, zi) => {
      const zoneCode = `F${String(floorNumber).padStart(2, '0')}-${letter}`;
      const material = new THREE.MeshStandardMaterial({
        color: SEVERITY_COLORS.offline,
        roughness: 0.45,
        metalness: 0.15,
        emissive: SEVERITY_COLORS.offline,
        emissiveIntensity: 0.1,
      });
      const mesh = new THREE.Mesh(zoneGeometry, material);
      mesh.position.set(ZONE_POS[zi][0], 0, ZONE_POS[zi][1]);
      mesh.castShadow = true;
      mesh.receiveShadow = true;
      mesh.userData = { floorNumber, zoneCode, zone: null };
      group.add(mesh);
      zoneMeshes.set(zoneCode, mesh);
      zoneMeshList.push(mesh);
    });

    const label = makeLabelSprite(`F${String(floorNumber).padStart(2, '0')}`);
    label.position.set(-5.4, 0, 0);
    group.add(label);

    building.add(group);
  }

  // Roof slab + status beacon (color = worst severity in the building).
  const roof = new THREE.Mesh(slabGeometry, slabMaterial);
  roof.position.y = FLOOR_COUNT * FLOOR_H + 0.08;
  roof.castShadow = true;
  building.add(roof);
  const beacon = new THREE.Mesh(
    new THREE.CylinderGeometry(0.22, 0.3, 0.9, 16),
    new THREE.MeshStandardMaterial({
      color: SEVERITY_COLORS.offline,
      emissive: SEVERITY_COLORS.offline,
      emissiveIntensity: 0.5,
      roughness: 0.3,
    }),
  );
  beacon.position.y = FLOOR_COUNT * FLOOR_H + 0.6;
  building.add(beacon);

  // Replay highlight: wireframe cage around the zone the current event touches.
  const highlightMesh = new THREE.Mesh(
    new THREE.BoxGeometry(ZONE_SIZE.w + 0.3, ZONE_SIZE.h + 0.3, ZONE_SIZE.d + 0.3),
    new THREE.MeshBasicMaterial({ color: 0x38bdf8, wireframe: true, transparent: true, opacity: 0.9 }),
  );
  highlightMesh.visible = false;
  scene.add(highlightMesh);

  const SEVERITY_RANK = { online: 0, offline: 1, warning: 2, critical: 3 };

  function paint(s) {
    if (!s) return;
    criticalMeshes.clear();
    let worst = 'online';
    for (const floor of s.floors) {
      for (const zone of floor.zones) {
        const mesh = zoneMeshes.get(zone.zoneCode);
        if (!mesh) continue;
        const color = SEVERITY_COLORS[zone.severity] ?? SEVERITY_COLORS.offline;
        mesh.material.color.setHex(color);
        mesh.material.emissive.setHex(color);
        mesh.material.emissiveIntensity = zone.severity === 'critical' ? 0.35 : 0.12;
        mesh.userData.zone = zone;
        if (zone.severity === 'critical') criticalMeshes.add(mesh);
        if (SEVERITY_RANK[zone.severity] > SEVERITY_RANK[worst]) worst = zone.severity;
      }
    }
    const beaconColor = SEVERITY_COLORS[worst];
    beacon.material.color.setHex(beaconColor);
    beacon.material.emissive.setHex(beaconColor);
  }

  function highlight(zoneCode) {
    if (!zoneCode) {
      highlightMesh.visible = false;
      return;
    }
    const mesh = zoneMeshes.get(zoneCode);
    if (!mesh) {
      highlightMesh.visible = false;
      return;
    }
    mesh.getWorldPosition(highlightMesh.position);
    highlightMesh.visible = true;
  }

  // hover tooltip
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
    pointerDirty = false;
  });

  function updateTooltip() {
    if (!pointerDirty) return;
    pointerDirty = false;
    raycaster.setFromCamera(pointer, camera);
    const hit = raycaster.intersectObjects(zoneMeshList, false)[0];
    const zone = hit?.object.userData.zone;
    if (!zone) {
      tooltipEl.hidden = true;
      return;
    }
    tooltipEl.innerHTML = '';
    const title = document.createElement('div');
    title.className = 'tt-title';
    title.textContent = `${zone.zoneCode} · ${zone.severity.toUpperCase()}`;
    const body = document.createElement('div');
    body.textContent =
      `devices ${zone.online}/${zone.devicesTotal} online · ` +
      `recv ${zone.received} · ack ${zone.acknowledged} · failed ${zone.failed}`;
    tooltipEl.append(title, body);
    tooltipEl.style.left = `${Math.min(pointerPx.x + 14, window.innerWidth - 320)}px`;
    tooltipEl.style.top = `${Math.min(pointerPx.y + 14, window.innerHeight - 70)}px`;
    tooltipEl.hidden = false;
  }

  window.addEventListener('resize', () => {
    camera.aspect = window.innerWidth / window.innerHeight;
    camera.updateProjectionMatrix();
    renderer.setSize(window.innerWidth, window.innerHeight);
  });

  const clock = new THREE.Clock();
  renderer.setAnimationLoop(() => {
    const t = clock.getElapsedTime();
    controls.update();
    const pulse = 0.3 + 0.25 * (0.5 + 0.5 * Math.sin(t * 5));
    for (const mesh of criticalMeshes) mesh.material.emissiveIntensity = pulse;
    beacon.material.emissiveIntensity = 0.4 + 0.3 * (0.5 + 0.5 * Math.sin(t * 2));
    if (highlightMesh.visible) highlightMesh.material.opacity = 0.55 + 0.35 * (0.5 + 0.5 * Math.sin(t * 6));
    updateTooltip();
    renderer.render(scene, camera);
  });

  return { paint, highlight };
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
