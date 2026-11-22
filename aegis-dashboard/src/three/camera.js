// ---------------------------------------------------------------------------
// camera.js — CAMERA + navigation for the AEGIS Tower B01 digital twin.
// Owner: camera module (see src/three/CONTRACT.md). Additive + self-contained:
// this file never edits another module's objects permanently — every visibility,
// material and transform change is recorded and restored on dispose()/mode-off.
//
// Exports
//   attach(camera, domElement) -> OrbitControls  (damping + angle/distance limits)
//   build(ctx) -> {
//     controls, camera, group,
//     flyToFloor(i),            // smooth lerp of controls.target + camera.position
//     setView(name),            // 'overview' | 'front' | 'top' | 'lobby' (+ back/side)
//     setCutaway(bool[,level]), // hide facade + interior of floors ABOVE level
//     setExplode(bool),         // separate floors along +Y (animated)
//     isolateFloor(i|null),     // keep floor i solid, ghost everything else
//     update(dt),               // also accepts update(status, dt) per CONTRACT
//     dispose(),
//     setCutLevel(i), getState(), refresh(), resize(w,h),          // extras for ui.js
//     setMode(id), toggleCutaway(), toggleExplode(), showAll(), resetView()/reset()
//   }
//   setView also accepts the ui.js HUD ids: 'cutaway'/'explode'/'isolate' toggle the
//   matching mode, 'night'/'day' are passed through to lighting.js via onViewChange,
//   'all' -> showAll(), 'reset' -> resetView().
//
// View modes
//   overview : 3/4 aerial of the whole tower (default)
//   front    : elevation from +Z (entrance side)
//   top      : near-plan view from above (small tilt avoids gimbal lock)
//   lobby    : eye level inside the ground floor, looking at the core
//   back/side: bonus elevations (-Z / +X)
//   floor    : entered by flyToFloor(i) — keeps the current azimuth, frames floor i
//
// All dimensions come from config.js (BUILDING / floorY / pitch / totalHeight);
// nothing is hardcoded. Scene objects are discovered by traversing ctx.root and
// reading userData {floor, zone, zoneCode} / names / materials, so the module
// works regardless of build order (it re-scans when the scene graph changes).
// ---------------------------------------------------------------------------

import * as THREE from 'three';
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js';
import {
  BUILDING,
  floorY as cfgFloorY,
  pitch as cfgPitch,
  totalHeight as cfgTotalHeight,
} from './config.js';

export const VIEW_NAMES = ['overview', 'front', 'top', 'lobby', 'back', 'side'];

const VIEW_ALIASES = {
  default: 'overview', iso: 'overview', corner: 'overview', aerial: 'overview',
  entrance: 'lobby', ground: 'lobby', lobby: 'lobby',
  plan: 'top', above: 'top', bird: 'top', birds: 'top',
  elevation: 'front', south: 'front', north: 'back', east: 'side', west: 'side',
};

const EYE_HEIGHT = 1.7;          // lobby eye level (m)
const FLY_DURATION = 1.15;       // seconds for preset / fly-to-floor moves
const EXPLODE_SPEED = 6;         // exponential approach rate (1/s)
const EXPLODE_GAP_RATIO = 0.9;   // extra gap per floor = ratio * pitch()
const RESCAN_INTERVAL = 0.4;     // seconds between scene-graph change checks

// --- framing coefficients (all distances are derived from config metrics) ---
const FRAME_MARGIN = 1.15;       // padding on the exact 8-corner frustum fit
const OVERVIEW_SPAN = 1.65;      // overview  >= span * 1.65
const ELEVATION_SPAN = 1.9;      // front/back/side >= span * 1.9
const TOP_SPAN = 1.85;           // top (plan) >= span * 1.85
const FLOOR_SPAN = 1.3;          // floor view >= span * 1.3
const FLOOR_ELEVATION = 0.62;    // ~35.5 deg above the plate: zone rectangles stay legible
const GHOST_OPACITY = 0.07;      // non-isolated building objects
const GHOST_ENV_OPACITY = 0.18;  // ground / context while isolating

// --- classification ---------------------------------------------------------

