// ---------------------------------------------------------------------------
// Aegis EBS — digital twin dashboard (Vite + Three.js)
// 10-floor building, 4 zones per floor (2x2). Each zone box is colored by
// severity: green=online/acked, yellow=warning, red=critical, gray=offline.
// Data: src/ws.js (live WebSocket → mock fallback), contract in src/data.js.
// ---------------------------------------------------------------------------

import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';
import './styles.css';
import {
  WS_URL,
  FLOOR_COUNT,
  ZONE_LETTERS,
  SEVERITY_COLORS,
  applyDelta,
} from './data.js';
import { createStatusFeed } from './ws.js';

// --- DOM --------------------------------------------------------------------

const $ = (id) => document.getElementById(id);
const canvas = $('scene');
const badgeEl = $('conn-badge');
const alertsEl = $('alerts');
const alertCountEl = $('alert-count');
const tooltipEl = $('tooltip');
const feedModeEl = $('feed-mode');
const lastUpdateEl = $('last-update');

// --- renderer / scene / camera ----------------------------------------------

let renderer;
try {
  renderer = new THREE.WebGLRenderer({ canvas, antialias: true });
} catch (err) {
  document.body.innerHTML =
    '<p style="padding:2rem;font-family:sans-serif">WebGL is not available in this browser: ' +
    err.message + '</p>';
  throw err;
}
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

// --- lights / ground ----------------------------------------------------------

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

// --- building: 10 stacked floors × 4 zone boxes -------------------------------

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

// --- state → scene / HUD -------------------------------------------------------

let state = null;
const SEVERITY_RANK = { online: 0, offline: 1, warning: 2, critical: 3 };

function paint() {
  if (!state) return;
  criticalMeshes.clear();
  let worst = 'online';

  for (const floor of state.floors) {
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

const fmt = (v) => (typeof v === 'number' ? v.toLocaleString('en-US') : '–');

function renderHud() {
  if (!state) return;
  const m = state.metrics ?? {};
  $('m-connected').textContent = fmt(m.connected);
  $('m-p50').textContent = fmt(m.p50);
  $('m-p95').textContent = fmt(m.p95);
  $('m-p99').textContent = fmt(m.p99);
  $('m-retry').textContent = fmt(m.retryCount);

  const alerts = state.activeAlerts ?? [];
  alertCountEl.textContent = String(alerts.length);
  alertsEl.replaceChildren(
    ...alerts.map((a) => {
      const li = document.createElement('li');
      li.className = `alert alert-${a.severity}`;
      const head = document.createElement('strong');
      head.textContent = a.zoneCode;
      li.append(head, document.createTextNode(` ${a.message}`));
      return li;
    }),
  );
  if (alerts.length === 0) {
    const li = document.createElement('li');
    li.className = 'alert alert-none';
    li.textContent = 'No active alerts';
    alertsEl.replaceChildren(li);
  }
  lastUpdateEl.textContent = `last update: ${new Date(state.ts).toLocaleTimeString()}`;
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
}

// --- hover tooltip ---------------------------------------------------------------

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

// --- data feed ---------------------------------------------------------------------

const wsUrl = new URLSearchParams(window.location.search).get('ws') || WS_URL;

const feed = createStatusFeed({
  url: wsUrl,
  onSnapshot: (snapshot) => {
    state = snapshot;
    paint();
    renderHud();
  },
  onDelta: (delta) => {
    if (!state) return; // server must send a snapshot first
    state = applyDelta(state, delta);
    paint();
    renderHud();
  },
  onMode: setMode,
});

// --- resize + render loop -------------------------------------------------------------

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
  updateTooltip();
  renderer.render(scene, camera);
});
