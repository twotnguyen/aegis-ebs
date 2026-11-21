// ---------------------------------------------------------------------------
// Aegis EBS — statusViz.js · real-time status visualization on the twin.
//
// Contract: src/three/CONTRACT.md · data: status/1 (src/data.js).
// build(ctx) → { group, setStatus(status), update(status, dt), dispose, ... }
//   ctx = { THREE, config, materials, root, helpers }
//   status = derived STATUS_SNAPSHOT model (applyDelta output); partial
//            STATUS_DELTA-shaped payloads are merged onto the last model.
//
// Effects (all driven by zone counters/severity, no per-frame allocations):
//   1. Zone tint      — zone meshes found via userData.zoneCode (interior/
//                       structure); material.color + emissive set to
//                       STATUS_COLORS[severity]. Materials are cloned ONCE
//                       at bind time (zone materials may be shared), then
//                       only mutated — never recreated per update.
//   2. Critical pulse — emissiveIntensity heartbeat on critical zones.
//   3. Alert beacon   — glowing octahedron + ring + pointer floating above
//                       warning/critical zones (color from activeAlerts).
//   4. Device markers — one InstancedMesh of dots per zone; green = online,
//                       slate = offline; count = devicesTotal.
//   5. ACK particles  — pooled THREE.Points; when acknowledged increases,
//                       green sparks fly an arced path from the zone into
//                       the central core, fading out (additive blending).
//   6. Heatmap        — per-zone plane overlay, red→yellow→green ramp of
//                       acknowledged/received; opacity grows with unacked %.
//
// Robustness: overlays live in per-zone anchor groups that copy the zone
// mesh's world transform every frame (follows explode/cutaway/floor moves,
// works for box volumes AND rotated floor planes). If no tagged zone mesh
// exists yet (module build order / missing interior), a translucent proxy
// volume from config.zoneRect/floorY is used and swapped out automatically
// when the real mesh appears.
//
// Extras for ui.js: rescan(), setHeatmapVisible(v), setMarkersVisible(v),
// setBeaconsVisible(v), getStats().
// ---------------------------------------------------------------------------
import * as THREE from 'three';
import { BUILDING, STATUS_COLORS, ZONE_CODES, floorY, zoneRect } from './config.js';
import { makeMaterials } from './materials.js';
import { deriveSeverity } from '../data.js';

const CODE_RE = /^F(\d{1,2})-([A-D])$/;
const SEVERITIES = ['online', 'warning', 'critical', 'offline'];
const MAX_PARTICLES = 512;
const PARK_Y = -1e4; // inactive particles parked far below the scene
const SEV_EMISSIVE = { online: 0.16, warning: 0.32, critical: 0.42, offline: 0.05 };

const clamp = (v, lo, hi) => Math.min(hi, Math.max(lo, v));
const hashPhase = (s) => {
  let h = 0;
  for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) >>> 0;
  return ((h % 1000) / 1000) * Math.PI * 2;
};

