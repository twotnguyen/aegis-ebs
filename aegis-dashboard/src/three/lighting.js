// lighting.js — cinematic lighting rig for AEGIS Tower B01.
// Sun/moon DirectionalLight with 2048 PCF-soft shadow map covering the tower,
// HemisphereLight (sky/ground) + subtle AmbientLight fill, warm interior
// ceiling lighting per floor (1 capped PointLight + emissive panels), lobby
// and landscape night lighting. Day/night modes cross-fade intensity, colors,
// sky tint and ACESFilmic exposure. Perf: max 12 point lights — everything
// else (ceiling panels, lamp heads, bollards) is emissive-only geometry.
import * as THREE from 'three';
import { BUILDING, floorY, totalHeight, zoneRect } from './config.js';
import { makeMaterials } from './materials.js';

// Hard cap on real point lights: 10 interior (1/floor) + 1 lobby + 1 plaza.
export const MAX_POINT_LIGHTS = 12;

// Sky palette shared with environment.js / assembler for background + fog.
export const SKY = {
  day:   { background: 0xbfd9ee, fog: 0xcfdfee, hemiSky: 0xbdd7ff, hemiGround: 0x9a8f7a },
  night: { background: 0x0a1020, fog: 0x0b1122, hemiSky: 0x1c2b4d, hemiGround: 0x0c0f14 },
};

// Per-mode lighting presets (three r155+ physical units: point = candela).
const PRESET = {
  day: {
    sunColor: 0xfff2dd, sunIntensity: 3.2, sunPos: [70, 95, 45],
    hemiIntensity: 1.0, ambientColor: 0xffffff, ambientIntensity: 0.16,
    interior: 4, lobby: 5, plaza: 0,
    panel: 0.12, lamp: 0.05, bollard: 0.05, exposure: 1.0,
  },
  night: {
    sunColor: 0x9db4e8, sunIntensity: 0.4, sunPos: [-60, 75, -40], // moon
    hemiIntensity: 0.3, ambientColor: 0x2a3550, ambientIntensity: 0.1,
    interior: 60, lobby: 45, plaza: 35,
    panel: 2.4, lamp: 3.0, bollard: 2.0, exposure: 0.85,
  },
};

const lerp = (a, b, t) => a + (b - a) * t;

// Module-level renderer link so the standalone applyRenderer() export and the
// rig built by build() share exposure state (single-scene dashboard).
let rendererRef = null;
let exposureNow = PRESET.day.exposure;

// Configure the renderer: shadows (PCFSoft) + ACESFilmic tone mapping.
// Assembler calls this once with its WebGLRenderer; exposure then tracks mode.
export function applyRenderer(renderer) {
  if (!renderer) return renderer;
  renderer.shadowMap.enabled = true;
  renderer.shadowMap.type = THREE.PCFSoftShadowMap;
  renderer.toneMapping = THREE.ACESFilmicToneMapping;
  renderer.toneMappingExposure = exposureNow;
  rendererRef = renderer;
  return renderer;
}