const KIND_PATTERNS = [
  ['safety', /safety|exit|extinguish|sprinkler|alarm|strobe|evac|fire|hydrant|aed/i],
  ['status', /status|viz|marker|beacon|heatmap|heat-map|particle|ack|device|sensor|pulse/i],
  ['environment', /environment|ground|plaza|road|parking|tree|sky|context|terrain|grass|asphalt|water|sidewalk|street|landscape/i],
  ['facade', /facade|curtain|glaz|glass|mullion|window|canopy|parapet|signage|cladding|exterior|entrance/i],
  ['interior', /interior|partition|room|furniture|desk|chair|table|corridor|zone|cubicle|sofa|carpet|ceiling|workstation/i],
  ['structure', /structure|slab|column|beam|core|elevator|podium|roof|stair|floor|level|frame/i],
];

function matchKind(text) {
  if (!text) return null;
  for (const [kind, re] of KIND_PATTERNS) if (re.test(text)) return kind;
  return null;
}

// F03 (zoneCode convention) is 1-based; floor-3 / level-3 are 0-based loop indices
// unless the number equals BUILDING.floors (then it must be 1-based).
function floorFromName(name, floors) {
  if (!name) return null;
  let mm = /(?:^|[^A-Za-z0-9])[Ff](\d{1,2})(?![0-9])/.exec(name);
  if (mm) {
    const n = parseInt(mm[1], 10);
    if (n >= 1 && n <= floors) return n - 1;
  }
  mm = /(?:floor|level|storey|lvl)[-_ ]?(\d{1,2})/i.exec(name);
  if (mm) {
    const n = parseInt(mm[1], 10);
    if (n === floors) return n - 1;
    if (n >= 0 && n < floors) return n;
  }
  return null;
}

// --- metrics ----------------------------------------------------------------

function makeMetrics(config) {
  const c = config && typeof config === 'object' ? config : {};
  const B = c.BUILDING && typeof c.BUILDING === 'object' ? c.BUILDING : BUILDING;
  const num = (v, fallback) => (Number.isFinite(v) ? v : fallback);
  const pitchFn = typeof c.pitch === 'function' ? c.pitch : cfgPitch;
  const floorYFn = typeof c.floorY === 'function' ? c.floorY : cfgFloorY;
  const totalFn = typeof c.totalHeight === 'function' ? c.totalHeight : cfgTotalHeight;
  const floors = num(B.floors, BUILDING.floors);
  const floorHeight = num(B.floorHeight, BUILDING.floorHeight);
  const slabThickness = num(B.slabThickness, BUILDING.slabThickness);
  const width = num(B.width, BUILDING.width);
  const depth = num(B.depth, BUILDING.depth);
  const coreDepth = num(B.coreDepth, BUILDING.coreDepth);
  const pitch = num(pitchFn(), floorHeight + slabThickness);
  const height = num(totalFn(), floors * pitch);
  const span = Math.max(width, depth);
  return { B, floors, floorHeight, slabThickness, width, depth, span, coreDepth, pitch, height, floorY: floorYFn, totalHeight: totalFn };
}

// --- controls ---------------------------------------------------------------

function tuneControls(controls, m) {
  controls.enableDamping = true;
  controls.dampingFactor = 0.06;
  controls.rotateSpeed = 0.85;
  controls.zoomSpeed = 0.9;
  controls.panSpeed = 0.8;
  controls.screenSpacePanning = true;
  controls.minDistance = Math.max(2, m.floorHeight * 0.8);
  // Large footprint (span = max(W,D)) needs plenty of zoom-out headroom.
  controls.maxDistance = Math.max(m.height * 4, m.span * 6, 400);
  controls.minPolarAngle = 0.08;                 // never exactly straight down
  controls.maxPolarAngle = Math.PI / 2 - 0.03;   // never below the plaza
  controls.autoRotate = false;
  controls.autoRotateSpeed = 0.6;
  controls.zoomToCursor = false;
  if (controls.cursor) controls.cursor.set(0, m.height * 0.4, 0);
  if ('maxTargetRadius' in controls) controls.maxTargetRadius = m.height + Math.max(m.width, m.depth);
  // Deliberately no listenToKeyEvents(): panels.js owns hotkeys 1..5.
  return controls;
}

/**
 * Attach tuned OrbitControls to an existing camera.
 * domElement may be null (headless / tests) — controls stay usable via update().
 */