export function build(ctx) {
  if (!ctx || !ctx.root) throw new Error('statusViz: ctx.root (THREE.Group) is required');
  const root = ctx.root;
  const M = ctx.materials || makeMaterials();
  const ownsLibrary = !ctx.materials;

  const group = new THREE.Group();
  group.name = 'statusViz';
  root.add(group);
  const proxyGroup = new THREE.Group();
  proxyGroup.name = 'statusViz:proxies';
  group.add(proxyGroup);

  // --- owned shared resources (disposed once in dispose()) ------------------
  const ownedGeometries = [];
  const ownedMaterials = [];
  const ownGeo = (g) => (ownedGeometries.push(g), g);
  const ownMat = (m) => (ownedMaterials.push(m), m);

  const heatmapGeo = ownGeo(new THREE.PlaneGeometry(1, 1));
  const markerGeo = ownGeo(new THREE.IcosahedronGeometry(0.13, 0)); // 20 tris/dot
  const markerMat = ownMat(new THREE.MeshBasicMaterial({ color: 0xffffff, toneMapped: false }));
  const beaconCoreGeo = ownGeo(new THREE.OctahedronGeometry(0.3, 0));
  const beaconRingGeo = ownGeo(new THREE.RingGeometry(0.44, 0.56, 24));
  const beaconConeGeo = ownGeo(new THREE.ConeGeometry(0.12, 0.34, 8));
  let proxyGeo = null; // lazy, shared by all fallback proxies

  // --- ACK particle pool (single Points draw call) ---------------------------
  const pPositions = new Float32Array(MAX_PARTICLES * 3);
  const pColors = new Float32Array(MAX_PARTICLES * 3);
  for (let i = 0; i < MAX_PARTICLES; i++) pPositions[i * 3 + 1] = PARK_Y;
  const pGeo = ownGeo(new THREE.BufferGeometry());
  const posAttr = new THREE.BufferAttribute(pPositions, 3).setUsage(THREE.DynamicDrawUsage);
  const colAttr = new THREE.BufferAttribute(pColors, 3).setUsage(THREE.DynamicDrawUsage);
  pGeo.setAttribute('position', posAttr);
  pGeo.setAttribute('color', colAttr);
  const pMat = ownMat(new THREE.PointsMaterial({
    size: 0.26, vertexColors: true, transparent: true, opacity: 0.95,
    blending: THREE.AdditiveBlending, depthWrite: false, sizeAttenuation: true, toneMapped: false,
  }));
  const points = new THREE.Points(pGeo, pMat);
  points.name = 'statusViz:ackParticles';
  points.frustumCulled = false;
  points.renderOrder = 5;
  group.add(points);
  const particles = Array.from({ length: MAX_PARTICLES }, () => ({
    active: false, age: 0, delay: 0, life: 1,
    sx: 0, sy: 0, sz: 0, ex: 0, ey: 0, ez: 0, arc: 1, r: 0, g: 0, b: 0,
  }));
  let pCursor = 0;
  let activeParticles = 0;

  // --- state -----------------------------------------------------------------
  const zones = new Map();      // zoneCode -> entry
  const criticalCodes = new Set();
  const alertByZone = new Map(); // zoneCode -> activeAlert
  let time = 0;
  let disposed = false;
  let lastStatus = null;
  let heatmapOn = true;
  let markersOn = true;
  let beaconsOn = true;

  // scratch objects (zero per-frame allocation)
  const _v3 = new THREE.Vector3();
  const _v3b = new THREE.Vector3();
  const _v3s = new THREE.Vector3();
  const _color = new THREE.Color();
  const _color2 = new THREE.Color();
  const _dummy = new THREE.Object3D();
  const _invGroup = new THREE.Matrix4();
  const C_RED = new THREE.Color(STATUS_COLORS.critical);
  const C_YEL = new THREE.Color(STATUS_COLORS.warning);
  const C_GRN = new THREE.Color(STATUS_COLORS.online);

  function ackRamp(t, out) {
    if (t < 0.5) out.lerpColors(C_RED, C_YEL, t * 2);
    else out.lerpColors(C_YEL, C_GRN, (t - 0.5) * 2);
  }

  function beginFrame() {
    group.updateWorldMatrix(true, false);
    _invGroup.copy(group.matrixWorld).invert();
  }

  function isVisible(mesh) {
    let o = mesh;
    while (o) {
      if (!o.visible) return false;
      if (o === root) return true;
      o = o.parent;
    }
    return false;
  }

  function attachedToRoot(mesh) {
    let o = mesh;
    while (o) {
      if (o === root) return true;
      o = o.parent;
    }
    return false;
  }

  // --- zone binding -----------------------------------------------------------
  function cloneMats(material) {
    return Array.isArray(material) ? material.map((m) => m.clone()) : [material.clone()];
  }

  // Geometry-space frame: thin axis = surface normal (works for box volumes
  // and for plane geometries whose mesh is already rotated flat).
  function setupGeometry(entry) {
    const geo = entry.mesh.geometry;
    if (!geo.boundingBox) geo.computeBoundingBox();
    entry.geomBox.copy(geo.boundingBox);
    const size = entry.geomBox.getSize(new THREE.Vector3());
    const c = entry.geomBox.getCenter(new THREE.Vector3());
    let nAxis = 'x';
    if (size.y <= size.x && size.y <= size.z) nAxis = 'y';
    else if (size.z <= size.x && size.z <= size.y) nAxis = 'z';
    const [uAxis, vAxis] = nAxis === 'y' ? ['x', 'z'] : nAxis === 'z' ? ['x', 'y'] : ['z', 'y'];
    entry.frame = {
      nAxis, uAxis, vAxis,
      uSize: size[uAxis], vSize: size[vAxis],
      cu: c[uAxis], cv: c[vAxis],
      surface: entry.geomBox.max[nAxis],
    };
    const hm = entry.heatmap;
    hm.rotation.set(0, 0, 0);
    if (nAxis === 'y') hm.rotation.x = -Math.PI / 2;
    else if (nAxis === 'x') hm.rotation.y = Math.PI / 2;
    hm.scale.set(entry.frame.uSize * 0.96, entry.frame.vSize * 0.96, 1);
    hm.position.set(0, 0, 0);
    hm.position[uAxis] = entry.frame.cu;
    hm.position[vAxis] = entry.frame.cv;
    hm.position[nAxis] = entry.frame.surface + 0.03;
    entry.lastLayoutN = -1; // force marker re-layout
  }

  function bind(mesh, code, isProxy) {
    const match = CODE_RE.exec(code);
    const entry = {
      code, isProxy, mesh,
      floorNumber: match ? parseInt(match[1], 10) : 0,
      zoneIndex: match ? ZONE_CODES.indexOf(match[2]) : 0,
      phase: hashPhase(code),
      model: null, prevAck: undefined, severity: 'offline',
      geomBox: new THREE.Box3(), worldBox: new THREE.Box3(), frame: null,
      mats: [], originalMaterial: null,
      anchor: new THREE.Group(), heatmap: null,
      markers: null, markerCap: 0, lastLayoutN: -1, lastOnline: -1, lastTotal: -1,
      beacon: null, beaconCore: null, beaconRing: null,
      beaconMat: null, beaconRingMat: null,
      beaconWanted: false, beaconSeverity: 'warning',
    };
    entry.anchor.name = `statusViz:anchor:${code}`;
    entry.originalMaterial = mesh.material;
    if (isProxy) {
      entry.mats = [mesh.material]; // proxy material is ours already
    } else {
      // Clone once: zone materials may be shared between meshes; afterwards
      // we only mutate color/emissive — no new materials per status update.
      entry.mats = cloneMats(mesh.material);
      mesh.material = Array.isArray(entry.originalMaterial) ? entry.mats : entry.mats[0];
    }
    entry.heatmap = new THREE.Mesh(heatmapGeo, new THREE.MeshBasicMaterial({
      color: 0xffffff, transparent: true, opacity: 0.2, depthWrite: false,
      side: THREE.DoubleSide, toneMapped: false,
      polygonOffset: true, polygonOffsetFactor: -1, polygonOffsetUnits: -1,
    }));
    entry.heatmap.name = `statusViz:heatmap:${code}`;
    entry.heatmap.renderOrder = 2;
    entry.anchor.add(entry.heatmap);
    group.add(entry.anchor);
    setupGeometry(entry);
    zones.set(code, entry);
    return entry;
  }

  function discardProxy(entry) {
    proxyGroup.remove(entry.mesh);
    entry.mesh.material.dispose();
  }

  function unbind(entry) {
    zones.delete(entry.code);
    criticalCodes.delete(entry.code);
    if (entry.isProxy) {
      discardProxy(entry);
    } else {
      entry.mesh.material = entry.originalMaterial; // restore module-owned material
      for (const m of entry.mats) m.dispose();
      delete entry.mesh.userData.zoneStatus;
    }
    group.remove(entry.anchor);
    entry.heatmap.material.dispose();
    if (entry.markers) entry.markers.dispose(); // frees instance buffers only
    if (entry.beacon) {
      group.remove(entry.beacon);
      entry.beaconMat.dispose();
      entry.beaconRingMat.dispose();
    }
  }

  function rebindReal(entry, mesh) {
    discardProxy(entry);
    entry.mesh = mesh;
    entry.isProxy = false;
    entry.originalMaterial = mesh.material;
    entry.mats = cloneMats(mesh.material);
    mesh.material = Array.isArray(entry.originalMaterial) ? entry.mats : entry.mats[0];
    setupGeometry(entry);
    entry.lastOnline = -1;
    entry.lastTotal = -1;
  }

  // Fallback volume from config when no tagged mesh exists for a zone yet.
  function ensureProxy(code) {
    const match = CODE_RE.exec(code);
    if (!match) return null;
    const floorNumber = parseInt(match[1], 10);
    const zi = ZONE_CODES.indexOf(match[2]);
    if (floorNumber < 1 || floorNumber > BUILDING.floors || zi < 0) return null;
    if (!proxyGeo) {
      const r = zoneRect(0);
      proxyGeo = ownGeo(new THREE.BoxGeometry(r.w, BUILDING.floorHeight * 0.8, r.d));
    }
    const rect = zoneRect(zi);
    const mat = M.status('offline');
    mat.opacity = 0.3;
    mat.depthWrite = false;
    const mesh = new THREE.Mesh(proxyGeo, mat);
    mesh.name = `statusViz:proxy:${code}`;
    mesh.renderOrder = 1;
    mesh.position.set(rect.x, floorY(floorNumber - 1) + BUILDING.floorHeight * 0.4, rect.z);
    mesh.userData = { floor: floorNumber, zone: ZONE_CODES[zi], zoneCode: code, statusVizProxy: true };
    proxyGroup.add(mesh);
    return bind(mesh, code, true);
  }

  // Discover tagged zone meshes under root (skipping our own subtree).
  function rescan() {
    const seen = new Set();
    const consider = (mesh) => {
      const code = mesh.userData.zoneCode;
      seen.add(code);
      const entry = zones.get(code);
      if (entry && entry.mesh === mesh) return;
      if (entry && entry.isProxy) { rebindReal(entry, mesh); return; }
      if (entry) unbind(entry); // mesh replaced by a module rebuild
      bind(mesh, code, false);
    };
    const walk = (obj) => {
      for (const child of obj.children) {
        if (child === group) continue;
        if (child.isMesh && typeof child.userData?.zoneCode === 'string' && CODE_RE.test(child.userData.zoneCode)) {
          consider(child);
        }
        walk(child);
      }
    };
    walk(root);
    for (const [code, entry] of zones) {
      if (entry.isProxy || seen.has(code)) continue;
      if (!attachedToRoot(entry.mesh)) unbind(entry); // zone mesh removed from scene
    }
  }

  // --- device markers (instanced dots) ----------------------------------------
  function ensureMarkers(entry, total) {
    if (entry.markers && total <= entry.markerCap) return;
    if (entry.markers) {
      entry.anchor.remove(entry.markers);
      entry.markers.dispose();
    }
    const cap = Math.max(8, total + 4);
    const im = new THREE.InstancedMesh(markerGeo, markerMat, cap);
    im.name = `statusViz:devices:${entry.code}`;
    im.frustumCulled = false;
    im.renderOrder = 3;
    im.instanceMatrix.setUsage(THREE.DynamicDrawUsage);
    for (let i = 0; i < cap; i++) im.setColorAt(i, _color.setHex(0xffffff));
    im.count = 0;
    entry.markers = im;
    entry.markerCap = cap;
    entry.lastLayoutN = -1;
    entry.lastOnline = -1;
    entry.lastTotal = -1;
    entry.anchor.add(im);
  }

  function layoutMarkers(entry, n) {
    const im = entry.markers;
    if (!im) return;
    if (n <= 0) { im.count = 0; return; }
    const f = entry.frame;
    const cols = Math.max(1, Math.round(Math.sqrt(n * (f.uSize / Math.max(f.vSize, 0.001)))));
    const rows = Math.ceil(n / cols);
    const spanU = f.uSize * 0.7;
    const spanV = f.vSize * 0.7;
    const stepU = cols > 1 ? spanU / (cols - 1) : spanU;
    const stepV = rows > 1 ? spanV / (rows - 1) : spanV;
    for (let i = 0; i < n; i++) {
      const col = i % cols;
      const row = Math.floor(i / cols);
      const ju = ((hashPhase(`${entry.code}:${i}`) % 1) - 0.5) * Math.min(stepU, 0.5) * 0.3;
      const jv = ((hashPhase(`${entry.code}#${i}`) % 1) - 0.5) * Math.min(stepV, 0.5) * 0.3;
      _v3.set(0, 0, 0);
      _v3[f.uAxis] = f.cu + (cols > 1 ? (col / (cols - 1) - 0.5) * spanU : 0) + ju;
      _v3[f.vAxis] = f.cv + (rows > 1 ? (row / (rows - 1) - 0.5) * spanV : 0) + jv;
      _v3[f.nAxis] = f.surface + 0.1;
      _dummy.position.copy(_v3);
      _dummy.updateMatrix();
      im.setMatrixAt(i, _dummy.matrix);
    }
    im.count = n;
    im.instanceMatrix.needsUpdate = true;
  }

  function recolorMarkers(entry, online, total) {
    const im = entry.markers;
    if (!im) return;
    for (let i = 0; i < entry.markerCap; i++) {
      _color.setHex(i < online ? STATUS_COLORS.online : STATUS_COLORS.offline);
      im.setColorAt(i, _color);
    }
    if (im.instanceColor) im.instanceColor.needsUpdate = true;
    im.count = total;
  }

  // --- alert beacons ------------------------------------------------------------
  function ensureBeacon(entry) {
    if (entry.beacon) return;
    const mat = M.beacon(0xffffff); // fresh emissive material, owned per beacon
    const ringMat = new THREE.MeshBasicMaterial({
      color: 0xffffff, transparent: true, opacity: 0.4,
      blending: THREE.AdditiveBlending, depthWrite: false,
      side: THREE.DoubleSide, toneMapped: false,
    });
    const b = new THREE.Group();
    b.name = `statusViz:beacon:${entry.code}`;
    const core = new THREE.Mesh(beaconCoreGeo, mat);
    core.name = 'core';
    const ring = new THREE.Mesh(beaconRingGeo, ringMat);
    ring.rotation.x = -Math.PI / 2;
    ring.position.y = -0.1;
    const cone = new THREE.Mesh(beaconConeGeo, mat);
    cone.rotation.x = Math.PI; // point down at the zone
    cone.position.y = -0.45;
    b.add(core, ring, cone);
    b.visible = false;
    b.renderOrder = 4;
    group.add(b);
    entry.beacon = b;
    entry.beaconCore = core;
    entry.beaconRing = ring;
    entry.beaconMat = mat;
    entry.beaconRingMat = ringMat;
  }

  // --- ACK particles --------------------------------------------------------------
  function spawnAck(entry, count) {
    beginFrame();
    syncEntry(entry); // fresh world box + group inverse
    entry.worldBox.getCenter(_v3);
    const size = entry.worldBox.getSize(_v3s);
    for (let i = 0; i < count; i++) {
      const idx = pCursor;
      pCursor = (pCursor + 1) % MAX_PARTICLES;
      const p = particles[idx];
      if (!p.active) activeParticles++;
      p.active = true;
      p.age = 0;
      p.delay = i * 0.05;
      p.life = 0.85 + Math.random() * 0.5;
      p.arc = 1 + Math.random() * 1.6;
      // start: inside the zone volume (world) → group-local
      _v3b.set(
        _v3.x + (Math.random() - 0.5) * size.x * 0.5,
        _v3.y + Math.random() * size.y * 0.3,
        _v3.z + (Math.random() - 0.5) * size.z * 0.5,
      ).applyMatrix4(_invGroup);
      p.sx = _v3b.x; p.sy = _v3b.y; p.sz = _v3b.z;
      // end: central elevator/stair core at the zone's height (world) → group-local
      _v3b.set(
        (Math.random() - 0.5) * (BUILDING.coreWidth - 3),
        _v3.y + 0.4 + Math.random() * 1.4,
        (Math.random() - 0.5) * (BUILDING.coreDepth - 3),
      ).applyMatrix4(_invGroup);
      p.ex = _v3b.x; p.ey = _v3b.y; p.ez = _v3b.z;
      const b = 0.75 + Math.random() * 0.45;
      _color.setHex(STATUS_COLORS.online);
      p.r = _color.r * b; p.g = _color.g * b; p.b = _color.b * b;
      const i3 = idx * 3;
      pPositions[i3] = p.sx; pPositions[i3 + 1] = PARK_Y; pPositions[i3 + 2] = p.sz;
      pColors[i3] = 0; pColors[i3 + 1] = 0; pColors[i3 + 2] = 0;
    }
    posAttr.needsUpdate = true;
    colAttr.needsUpdate = true;
  }

  function integrateParticles(step) {
    let any = false;
    for (let i = 0; i < MAX_PARTICLES; i++) {
      const p = particles[i];
      if (!p.active) continue;
      any = true;
      const i3 = i * 3;
      if (p.delay > 0) { p.delay -= step; continue; }
      p.age += step;
      const t = p.age / p.life;
      if (t >= 1) {
        p.active = false;
        activeParticles--;
        pPositions[i3 + 1] = PARK_Y;
        pColors[i3] = 0; pColors[i3 + 1] = 0; pColors[i3 + 2] = 0;
        continue;
      }
      const e = t * t * (3 - 2 * t); // smoothstep
      pPositions[i3] = p.sx + (p.ex - p.sx) * e;
      pPositions[i3 + 1] = p.sy + (p.ey - p.sy) * e + Math.sin(Math.PI * t) * p.arc;
      pPositions[i3 + 2] = p.sz + (p.ez - p.sz) * e;
      const f = Math.pow(1 - t, 1.4); // additive fade-out
      pColors[i3] = p.r * f; pColors[i3 + 1] = p.g * f; pColors[i3 + 2] = p.b * f;
    }
    if (any) {
      posAttr.needsUpdate = true;
      colAttr.needsUpdate = true;
    }
  }

  // --- per-zone model → visuals ----------------------------------------------------
  function applyModel(entry) {
    const m = entry.model || {};
    const sev = SEVERITIES.includes(m.severity) ? m.severity : deriveSeverity(m);
    entry.severity = sev;
    _color.setHex(STATUS_COLORS[sev] ?? STATUS_COLORS.offline);
    for (const mat of entry.mats) {
      mat.color.copy(_color);
      mat.emissive.copy(_color);
      mat.emissiveIntensity = SEV_EMISSIVE[sev];
    }
    if (sev === 'critical') criticalCodes.add(entry.code);
    else criticalCodes.delete(entry.code);

    // heatmap: ack ratio ramp (offline zones go neutral gray)
    const received = m.received ?? 0;
    const ack = m.acknowledged ?? 0;
    const ratio = received > 0 ? clamp(ack / received, 0, 1) : 1;
    if (sev === 'offline') _color2.setHex(STATUS_COLORS.offline);
    else ackRamp(ratio, _color2);
    entry.heatmap.material.color.copy(_color2);
    entry.heatmap.material.opacity = sev === 'offline' ? 0.12 : 0.14 + 0.4 * (1 - ratio);

    // instanced device markers
    const total = Math.max(0, Math.floor(m.devicesTotal ?? 0));
    const online = clamp(Math.floor(m.online ?? 0), 0, total);
    ensureMarkers(entry, total);
    if (total !== entry.lastLayoutN) {
      layoutMarkers(entry, total);
      entry.lastLayoutN = total;
    }
    if (online !== entry.lastOnline || total !== entry.lastTotal) {
      recolorMarkers(entry, online, total);
      entry.lastOnline = online;
      entry.lastTotal = total;
    }
    entry.markers.count = total;

    // alert beacon (warning/critical zones; alert severity wins for color)
    const alert = alertByZone.get(entry.code);
    entry.beaconWanted = sev === 'warning' || sev === 'critical';
    entry.beaconSeverity =
      alert && (alert.severity === 'critical' || alert.severity === 'warning') ? alert.severity : sev;
    if (entry.beaconWanted) {
      ensureBeacon(entry);
      _color.setHex(STATUS_COLORS[entry.beaconSeverity] ?? STATUS_COLORS.critical);
      entry.beaconMat.color.copy(_color);
      entry.beaconMat.emissive.copy(_color);
      entry.beaconRingMat.color.copy(_color);
    }

    // ACK particles on acknowledged increase (monotonic counter; first
    // snapshot only sets the baseline, resync decreases never spawn)
    if (typeof entry.prevAck === 'number' && ack > entry.prevAck) {
      spawnAck(entry, Math.min(ack - entry.prevAck, 10));
    }
    entry.prevAck = ack;
    entry.mesh.userData.zoneStatus = m; // additive tag for ui.js tooltips
  }

  // Copy the zone mesh's world transform into the anchor (overlays follow
  // explode/cutaway/floor isolation) and place the beacon in world space.
  function syncEntry(entry) {
    const mesh = entry.mesh;
    mesh.updateWorldMatrix(true, false);
    entry.worldBox.copy(entry.geomBox).applyMatrix4(mesh.matrixWorld);
    entry.anchor.position.setFromMatrixPosition(mesh.matrixWorld);
    entry.anchor.quaternion.setFromRotationMatrix(mesh.matrixWorld);
    entry.anchor.scale.setFromMatrixScale(mesh.matrixWorld);
    const vis = isVisible(mesh);
    entry.anchor.visible = vis && (heatmapOn || markersOn);
    entry.heatmap.visible = heatmapOn;
    if (entry.markers) entry.markers.visible = markersOn;
    if (entry.beacon) {
      const show = vis && beaconsOn && entry.beaconWanted;
      entry.beacon.visible = show;
      if (show) {
        entry.worldBox.getCenter(_v3);
        _v3.y = entry.worldBox.max.y + 0.85 + Math.sin(time * 2.2 + entry.phase) * 0.12;
        _v3.applyMatrix4(_invGroup);
        entry.beacon.position.copy(_v3);
      }
    }
  }

  // --- public API -------------------------------------------------------------------
  function setStatus(status) {
    if (disposed || !status) return;
    lastStatus = status;
    rescan();
    if (Array.isArray(status.activeAlerts)) {
      alertByZone.clear();
      for (const a of status.activeAlerts) if (a && a.zoneCode) alertByZone.set(a.zoneCode, a);
    }
    for (const f of status.floors ?? []) {
      for (const z of f?.zones ?? []) {
        const code = z?.zoneCode;
        if (typeof code !== 'string' || !CODE_RE.test(code)) continue;
        let entry = zones.get(code);
        if (!entry) entry = ensureProxy(code);
        if (!entry) continue;
        // merge: snapshot zones are full; delta zones may be partial
        entry.model = { ...(entry.model || {}), ...z };
        if (typeof f.floorNumber === 'number') entry.model.floorNumber = f.floorNumber;
      }
    }
    for (const entry of zones.values()) applyModel(entry);
    beginFrame();
    for (const entry of zones.values()) syncEntry(entry);
  }

  function update(status, dt) {
    if (disposed) return;
    if (status && status !== lastStatus) setStatus(status);
    const step = clamp(typeof dt === 'number' && Number.isFinite(dt) ? dt : 1 / 60, 0, 0.1);
    time += step;
    beginFrame();
    for (const entry of zones.values()) {
      syncEntry(entry);
      if (entry.beacon && entry.beacon.visible) {
        const fast = entry.beaconSeverity === 'critical';
        const p = 0.5 + 0.5 * Math.sin(time * (fast ? 7 : 3) + entry.phase);
        entry.beaconCore.rotation.y = time * 1.6 + entry.phase;
        entry.beaconMat.emissiveIntensity = 0.9 + 0.9 * p;
        entry.beaconRing.scale.setScalar(1 + 0.18 * p);
        entry.beaconRingMat.opacity = 0.22 + 0.4 * p;
      }
    }
    // critical heartbeat on zone emissive
    for (const code of criticalCodes) {
      const e = zones.get(code);
      if (!e) continue;
      const pulse = 0.3 + 0.45 * (0.5 + 0.5 * Math.sin(time * 6 + e.phase));
      for (const mat of e.mats) mat.emissiveIntensity = pulse;
    }
    integrateParticles(step);
  }

  function dispose() {
    if (disposed) return;
    disposed = true;
    for (const entry of [...zones.values()]) unbind(entry);
    for (const g of ownedGeometries) g.dispose();
    for (const m of ownedMaterials) m.dispose();
    if (ownsLibrary) for (const m of Object.values(M)) if (m && m.isMaterial) m.dispose();
    group.removeFromParent();
    zones.clear();
    criticalCodes.clear();
    alertByZone.clear();
    lastStatus = null;
  }

  function getStats() {
    let beacons = 0;
    let proxies = 0;
    let real = 0;
    for (const e of zones.values()) {
      if (e.isProxy) proxies++; else real++;
      if (e.beacon && e.beacon.visible) beacons++;
    }
    return {
      zones: zones.size, realZones: real, proxyZones: proxies,
      critical: criticalCodes.size, beacons, particles: activeParticles,
      heatmapOn, markersOn, beaconsOn,
    };
  }

  return {
    group,
    setStatus,
    update,
    dispose,
    rescan,
    setHeatmapVisible: (v) => { heatmapOn = !!v; },
    setMarkersVisible: (v) => { markersOn = !!v; },
    setBeaconsVisible: (v) => { beaconsOn = !!v; },
    getStats,
  };
}
