// environment.js — minimal surroundings for AEGIS Tower B01 (main building at origin).
// Performance-focused: ground, entrance plaza + fountain, the main road in front of
// the gate, a few near street lamps and the gradient sky dome remain. The low-rise
// context backdrop, parking lot, cars, pond and far trees were removed.
// Contract: build(ctx) -> { group, dispose }.
import * as THREE from 'three';
import { BUILDING } from './config.js';
import { makeMaterials } from './materials.js';

const TAU = Math.PI * 2;

// Deterministic PRNG so the remaining landscaping is identical on every load.
function mulberry32(seed) {
  let a = seed >>> 0;
  return function () {
    a |= 0; a = (a + 0x6D2B79F5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

export function build(ctx = {}) {
  const T = ctx.THREE || THREE;
  const B = (ctx.config && ctx.config.BUILDING) || BUILDING;
  const M = ctx.materials || makeMaterials();
  const ownsLib = !ctx.materials;

  // Materials created here (or the whole fallback library) are ours to dispose.
  const owned = [];
  const own = (m) => { owned.push(m); return m; };
  if (ownsLib) for (const v of Object.values(M)) if (v && v.isMaterial) owned.push(v);

  const rand = mulberry32(0x5eed2026);
  const rr = (a, b) => a + (b - a) * rand();
  const pick = (arr) => arr[Math.floor(rand() * arr.length)];

  const group = new T.Group();
  group.name = 'environment';
  const sub = (name) => { const g = new T.Group(); g.name = name; group.add(g); return g; };

  const gGround = sub('env-ground');
  const gPlaza = sub('env-plaza');
  const gRoads = sub('env-roads');
  const gVeg = sub('env-vegetation');
  const gFurniture = sub('env-street-furniture');
  const gSky = sub('env-sky');

  // ---------------------------------------------------------------- helpers
  const UNIT_BOX = new T.BoxGeometry(1, 1, 1); // shared by every scaled-box instance set

  function put(parent, geo, mat, name, x, y, z, o = {}) {
    const m = new T.Mesh(geo, mat);
    m.name = name;
    m.position.set(x, y, z);
    if (o.rx) m.rotation.x = o.rx;
    if (o.ry) m.rotation.y = o.ry;
    if (o.rz) m.rotation.z = o.rz;
    m.castShadow = o.cast !== false;
    m.receiveShadow = !!o.recv;
    m.userData = { module: 'environment', kind: o.kind || name };
    parent.add(m);
    return m;
  }

  // items: { p:[x,y,z], r:[rx,ry,rz], s:[sx,sy,sz], c:hex }
  function inst(parent, geo, mat, name, items, o = {}) {
    const im = new T.InstancedMesh(geo, mat, items.length);
    const dummy = new T.Object3D();
    const col = new T.Color();
    items.forEach((it, i) => {
      dummy.position.set(it.p[0], it.p[1], it.p[2]);
      dummy.rotation.set(it.r ? it.r[0] : 0, it.r ? it.r[1] : 0, it.r ? it.r[2] : 0);
      dummy.scale.set(it.s ? it.s[0] : 1, it.s ? it.s[1] : 1, it.s ? it.s[2] : 1);
      dummy.updateMatrix();
      im.setMatrixAt(i, dummy.matrix);
      if (it.c !== undefined) im.setColorAt(i, col.set(it.c));
    });
    im.instanceMatrix.needsUpdate = true;
    if (im.instanceColor) im.instanceColor.needsUpdate = true;
    im.computeBoundingSphere();
    im.name = name;
    im.castShadow = o.cast !== false;
    im.receiveShadow = !!o.recv;
    im.userData = { module: 'environment', kind: o.kind || name, instances: items.length };
    parent.add(im);
    return im;
  }

  const rotY = (vx, vz, th) => [vx * Math.cos(th) + vz * Math.sin(th), -vx * Math.sin(th) + vz * Math.cos(th)];

  // Local paint / detail materials (library has no paint, bark is wood…).
  const paintWhite = own(new T.MeshStandardMaterial({ color: 0xe9eaec, roughness: 0.55, metalness: 0 }));
  const paintYellow = own(new T.MeshStandardMaterial({ color: 0xd8b84a, roughness: 0.55, metalness: 0 }));
  const flagBlue = own(new T.MeshStandardMaterial({ color: 0x2563eb, roughness: 0.6, metalness: 0 }));
  const lampGlow = own(M.emissive(0xffe6b0, 0.9));
  const terrainFar = own(new T.MeshStandardMaterial({ color: 0xa9bfa2, roughness: 1, metalness: 0 }));

  const hd = B.depth / 2;                           // tower half-depth from config
  const plazaW = B.width + 16;                      // always wider than the facade
  const plazaHalf = plazaW / 2;
  const plazaFarZ = 46;                             // meets the near sidewalk of the main road

  // ---------------------------------------------------------------- ground
  put(gGround, new T.PlaneGeometry(700, 700), M.grass, 'env-lawn', 0, -0.06, 0,
    { rx: -Math.PI / 2, cast: false, recv: true, kind: 'grass' });
  put(gGround, new T.CircleGeometry(478, 48), terrainFar, 'env-distant-terrain', 0, -0.16, 0,
    { rx: -Math.PI / 2, cast: false, kind: 'terrain' });

  // ---------------------------------------------------------------- plaza (in front of entrance, +Z)
  const plazaD = plazaFarZ - hd, plazaCz = (hd + plazaFarZ) / 2;
  put(gPlaza, UNIT_BOX, M.concreteDark, 'env-plaza-base', 0, -0.1, plazaCz,
    { cast: false, recv: true, kind: 'plaza' }).scale.set(plazaW, 0.2, plazaD);

  const tile = 3.42, gap = 0.1, pitchT = tile + gap;
  const cols = Math.floor((plazaW - 1) / pitchT), rows = Math.floor((plazaD - 1) / pitchT);
  const startX = -(cols * pitchT - gap) / 2 + tile / 2;
  const startZ = hd + (plazaD - (rows * pitchT - gap)) / 2 + tile / 2;
  const tileTints = [0xffffff, 0xf1efe8, 0xe6e4de, 0xfaf8f2, 0xedeae2];
  const tileItems = [];
  for (let i = 0; i < cols; i++) for (let j = 0; j < rows; j++) {
    tileItems.push({ p: [startX + i * pitchT, -0.01, startZ + j * pitchT], s: [tile, 0.06, tile], c: pick(tileTints) });
  }
  inst(gPlaza, UNIT_BOX, M.slab, 'env-plaza-paving', tileItems, { cast: false, recv: true, kind: 'paving' });

  // Fountain — plaza focal point on the entrance axis.
  const fx = 0, fz = hd + 8;
  put(gPlaza, new T.CylinderGeometry(3.9, 4.1, 0.6, 28), M.concrete, 'env-fountain-basin', fx, 0.3, fz, { recv: true, kind: 'fountain' });
  put(gPlaza, new T.CircleGeometry(3.55, 28), M.water, 'env-fountain-water', fx, 0.52, fz, { rx: -Math.PI / 2, cast: false, kind: 'water' });
  put(gPlaza, new T.CylinderGeometry(0.28, 0.45, 1.1, 12), M.concreteDark, 'env-fountain-pedestal', fx, 1.1, fz, { kind: 'fountain' });
  put(gPlaza, new T.SphereGeometry(0.3, 10, 8), M.steel, 'env-fountain-finial', fx, 1.85, fz, { kind: 'fountain' });

  // Benches ringing the fountain (instanced seats/backs/legs), entrance corridor kept clear.
  const seatItems = [], backItems = [], legItems = [];
  for (const deg of [0, 60, 120, 180, 240, 300]) {
    const a = deg * Math.PI / 180, r = 7.6;
    const px = fx + r * Math.cos(a), pz = fz + r * Math.sin(a);
    const th = Math.atan2(fx - px, fz - pz); // +Z faces the fountain
    seatItems.push({ p: [px, 0.46, pz], r: [0, th, 0], s: [2.2, 0.12, 0.62] });
    const [bx, bz] = rotY(0, -0.28, th);
    backItems.push({ p: [px + bx, 0.78, pz + bz], r: [0, th, 0], s: [2.2, 0.55, 0.1] });
    for (const lx of [-0.9, 0.9]) {
      const [ox, oz] = rotY(lx, 0, th);
      legItems.push({ p: [px + ox, 0.22, pz + oz], r: [0, th, 0], s: [0.14, 0.44, 0.55] });
    }
  }
  inst(gFurniture, UNIT_BOX, M.wood, 'env-bench-seats', seatItems, { kind: 'bench' });
  inst(gFurniture, UNIT_BOX, M.wood, 'env-bench-backs', backItems, { kind: 'bench' });
  inst(gFurniture, UNIT_BOX, M.concreteDark, 'env-bench-legs', legItems, { kind: 'bench' });

  // Planters at plaza corners + hedge strips along the sides.
  const planterItems = [], hedgeTopItems = [];
  for (const sx of [-plazaHalf + 2.5, plazaHalf - 2.5]) for (const sz of [hd + 2.5, plazaFarZ - 1.5]) {
    planterItems.push({ p: [sx, 0.35, sz], s: [3, 0.7, 3] });
    hedgeTopItems.push({ p: [sx, 0.95, sz], s: [2.6, 0.55, 2.6] });
  }
  inst(gPlaza, UNIT_BOX, M.concrete, 'env-planters', planterItems, { recv: true, kind: 'planter' });
  inst(gVeg, UNIT_BOX, M.grass, 'env-planter-hedges', hedgeTopItems, { kind: 'hedge' });
  for (const sx of [-plazaHalf + 0.1, plazaHalf - 0.1]) {
    put(gVeg, UNIT_BOX, M.grass, 'env-hedge-strip', sx, 0.36, plazaCz, { kind: 'hedge' }).scale.set(0.9, 0.72, plazaD - 2);
  }

  // Bollards along the plaza edge (gap on the entrance/crosswalk axis).
  const bollardItems = [];
  for (let x = -plazaHalf + 4; x <= plazaHalf - 4; x += 4) {
    if (Math.abs(x) <= 4) continue;
    bollardItems.push({ p: [x, 0.45, plazaFarZ - 0.4], s: [1, 1, 1] });
  }
  inst(gFurniture, new T.CylinderGeometry(0.11, 0.13, 0.85, 8), M.steel, 'env-bollards', bollardItems, { kind: 'bollard' });

  // Flagpoles near the entrance (inside the plaza, clear of the enlarged footprint).
  const flagMats = [M.fireRed, flagBlue, M.exitSign];
  [-10, -13, -16].forEach((x, i) => {
    const fzFlag = hd + 3;
    put(gFurniture, new T.CylinderGeometry(0.05, 0.07, 7.5, 6), M.steel, `env-flagpole-${i}`, x, 3.75, fzFlag, { kind: 'flagpole' });
    put(gFurniture, UNIT_BOX, flagMats[i], `env-flag-${i}`, x + 0.9, 6.9, fzFlag, { kind: 'flag' }).scale.set(1.7, 1.05, 0.05);
  });

  // ---------------------------------------------------------------- main road (z[52,60], at the gate)
  put(gRoads, UNIT_BOX, M.asphalt, 'env-road-main', 0, -0.05, 56, { cast: false, recv: true, kind: 'road' }).scale.set(340, 0.12, 8);
  put(gRoads, UNIT_BOX, M.slab, 'env-sidewalk-near', 0, -0.03, 49, { cast: false, recv: true, kind: 'sidewalk' }).scale.set(340, 0.18, 6);
  put(gRoads, UNIT_BOX, M.slab, 'env-sidewalk-far', 0, -0.03, 62, { cast: false, recv: true, kind: 'sidewalk' }).scale.set(340, 0.18, 4);

  // Lane markings: dashed yellow centre line, solid white edges, zebra crossing at x=0.
  const dashItems = [];
  for (let x = -168; x <= 168; x += 6) dashItems.push({ p: [x, 0.025, 56], s: [2.6, 0.02, 0.18] });
  inst(gRoads, UNIT_BOX, paintYellow, 'env-road-centre-dashes', dashItems, { cast: false, kind: 'marking' });
  const edgeItems = [
    { p: [0, 0.025, 52.5], s: [340, 0.02, 0.15] },
    { p: [0, 0.025, 59.5], s: [340, 0.02, 0.15] },
  ];
  inst(gRoads, UNIT_BOX, paintWhite, 'env-road-edge-lines', edgeItems, { cast: false, kind: 'marking' });
  const zebraItems = [];
  for (let i = 0; i < 8; i++) zebraItems.push({ p: [0, 0.028, 52.7 + i * 0.95], s: [3.2, 0.02, 0.55] });
  inst(gRoads, UNIT_BOX, paintWhite, 'env-crosswalk', zebraItems, { cast: false, kind: 'marking' });

  // ---------------------------------------------------------------- near trees only (plaza rows)
  const treeSpots = [];
  const addTree = (x, z, s) => treeSpots.push({ x, z, s: s === undefined ? rr(0.85, 1.3) : s });
  for (const sx of [-plazaHalf + 6, plazaHalf - 6]) {
    for (const z of [hd + 4, hd + 11, hd + 18]) addTree(sx, z, rr(0.9, 1.1));
  }
  const trunkItems = [], canopyItems = [];
  const canopyTints = [0xffffff, 0xd9efd0, 0xc4e2b8, 0xeef7dd, 0xb8d9a8]; // multiplies materials.grass
  for (const t of treeSpots) {
    const s = t.s;
    trunkItems.push({ p: [t.x, 1.3 * s, t.z], r: [0, rr(0, TAU), 0], s: [s, s, s] });
    canopyItems.push({ p: [t.x, 3.9 * s, t.z], r: [rr(0, TAU), rr(0, TAU), 0], s: [1.95 * s, 2.15 * s, 1.95 * s], c: pick(canopyTints) });
    canopyItems.push({
      p: [t.x + rr(-0.8, 0.8) * s, 3.2 * s, t.z + rr(-0.8, 0.8) * s],
      r: [rr(0, TAU), rr(0, TAU), 0], s: [1.35 * s, 1.4 * s, 1.35 * s], c: pick(canopyTints),
    });
  }
  inst(gVeg, new T.CylinderGeometry(0.16, 0.26, 2.6, 6), M.wood, 'env-tree-trunks', trunkItems, { kind: 'tree' });
  inst(gVeg, new T.IcosahedronGeometry(1, 1), M.grass, 'env-tree-canopies', canopyItems, { kind: 'tree' });

  // ---------------------------------------------------------------- near street lamps only
  const poleItems = [], armItems = [], headItems = [];
  function lamp(x, z, h, armDir) {
    poleItems.push({ p: [x, h / 2, z], s: [1, h, 1] });
    if (armDir) {
      armItems.push({ p: [x, h - 0.12, z + armDir * 0.7], s: [0.09, 0.09, 1.5] });
      headItems.push({ p: [x, h - 0.18, z + armDir * 1.45], s: [0.62, 0.14, 0.3] });
    } else {
      headItems.push({ p: [x, h + 0.05, z], s: [0.42, 0.2, 0.42] });
    }
  }
  for (const x of [-60, -20, 20, 60]) lamp(x, 50.2, 5.4, 1);
  for (const sx of [-plazaHalf + 3, plazaHalf - 3]) for (const sz of [hd + 2, plazaFarZ - 4]) lamp(sx, sz, 4.2, 0);
  inst(gFurniture, new T.CylinderGeometry(0.08, 0.12, 1, 6), M.steel, 'env-lamp-poles', poleItems, { kind: 'lamp' });
  inst(gFurniture, UNIT_BOX, M.steel, 'env-lamp-arms', armItems, { kind: 'lamp' });
  inst(gFurniture, UNIT_BOX, lampGlow, 'env-lamp-heads', headItems, { cast: false, kind: 'lamp' });

  // ---------------------------------------------------------------- gradient sky dome
  const skyMat = own(new T.ShaderMaterial({
    uniforms: {
      topColor: { value: new T.Color(0x2c6fbb) },
      horizonColor: { value: new T.Color(0xd9ecf4) },
      exponent: { value: 0.82 },
    },
    vertexShader: [
      'varying vec3 vWorld;',
      'void main() {',
      '  vec4 wp = modelMatrix * vec4(position, 1.0);',
      '  vWorld = wp.xyz;',
      '  gl_Position = projectionMatrix * viewMatrix * wp;',
      '}',
    ].join('\n'),
    fragmentShader: [
      'uniform vec3 topColor;',
      'uniform vec3 horizonColor;',
      'uniform float exponent;',
      'varying vec3 vWorld;',
      'void main() {',
      '  float h = normalize(vWorld).y;',
      '  float t = pow(max(h, 0.0), exponent);',
      '  gl_FragColor = vec4(mix(horizonColor, topColor, t), 1.0);',
      '  #include <tonemapping_fragment>',
      '  #include <colorspace_fragment>',
      '}',
    ].join('\n'),
    side: T.BackSide,
    depthWrite: false,
    fog: false,
  }));
  const sky = put(gSky, new T.SphereGeometry(480, 24, 12), skyMat, 'env-sky-dome', 0, 0, 0, { cast: false, kind: 'sky' });
  sky.renderOrder = -1;

  // ---------------------------------------------------------------- stats + wiring
  let triangles = 0;
  group.traverse((o) => {
    if (!o.isMesh || !o.geometry) return;
    const g = o.geometry;
    const per = g.index ? g.index.count / 3 : (g.attributes.position ? g.attributes.position.count / 3 : 0);
    triangles += per * (o.isInstancedMesh ? o.count : 1);
  });
  group.userData = {
    module: 'environment',
    stats: {
      trees: treeSpots.length,
      cars: 0,
      contextBuildings: 0,
      pavingTiles: tileItems.length,
      triangles: Math.round(triangles),
    },
  };
  if (ctx.root && ctx.root.add) ctx.root.add(group);

  // ---------------------------------------------------------------- dispose
  let disposed = false;
  function dispose() {
    if (disposed) return;
    disposed = true;
    const geos = new Set();
    group.traverse((o) => {
      if (!o.isMesh) return;
      if (o.geometry) geos.add(o.geometry);
      if (o.isInstancedMesh && typeof o.dispose === 'function') o.dispose();
    });
    geos.forEach((g) => g.dispose());
    owned.forEach((m) => m.dispose());
    group.removeFromParent();
  }

  return { group, dispose };
}