export function build(ctx) {
  const mats = (ctx && ctx.materials) || makeMaterials();
  const root = ctx && ctx.root;

  const group = new THREE.Group();
  group.name = 'lighting';

  // Resources owned by this module (shared library materials are NOT disposed).
  const ownGeometries = [];
  const ownMaterials = [];
  const ownInstanced = [];
  const trackGeo = (g) => { ownGeometries.push(g); return g; };
  const trackInst = (m) => { ownInstanced.push(m); return m; };

  // ---- Sun / moon: directional + shadow map -------------------------------
  const sun = new THREE.DirectionalLight(PRESET.day.sunColor, PRESET.day.sunIntensity);
  sun.name = 'sun';
  sun.position.set(...PRESET.day.sunPos);
  sun.castShadow = true;
  sun.shadow.mapSize.set(2048, 2048);
  // Ortho shadow camera encloses the whole tower (44x30 footprint, ~39m tall)
  // plus plaza margin, from both day and night light positions.
  const sc = sun.shadow.camera;
  sc.left = -65; sc.right = 65; sc.top = 65; sc.bottom = -65;
  sc.near = 10; sc.far = 280;
  sc.updateProjectionMatrix();
  sun.shadow.bias = -0.0004;
  sun.shadow.normalBias = 0.3;
  sun.target.name = 'sunTarget';
  sun.target.position.set(0, totalHeight() * 0.35, 0);
  group.add(sun, sun.target);

  // ---- Sky / ground fill ---------------------------------------------------
  const hemi = new THREE.HemisphereLight(SKY.day.hemiSky, SKY.day.hemiGround, PRESET.day.hemiIntensity);
  hemi.name = 'hemisphere';
  hemi.position.set(0, 1, 0);
  group.add(hemi);

  const ambient = new THREE.AmbientLight(PRESET.day.ambientColor, PRESET.day.ambientIntensity);
  ambient.name = 'ambient';
  group.add(ambient);

  // ---- Interior: 1 warm capped PointLight per floor (no shadows) -----------
  // Simulates ceiling fixtures; the visible fixtures are emissive panels below.
  const interior = [];
  const ceilY = (i) => floorY(i) + BUILDING.floorHeight - 0.06;
  for (let i = 0; i < BUILDING.floors; i++) {
    const p = new THREE.PointLight(0xffd9a8, PRESET.day.interior, 30, 2);
    p.name = `interior-F${String(i + 1).padStart(2, '0')}`;
    // Core-centered: omnidirectional, shadowless, distance-limited -> cheap.
    p.position.set(0, floorY(i) + BUILDING.floorHeight - 0.7, 0);
    p.castShadow = false;
    interior.push(p);
    group.add(p);
  }

  // ---- Lobby + plaza (landscape) real lights -------------------------------
  const lobby = new THREE.PointLight(0xffd2a0, PRESET.day.lobby, 22, 2);
  lobby.name = 'lobby';
  lobby.position.set(0, BUILDING.floorHeight - 0.6, BUILDING.depth / 2 - 5);
  lobby.castShadow = false;
  group.add(lobby);

  const plaza = new THREE.PointLight(0xffc98a, PRESET.day.plaza, 45, 2);
  plaza.name = 'plaza';
  plaza.position.set(0, 4.5, BUILDING.depth / 2 + 11);
  plaza.castShadow = false;
  group.add(plaza);

  const pointCount = interior.length + 2;
  if (pointCount > MAX_POINT_LIGHTS) {
    throw new Error(`lighting: ${pointCount} point lights exceeds cap ${MAX_POINT_LIGHTS}`);
  }

  // ---- Emissive fixtures (no light cost) -----------------------------------
  const panelMat = mats.emissive(0xffd9a8, PRESET.day.panel);   // ceiling panels + lobby discs
  const lampMat = mats.emissive(0xffc978, PRESET.day.lamp);     // lamp post heads
  const bollardMat = mats.emissive(0xffb86b, PRESET.day.bollard); // path bollards
  ownMaterials.push(panelMat, lampMat, bollardMat);

  const dummy = new THREE.Object3D();
  const place = (mesh, idx, x, y, z) => {
    dummy.position.set(x, y, z);
    dummy.rotation.set(0, 0, 0);
    dummy.updateMatrix();
    mesh.setMatrixAt(idx, dummy.matrix);
  };

  // Ceiling panels: 2 per zone per floor (10 floors x 4 zones x 2 = 80).
  const panelGeo = trackGeo(new THREE.BoxGeometry(5.5, 0.08, 1.1));
  const panelsPerFloor = BUILDING.zonesPerFloor * 2;
  const ceilingPanels = trackInst(new THREE.InstancedMesh(panelGeo, panelMat, BUILDING.floors * panelsPerFloor));
  ceilingPanels.name = 'ceilingPanels';
  let pi = 0;
  for (let i = 0; i < BUILDING.floors; i++) {
    for (let zi = 0; zi < BUILDING.zonesPerFloor; zi++) {
      const r = zoneRect(zi);
      place(ceilingPanels, pi++, r.x, ceilY(i), r.z - r.d * 0.22);
      place(ceilingPanels, pi++, r.x, ceilY(i), r.z + r.d * 0.22);
    }
  }
  ceilingPanels.instanceMatrix.needsUpdate = true;
  group.add(ceilingPanels);

  // Lobby downlight discs under the entrance ceiling.
  const discGeo = trackGeo(new THREE.CylinderGeometry(0.55, 0.55, 0.06, 16));
  const lobbyDiscs = trackInst(new THREE.InstancedMesh(discGeo, panelMat, 3));
  lobbyDiscs.name = 'lobbyDownlights';
  [-5, 0, 5].forEach((x, k) => place(lobbyDiscs, k, x, BUILDING.floorHeight - 0.05, BUILDING.depth / 2 - 4.5));
  lobbyDiscs.instanceMatrix.needsUpdate = true;
  group.add(lobbyDiscs);

  // Landscape lamp posts: front row, both sides, back row (emissive heads).
  const postPos = [];
  for (const x of [-20, -10, 0, 10, 20]) postPos.push([x, BUILDING.depth / 2 + 9]);
  for (const z of [-12, -4, 4, 12]) {
    postPos.push([-(BUILDING.width / 2 + 7), z]);
    postPos.push([BUILDING.width / 2 + 7, z]);
  }
  for (const x of [-15, 0, 15]) postPos.push([x, -(BUILDING.depth / 2 + 9)]);
  const poleGeo = trackGeo(new THREE.CylinderGeometry(0.07, 0.11, 3.6, 8));
  const lampPosts = trackInst(new THREE.InstancedMesh(poleGeo, mats.steel, postPos.length)); // shared material
  lampPosts.name = 'lampPosts';
  lampPosts.castShadow = true;
  const headGeo = trackGeo(new THREE.SphereGeometry(0.22, 12, 8));
  const lampHeads = trackInst(new THREE.InstancedMesh(headGeo, lampMat, postPos.length));
  lampHeads.name = 'lampHeads';
  postPos.forEach(([x, z], k) => {
    place(lampPosts, k, x, 1.8, z);
    place(lampHeads, k, x, 3.7, z);
  });
  lampPosts.instanceMatrix.needsUpdate = true;
  lampHeads.instanceMatrix.needsUpdate = true;
  group.add(lampPosts, lampHeads);

  // Path bollards along the entrance walkway (emissive-only).
  const bollardGeo = trackGeo(new THREE.CylinderGeometry(0.09, 0.09, 0.8, 8));
  const bollardXs = [];
  for (let x = -16; x <= 16; x += 4) bollardXs.push(x);
  const bollards = trackInst(new THREE.InstancedMesh(bollardGeo, bollardMat, bollardXs.length));
  bollards.name = 'pathBollards';
  bollardXs.forEach((x, k) => place(bollards, k, x, 0.4, BUILDING.depth / 2 + 5.5));
  bollards.instanceMatrix.needsUpdate = true;
  group.add(bollards);

  // ---- Day/night cross-fade state ------------------------------------------
  let mode = 'day';
  let mix = 0;        // 0 = day, 1 = night (animated)
  let targetMix = 0;

  // Cached colors/vectors to avoid per-frame allocation.
  const C = {
    sun: [new THREE.Color(PRESET.day.sunColor), new THREE.Color(PRESET.night.sunColor)],
    ambient: [new THREE.Color(PRESET.day.ambientColor), new THREE.Color(PRESET.night.ambientColor)],
    hemiSky: [new THREE.Color(SKY.day.hemiSky), new THREE.Color(SKY.night.hemiSky)],
    hemiGround: [new THREE.Color(SKY.day.hemiGround), new THREE.Color(SKY.night.hemiGround)],
    bg: [new THREE.Color(SKY.day.background), new THREE.Color(SKY.night.background)],
    fog: [new THREE.Color(SKY.day.fog), new THREE.Color(SKY.night.fog)],
    sunPos: [new THREE.Vector3(...PRESET.day.sunPos), new THREE.Vector3(...PRESET.night.sunPos)],
    tmp: new THREE.Color(),
  };

  const findScene = () => {
    let o = group;
    while (o) { if (o.isScene) return o; o = o.parent; }
    return null;
  };

  function apply(m) {
    const d = PRESET.day, n = PRESET.night;
    sun.color.lerpColors(C.sun[0], C.sun[1], m);
    sun.intensity = lerp(d.sunIntensity, n.sunIntensity, m);
    sun.position.lerpVectors(C.sunPos[0], C.sunPos[1], m);
    hemi.color.lerpColors(C.hemiSky[0], C.hemiSky[1], m);
    hemi.groundColor.lerpColors(C.hemiGround[0], C.hemiGround[1], m);
    hemi.intensity = lerp(d.hemiIntensity, n.hemiIntensity, m);
    ambient.color.lerpColors(C.ambient[0], C.ambient[1], m);
    ambient.intensity = lerp(d.ambientIntensity, n.ambientIntensity, m);
    const interiorI = lerp(d.interior, n.interior, m);
    for (const p of interior) p.intensity = interiorI;
    lobby.intensity = lerp(d.lobby, n.lobby, m);
    plaza.intensity = lerp(d.plaza, n.plaza, m);
    panelMat.emissiveIntensity = lerp(d.panel, n.panel, m);
    lampMat.emissiveIntensity = lerp(d.lamp, n.lamp, m);
    bollardMat.emissiveIntensity = lerp(d.bollard, n.bollard, m);
    // Sky tint: only when scene background/fog are plain colors (a sky dome
    // mesh owned by environment.js is left untouched).
    const scene = findScene();
    if (scene) {
      if (scene.background && scene.background.isColor) scene.background.lerpColors(C.bg[0], C.bg[1], m);
      if (scene.fog && scene.fog.color) scene.fog.color.lerpColors(C.fog[0], C.fog[1], m);
    }
    exposureNow = lerp(d.exposure, n.exposure, m);
    if (rendererRef) rendererRef.toneMappingExposure = exposureNow;
  }

  function setMode(next) {
    if (next !== 'day' && next !== 'night') return mode; // ignore unknown modes
    mode = next;
    targetMix = next === 'night' ? 1 : 0;
    return mode;
  }

  // Accepts update(dt) (task contract) or update(status, dt) (module contract).
  function update(a, b) {
    const dt = Math.min(typeof b === 'number' ? b : (typeof a === 'number' ? a : 0.016), 0.1);
    if (mix !== targetMix) {
      mix += (targetMix - mix) * (1 - Math.exp(-3.2 * dt));
      if (Math.abs(targetMix - mix) < 0.001) mix = targetMix;
      apply(mix);
    }
  }

  function dispose() {
    group.removeFromParent();
    for (const m of ownInstanced) m.dispose();
    for (const g of ownGeometries) g.dispose();
    for (const m of ownMaterials) m.dispose();
    if (sun.shadow.map) { sun.shadow.map.dispose(); sun.shadow.map = null; }
    if (sun.shadow.mapPass) { sun.shadow.mapPass.dispose(); sun.shadow.mapPass = null; }
    rendererRef = null;
  }

  apply(0); // start in day mode
  if (root) root.add(group);

  return {
    group,
    lights: {
      sun, hemisphere: hemi, ambient, interior, lobby, plaza,
      emissive: { ceilingPanels, lobbyDiscs, lampPosts, lampHeads, bollards },
    },
    setMode,
    getMode: () => mode,
    update,
    dispose,
    applyRenderer, // convenience: same function as the module export
  };
}