export function attach(camera, domElement = null) {
  const controls = new OrbitControls(camera, domElement || null);
  return tuneControls(controls, makeMetrics(null));
}

// --- module -----------------------------------------------------------------

export function build(ctx = {}) {
  const m = makeMetrics(ctx.config);
  const mats = ctx.materials || null;
  const root = ctx.root || null;
  const clampFloor = (i) => Math.max(0, Math.min(m.floors - 1, Math.round(i)));
  const floorFromY = (y) => clampFloor(Math.floor((y + m.slabThickness * 0.5 + 1e-3) / m.pitch));
  const explodeGap = () => m.pitch * EXPLODE_GAP_RATIO;

  // camera + rig group (returned so the assembler can root.add(group) safely)
  const group = new THREE.Group();
  group.name = 'camera-rig';
  const domElement = ctx.domElement || (ctx.renderer && ctx.renderer.domElement) || null;
  let camera = ctx.camera || null;
  const ownsCamera = !camera;
  if (!camera) {
    const aspect = Number.isFinite(ctx.aspect) ? ctx.aspect
      : (domElement && domElement.clientWidth ? domElement.clientWidth / Math.max(1, domElement.clientHeight) : 16 / 9);
    camera = new THREE.PerspectiveCamera(45, aspect, 0.1, Math.max(300, m.height * 8));
    camera.name = 'twin-camera';
    group.add(camera);
  }
  const controls = ctx.controls || attach(camera, domElement);
  tuneControls(controls, m);
  if (root && !group.parent) root.add(group);

  const state = {
    view: 'overview', focusFloor: null,
    cutaway: false, cutLevel: 0,
    explode: false, explodeAmount: 0,
    isolated: null,
  };
  let explodeTarget = 0;
  let disposed = false;
  let scanTimer = 0;
  let objectCount = -1;

  const flight = {
    active: false, t: 0, dur: FLY_DURATION,
    fromPos: new THREE.Vector3(), toPos: new THREE.Vector3(),
    fromTarget: new THREE.Vector3(), toTarget: new THREE.Vector3(),
  };

  // --- poses ---------------------------------------------------------------

  // Exact frustum fit: smallest distance along `dir` from `target` that keeps
  // every corner of a config-sized box inside the camera frustum. Fitting the
  // 8 corners (instead of a bounding sphere) guarantees no clipped corners at
  // any azimuth, including the wide 72x50 footprint.
  const _fitP = new THREE.Vector3();
  const _fitDir = new THREE.Vector3();
  const _fitRight = new THREE.Vector3();
  const _fitUp = new THREE.Vector3();
  const WORLD_UP = new THREE.Vector3(0, 1, 0);

  function frustumFit(dir, target, box, margin = FRAME_MARGIN) {
    const fov = camera.isPerspectiveCamera && Number.isFinite(camera.fov) && camera.fov > 0 ? camera.fov : 45;
    const aspect = camera.isPerspectiveCamera && Number.isFinite(camera.aspect) && camera.aspect > 0 ? camera.aspect : 16 / 9;
    const tanV = Math.tan(fov * Math.PI / 360);
    const tanH = tanV * aspect;
    _fitDir.copy(dir);
    if (_fitDir.lengthSq() < 1e-8) _fitDir.set(0, 0, 1);
    _fitDir.normalize();
    _fitRight.crossVectors(WORLD_UP, _fitDir);
    if (_fitRight.lengthSq() < 1e-8) _fitRight.set(1, 0, 0);   // near-vertical plan view
    _fitRight.normalize();
    _fitUp.crossVectors(_fitDir, _fitRight).normalize();
    let need = 0;
    for (const x of [-box.w * 0.5, box.w * 0.5]) {
      for (const y of [box.minY, box.maxY]) {
        for (const z of [-box.d * 0.5, box.d * 0.5]) {
          _fitP.set(x - target.x, y - target.y, z - target.z);
          const along = _fitP.dot(_fitDir);
          const h = Math.abs(_fitP.dot(_fitRight)) / tanH;
          const v = Math.abs(_fitP.dot(_fitUp)) / tanV;
          need = Math.max(need, along + h, along + v);
        }
      }
    }
    return Math.max(need * margin, m.pitch);
  }

  // Explode lifts the upper floors, so frame the expanded envelope when active.
  function buildingBox() {
    const extra = state.explode ? explodeGap() * (m.floors - 1) * state.explodeAmount : 0;
    return { w: m.width, d: m.depth, minY: 0, maxY: m.height + extra };
  }

  function presetPose(name) {
    const W = m.width, D = m.depth, span = m.span;
    const box = buildingBox();
    const H = box.maxY;
    const target = new THREE.Vector3();
    const position = new THREE.Vector3();
    const dir = new THREE.Vector3();
    let dist = 0;
    switch (name) {
      case 'front':
      case 'back':
      case 'side': {
        target.set(0, H * 0.45, 0);                 // centered on the full envelope
        if (name === 'front') dir.set(0, 0.06, 1);  // slight lift keeps the plaza in frame
        else if (name === 'back') dir.set(0, 0.06, -1);
        else dir.set(1, 0.06, 0);
        dist = Math.max(span * ELEVATION_SPAN, frustumFit(dir, target, box));
        break;
      }
      case 'top': {
        target.set(0, H * 0.45, 0);
        const tilt = 0.12, az = 0.3;               // near-plan; tilt clears minPolarAngle
        dir.set(Math.sin(az) * Math.sin(tilt), Math.cos(tilt), Math.cos(az) * Math.sin(tilt));
        dist = Math.max(span * TOP_SPAN, frustumFit(dir, target, box));
        break;
      }
      case 'lobby':
        target.set(0, m.floorY(0) + EYE_HEIGHT * 0.8, -m.coreDepth * 0.5);
        position.set(0, m.floorY(0) + EYE_HEIGHT + 0.5, D * 0.5 - 2);
        return { position, target };
      case 'overview':
      default: {
        target.set(0, H * 0.45, 0);
        dir.set(W * 0.95, H * 0.43, D * 1.55);      // same 3/4 bearing, pure config units
        dist = Math.max(span * OVERVIEW_SPAN, frustumFit(dir, target, box));
        break;
      }
    }
    position.copy(target).addScaledVector(dir.normalize(), dist);
    return { position, target };
  }

  function currentAzimuth() {
    const dx = camera.position.x - controls.target.x;
    const dz = camera.position.z - controls.target.z;
    return Math.atan2(dx, dz);
  }

  function floorPose(i) {
    const centerY = m.floorY(i) + m.floorHeight * 0.5;
    const target = new THREE.Vector3(0, centerY, 0);  // floor center => all 4 zones balanced
    const az = currentAzimuth();                      // keep the user's current side
    const el = FLOOR_ELEVATION;
    const dir = new THREE.Vector3(Math.sin(az) * Math.cos(el), Math.sin(el), Math.cos(az) * Math.cos(el));
    const plate = { w: m.width, d: m.depth, minY: centerY - m.pitch * 0.5, maxY: centerY + m.pitch * 0.5 };
    const dist = Math.max(m.span * FLOOR_SPAN, frustumFit(dir, target, plate));
    return { position: target.clone().addScaledVector(dir.normalize(), dist), target };
  }

  const easeInOutCubic = (k) => (k < 0.5 ? 4 * k * k * k : 1 - Math.pow(-2 * k + 2, 3) / 2);

  function startFlight(position, target, duration = FLY_DURATION, instant = false) {
    if (instant || !(duration > 0)) {
      camera.position.copy(position);
      controls.target.copy(target);
      flight.active = false;
      controls.enableDamping = true;
      controls.update();
      return;
    }
    flight.fromPos.copy(camera.position);
    flight.fromTarget.copy(controls.target);
    flight.toPos.copy(position);
    flight.toTarget.copy(target);
    flight.t = 0;
    flight.dur = duration;
    flight.active = true;
    // Damping off while scripted: residual spherical/pan deltas would fight the lerp.
    controls.enableDamping = false;
    controls.autoRotate = false;
  }

  function cancelFlight() {
    if (!flight.active) return;
    flight.active = false;
    controls.enableDamping = true;
  }

  // --- scene records -------------------------------------------------------

  const records = new Map();   // uuid -> record with the object's untouched base state
  const ghostCache = new Map();
  let order = [];
  let movers = [];
  let instanceMovers = [];

  function recordFor(obj) {
    let r = records.get(obj.uuid);
    if (!r) {
      r = {
        obj,
        baseVisible: obj.visible,
        basePos: obj.position.clone(),
        baseMaterials: obj.material !== undefined ? obj.material : null,
        baseInstance: null, instanceFloors: null,
        kind: 'other', floor: -1, multi: false, mover: false, instMover: false,
        ghosted: false,
      };
      records.set(obj.uuid, r);
    }
    return r;
  }

  function kindFromMaterial(material) {
    if (!mats || !material) return null;
    const list = Array.isArray(material) ? material : [material];
    for (const mm of list) {
      if (!mm) continue;
      if (mm === mats.glass || mm === mats.glassDark || mm === mats.mullion) return 'facade';
      if (mm === mats.exitSign || mm === mats.fireRed) return 'safety';
      if (mm === mats.grass || mm === mats.asphalt || mm === mats.water) return 'environment';
      if (mm === mats.floor || mm === mats.wall || mm === mats.wood) return 'interior';
      if (mm === mats.slab || mm === mats.concrete || mm === mats.concreteDark || mm === mats.steel) return 'structure';
    }
    return null;
  }

  function readTag(obj) {
    const ud = obj.userData || {};
    let kind = null, floor = null;
    for (const k of ['module', 'kind', 'part', 'category', 'type', 'layer']) {
      if (typeof ud[k] === 'string') { kind = matchKind(ud[k]); if (kind) break; }
    }
    for (const k of ['floor', 'floorIndex', 'level', 'storey']) {
      const v = ud[k];
      if (Number.isFinite(v)) { floor = clampFloor(v); break; }
      if (typeof v === 'string' && /^F?\d{1,2}$/i.test(v)) { floor = clampFloor(parseInt(v.replace(/^F/i, ''), 10) - (/^F/i.test(v) ? 1 : 0)); break; }
    }
    const name = obj.name || '';
    if (!kind) kind = matchKind(name);
    // zoneCode ('F03-A') is authoritative: statusViz tags `floor` 1-based while
    // structure/facade/interior tag it 0-based — the code string resolves both.
    if (floor == null && typeof ud.zoneCode === 'string') floor = floorFromName(ud.zoneCode, m.floors);
    else if (typeof ud.zoneCode === 'string') {
      const zc = floorFromName(ud.zoneCode, m.floors);
      if (zc != null) floor = zc;
    }
    if (floor == null) floor = floorFromName(name, m.floors);
    return { kind, floor };
  }

  const _bb = new THREE.Box3();
  const _v = new THREE.Vector3();
  const _mat = new THREE.Matrix4();

  // World-space Y extent of a renderable object (null for groups/lights).
  function worldYRange(obj) {
    if (obj.isInstancedMesh && obj.instanceMatrix) {
      let min = Infinity, max = -Infinity;
      const n = obj.count;
      for (let k = 0; k < n; k++) {
        _mat.fromArray(obj.instanceMatrix.array, k * 16);
        _v.setFromMatrixPosition(_mat).applyMatrix4(obj.matrixWorld);
        if (_v.y < min) min = _v.y;
        if (_v.y > max) max = _v.y;
      }
      if (!Number.isFinite(min)) return null;
      const g = obj.geometry && obj.geometry.boundingBox;
      const pad = g ? Math.max(Math.abs(g.min.y), Math.abs(g.max.y)) : 0;
      return { min: min - pad, max: max + pad };
    }
    if (obj.geometry) {
      if (!obj.geometry.boundingBox) obj.geometry.computeBoundingBox();
      _bb.copy(obj.geometry.boundingBox).applyMatrix4(obj.matrixWorld);
      if (!Number.isFinite(_bb.min.y)) return null;
      return { min: _bb.min.y, max: _bb.max.y };
    }
    return null;
  }

  function ensureInstanceData(r) {
    const obj = r.obj;
    if (!obj.isInstancedMesh || !obj.instanceMatrix) return;
    if (!r.baseInstance) r.baseInstance = Float32Array.from(obj.instanceMatrix.array);
    if (!r.instanceFloors || r.instanceFloors.length !== obj.count) {
      const floors = new Int16Array(obj.count);
      for (let k = 0; k < obj.count; k++) {
        _mat.fromArray(r.baseInstance, k * 16);
        _v.setFromMatrixPosition(_mat).applyMatrix4(obj.matrixWorld);
        floors[k] = floorFromY(_v.y);
      }
      r.instanceFloors = floors;
    }
  }

  function restoreTransforms() {
    for (const r of movers) r.obj.position.copy(r.basePos);
    for (const r of instanceMovers) {
      if (r.baseInstance && r.obj.instanceMatrix) {
        r.obj.instanceMatrix.array.set(r.baseInstance);
        r.obj.instanceMatrix.needsUpdate = true;
      }
    }
  }

  function scan() {
    if (!root) return;
    restoreTransforms();                 // classify from base geometry, not exploded Y
    root.updateMatrixWorld(true);
    const seen = new Set();
    order = []; movers = []; instanceMovers = [];

    const walk = (obj, inhFloor, inhKind, parentMover) => {
      if (obj === camera || obj === group || obj.isLight) return;
      seen.add(obj.uuid);
      const tag = readTag(obj);
      const r = recordFor(obj);
      const kind = tag.kind || inhKind || kindFromMaterial(r.baseMaterials) || 'other';
      const explicitFloor = tag.floor != null ? tag.floor : inhFloor;
      const renderable = obj.isMesh || obj.isInstancedMesh || obj.isPoints || obj.isLine || obj.isSprite;

      let floor = explicitFloor;
      let multi = false;
      if (floor == null && renderable) {
        const yr = worldYRange(obj);
        if (yr) {
          multi = (yr.max - yr.min) > m.pitch * 1.25;
          floor = multi ? -1 : floorFromY((yr.min + yr.max) / 2);
        }
      }
      if (floor == null) floor = -1;

      let mover = false, instMover = false;
      if (!parentMover && obj !== root && kind !== 'environment') {
        if (obj.isInstancedMesh && multi && explicitFloor == null) instMover = true;
        else if (floor >= 0 && !multi) mover = true;
      }

      r.kind = kind; r.floor = floor; r.multi = multi;
      r.mover = mover; r.instMover = instMover;
      order.push(r);
      if (mover) movers.push(r);
      if (instMover) { instanceMovers.push(r); ensureInstanceData(r); }

      const childMover = parentMover || mover || instMover;
      for (const child of obj.children) walk(child, explicitFloor, kind, childMover);
    };

    for (const child of root.children) walk(child, null, null, false);

    // drop records for objects that left the scene
    for (const [uuid, r] of records) {
      if (!seen.has(uuid)) {
        if (r.ghosted && r.obj) r.obj.material = r.baseMaterials;
        records.delete(uuid);
      }
    }
  }

  function countObjects() {
    if (!root) return 0;
    let n = 0;
    root.traverse(() => { n++; });
    return n;
  }

  // --- mode application ----------------------------------------------------

  function applyVisibility() {
    for (const r of order) {
      const upper = r.floor > state.cutLevel && r.floor >= 0;
      const hide = state.cutaway && upper && (r.kind === 'facade' || r.kind === 'interior') && !r.instMover;
      const want = r.baseVisible && !hide;
      if (r.obj.visible !== want) r.obj.visible = want;
    }
  }

  const _zero = new THREE.Vector3(0, 0, 0);
  function applyInstances() {
    for (const r of instanceMovers) {
      const obj = r.obj;
      if (!obj.instanceMatrix) continue;
      ensureInstanceData(r);
      const arr = obj.instanceMatrix.array;
      const base = r.baseInstance;
      const cut = state.cutaway && (r.kind === 'facade' || r.kind === 'interior');
      const iso = state.isolated != null;
      const gap = explodeGap() * state.explodeAmount;
      if (!cut && !iso && gap === 0) {
        if (arr.length === base.length) { arr.set(base); obj.instanceMatrix.needsUpdate = true; }
        continue;
      }
      for (let k = 0; k < obj.count; k++) {
        _mat.fromArray(base, k * 16);
        const f = r.instanceFloors[k];
        if (gap !== 0 && f >= 0) _mat.elements[13] += gap * f;   // upright meshes: local Y == world Y
        const hide = (cut && f > state.cutLevel) || (iso && f !== state.isolated);
        if (hide) _mat.scale(_zero);
        _mat.toArray(arr, k * 16);
      }
      obj.instanceMatrix.needsUpdate = true;
    }
  }

  function applyExplode() {
    const gap = explodeGap() * state.explodeAmount;
    for (const r of movers) r.obj.position.y = r.basePos.y + gap * r.floor;
  }

  function ghostFor(material, opacity) {
    const key = `${material.uuid}:${opacity}`;
    let g = ghostCache.get(key);
    if (!g) {
      g = material.clone();
      g.transparent = true;
      g.opacity = opacity;
      g.depthWrite = false;
      if (g.transmission != null) g.transmission = Math.min(g.transmission, 0.1);
      g.needsUpdate = true;
      ghostCache.set(key, g);
    }
    return g;
  }

  function applyIsolation() {
    for (const r of order) {
      const obj = r.obj;
      if (!r.baseMaterials || r.instMover) continue;   // instanced: masked per instance instead
      const ghost = state.isolated != null && r.floor !== state.isolated;
      if (ghost) {
        if (!r.ghosted) {
          const op = r.kind === 'environment' ? GHOST_ENV_OPACITY : GHOST_OPACITY;
          obj.material = Array.isArray(r.baseMaterials)
            ? r.baseMaterials.map((mm) => (mm ? ghostFor(mm, op) : mm))
            : ghostFor(r.baseMaterials, op);
          r.ghosted = true;
        }
      } else if (r.ghosted) {
        obj.material = r.baseMaterials;
        r.ghosted = false;
      }
    }
  }

  function applyAll() {
    applyVisibility();
    applyIsolation();
    applyExplode();
    applyInstances();
  }

  function refresh() {
    if (disposed || !root) return;
    scan();
    objectCount = countObjects();
    applyAll();
  }

  // --- public API ----------------------------------------------------------

  function setView(name, instant = false) {
    const key = String(name == null ? 'overview' : name).toLowerCase();
    // ui.js routes its HUD "view toggles" through setView()/setMode() as well.
    if (key === 'cutaway') { setCutaway(!state.cutaway); return 'cutaway'; }
    if (key === 'explode') { setExplode(!state.explode); return 'explode'; }
    if (key === 'isolate' || key === 'isolation') {
      isolateFloor(state.isolated == null ? (state.focusFloor == null ? 0 : state.focusFloor) : null);
      return 'isolate';
    }
    if (key === 'night' || key === 'day') { state.view = key; return key; }  // lighting.js owns night mode
    if (key === 'all') { showAll(); return 'overview'; }
    if (key === 'reset') { resetView(); return 'overview'; }
    const canonical = VIEW_NAMES.includes(key) ? key : (VIEW_ALIASES[key] || 'overview');
    const pose = presetPose(canonical);
    state.view = canonical;
    if (canonical === 'lobby') state.focusFloor = 0;
    else if (canonical === 'overview' || canonical === 'top') state.focusFloor = null;
    startFlight(pose.position, pose.target, instant ? 0 : FLY_DURATION, instant);
    return canonical;
  }

  // HUD aliases (ui.js bindCamera probes these names before flyToFloor/isolateFloor)
  const setMode = (id) => setView(id);
  const toggleCutaway = () => setCutaway(!state.cutaway);
  const toggleExplode = () => setExplode(!state.explode);
  function showAll() {
    isolateFloor(null);
    state.focusFloor = null;
    setView('overview');
    return null;
  }
  function resetView() {
    isolateFloor(null);
    setCutaway(false);
    setExplode(false);
    state.focusFloor = null;
    setView('overview');
    return 'overview';
  }

  function flyToFloor(i, opts = {}) {
    if (i == null || !Number.isFinite(Number(i))) return false;
    const f = clampFloor(Number(i));
    state.focusFloor = f;
    state.view = 'floor';
    if (state.cutaway) { state.cutLevel = f; applyVisibility(); applyInstances(); }
    const pose = floorPose(f);
    const instant = opts.instant === true || opts.duration === 0;
    startFlight(pose.position, pose.target, Number.isFinite(opts.duration) ? opts.duration : FLY_DURATION, instant);
    return true;
  }

  function setCutaway(on, level) {
    state.cutaway = !!on;
    if (Number.isFinite(level)) state.cutLevel = clampFloor(level);
    else if (state.cutaway && state.focusFloor != null) state.cutLevel = state.focusFloor;
    applyVisibility();
    applyInstances();
    return state.cutaway;
  }

  function setCutLevel(i) {
    if (!Number.isFinite(i)) return state.cutLevel;
    state.cutLevel = clampFloor(i);
    if (state.cutaway) { applyVisibility(); applyInstances(); }
    return state.cutLevel;
  }

  function setExplode(on, instant = false) {
    state.explode = !!on;
    explodeTarget = state.explode ? 1 : 0;
    if (instant || (!state.explode && state.explodeAmount === 0)) {
      state.explodeAmount = explodeTarget;
      applyExplode();
      applyInstances();
    }
    return state.explode;
  }

  function isolateFloor(i) {
    state.isolated = (i == null || !Number.isFinite(Number(i)) || Number(i) < 0) ? null : clampFloor(Number(i));
    if (state.isolated != null) state.focusFloor = state.isolated;
    applyIsolation();
    applyInstances();
    return state.isolated;
  }

  function resize(width, height) {
    if (!camera.isPerspectiveCamera || !(height > 0)) return;
    camera.aspect = width / height;
    camera.updateProjectionMatrix();
  }

  function getState() {
    return {
      view: state.view,
      focusFloor: state.focusFloor,
      cutaway: state.cutaway,
      cutLevel: state.cutLevel,
      explode: state.explode,
      explodeAmount: state.explodeAmount,
      isolatedFloor: state.isolated,
      flying: flight.active,
      trackedObjects: order.length,
    };
  }

  function update(statusOrDt, maybeDt) {
    if (disposed) return;
    let dt = typeof statusOrDt === 'number' ? statusOrDt : (typeof maybeDt === 'number' ? maybeDt : 1 / 60);
    if (!Number.isFinite(dt) || dt < 0) dt = 0;
    dt = Math.min(dt, 0.1);

    if (flight.active) {
      flight.t += dt;
      const k = easeInOutCubic(Math.min(1, flight.t / flight.dur));
      camera.position.lerpVectors(flight.fromPos, flight.toPos, k);
      controls.target.lerpVectors(flight.fromTarget, flight.toTarget, k);
      if (flight.t >= flight.dur) { flight.active = false; controls.enableDamping = true; }
    }

    if (state.explodeAmount !== explodeTarget) {
      const next = state.explodeAmount + (explodeTarget - state.explodeAmount) * (1 - Math.exp(-EXPLODE_SPEED * dt));
      state.explodeAmount = Math.abs(explodeTarget - next) < 0.002 ? explodeTarget : next;
      applyExplode();
      applyInstances();
    }

    scanTimer += dt;
    if (scanTimer >= RESCAN_INTERVAL) {
      scanTimer = 0;
      const n = countObjects();
      if (n !== objectCount) refresh();
    }

    controls.update(dt);
  }

  function restoreAll() {
    for (const r of records.values()) {
      const obj = r.obj;
      if (!obj) continue;
      if (obj.visible !== r.baseVisible) obj.visible = r.baseVisible;
      if (r.ghosted) { obj.material = r.baseMaterials; r.ghosted = false; }
      if (r.mover) obj.position.copy(r.basePos);
      if (r.instMover && r.baseInstance && obj.instanceMatrix) {
        obj.instanceMatrix.array.set(r.baseInstance);
        obj.instanceMatrix.needsUpdate = true;
      }
    }
  }

  function dispose() {
    if (disposed) return;
    disposed = true;
    controls.removeEventListener('start', cancelFlight);
    restoreAll();
    for (const g of ghostCache.values()) g.dispose();
    ghostCache.clear();
    records.clear();
    order = []; movers = []; instanceMovers = [];
    if (controls.domElement) controls.dispose();
    if (ownsCamera) group.remove(camera);
    if (group.parent) group.parent.remove(group);
  }

  // user grabbing the controls cancels a scripted flight
  controls.addEventListener('start', cancelFlight);

  // initial pose + first scene discovery (modules may build after us; update() re-scans)
  const initial = presetPose('overview');
  camera.position.copy(initial.position);
  controls.target.copy(initial.target);
  controls.update();
  refresh();

  return {
    controls, camera, group,
    flyToFloor, setView, setCutaway, setExplode, isolateFloor, update, dispose,
    setCutLevel, getState, refresh, resize,
    // HUD / assembler conveniences (ui.js bindCamera probes these names)
    setMode, toggleCutaway, toggleExplode, showAll, resetView, reset: resetView,
  };
}

export default { build, attach, VIEW_NAMES };
