// environment.js — cityscape around AEGIS Tower B01 (main building stays at origin).
// Stone plaza + fountain, road with markings, parking lot with instanced block cars,
// instanced trees (trunk + canopy), lawns/hedges, pond with pier, low-rise context
// buildings (glass/concrete), street furniture and a gradient sky dome.
// Contract: build(ctx) -> { group, dispose }.  All repeated elements are InstancedMesh;
// shared geometries/materials come from ctx (config.js / materials.js) when provided.
import * as THREE from 'three';
import { BUILDING, totalHeight } from './config.js';
import { makeMaterials } from './materials.js';

const TAU = Math.PI * 2;

// Deterministic PRNG so the landscape is identical on every load.
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
  const gParking = sub('env-parking');
  const gVeg = sub('env-vegetation');
  const gWater = sub('env-water');
  const gContext = sub('env-context');
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

  // Local paint / detail materials (library has no paint, bark is wood, car paint…).
  const paintWhite = own(new T.MeshStandardMaterial({ color: 0xe9eaec, roughness: 0.55, metalness: 0 }));
  const paintYellow = own(new T.MeshStandardMaterial({ color: 0xd8b84a, roughness: 0.55, metalness: 0 }));
  const carPaint = own(new T.MeshStandardMaterial({ color: 0xffffff, roughness: 0.35, metalness: 0.65 }));
  const carDark = own(new T.MeshStandardMaterial({ color: 0x1c2126, roughness: 0.85, metalness: 0.1 }));
  const flagBlue = own(new T.MeshStandardMaterial({ color: 0x2563eb, roughness: 0.6, metalness: 0 }));
  const lampGlow = own(M.emissive(0xffe6b0, 0.9));
  const terrainFar = own(new T.MeshStandardMaterial({ color: 0xa9bfa2, roughness: 1, metalness: 0 }));

  const hw = B.width / 2, hd = B.depth / 2; // tower footprint 44 x 30, centred at origin

  // ---------------------------------------------------------------- ground
  put(gGround, new T.PlaneGeometry(700, 700), M.grass, 'env-lawn', 0, -0.06, 0,
    { rx: -Math.PI / 2, cast: false, recv: true, kind: 'grass' });
  put(gGround, new T.CircleGeometry(478, 48), terrainFar, 'env-distant-terrain', 0, -0.16, 0,
    { rx: -Math.PI / 2, cast: false, kind: 'terrain' });

  // ---------------------------------------------------------------- plaza (in front of entrance, +Z)
  // Plaza slab x[-34,34] z[hd,46]; paving tiles instanced on top, grout = dark base.
  const plazaW = 68, plazaD = 46 - hd, plazaCz = (hd + 46) / 2;
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
  const fx = 0, fz = 33;
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
  for (const sx of [-31.5, 31.5]) for (const sz of [17.5, 44]) {
    planterItems.push({ p: [sx, 0.35, sz], s: [3, 0.7, 3] });
    hedgeTopItems.push({ p: [sx, 0.95, sz], s: [2.6, 0.55, 2.6] });
  }
  inst(gPlaza, UNIT_BOX, M.concrete, 'env-planters', planterItems, { recv: true, kind: 'planter' });
  inst(gVeg, UNIT_BOX, M.grass, 'env-planter-hedges', hedgeTopItems, { kind: 'hedge' });
  for (const sx of [-34.9, 34.9]) {
    put(gVeg, UNIT_BOX, M.grass, 'env-hedge-strip', sx, 0.36, plazaCz, { kind: 'hedge' }).scale.set(0.9, 0.72, 29);
  }

  // Bollards along the plaza edge (gap on the entrance/crosswalk axis).
  const bollardItems = [];
  for (let x = -32; x <= 32; x += 4) {
    if (Math.abs(x) <= 4) continue;
    bollardItems.push({ p: [x, 0.45, 45.6], s: [1, 1, 1] });
  }
  inst(gFurniture, new T.CylinderGeometry(0.11, 0.13, 0.85, 8), M.steel, 'env-bollards', bollardItems, { kind: 'bollard' });

  // Flagpoles near the entrance.
  const flagMats = [M.fireRed, flagBlue, M.exitSign];
  [-10, -13, -16].forEach((x, i) => {
    put(gFurniture, new T.CylinderGeometry(0.05, 0.07, 7.5, 6), M.steel, `env-flagpole-${i}`, x, 3.75, 19.5, { kind: 'flagpole' });
    put(gFurniture, UNIT_BOX, flagMats[i], `env-flag-${i}`, x + 0.9, 6.9, 19.5, { kind: 'flag' }).scale.set(1.7, 1.05, 0.05);
  });

  // ---------------------------------------------------------------- roads
  // Main road along X: z[52,60], two lanes; sidewalks z[46,52] & z[60,64].
  put(gRoads, UNIT_BOX, M.asphalt, 'env-road-main', 0, -0.05, 56, { cast: false, recv: true, kind: 'road' }).scale.set(340, 0.12, 8);
  // Near sidewalk split to leave the parking driveway opening at x[-75,-67].
  put(gRoads, UNIT_BOX, M.slab, 'env-sidewalk-near-west', -122.5, -0.03, 49, { cast: false, recv: true, kind: 'sidewalk' }).scale.set(95, 0.18, 6);
  put(gRoads, UNIT_BOX, M.slab, 'env-sidewalk-near-east', 51.5, -0.03, 49, { cast: false, recv: true, kind: 'sidewalk' }).scale.set(237, 0.18, 6);
  put(gRoads, UNIT_BOX, M.slab, 'env-sidewalk-far', 0, -0.03, 62, { cast: false, recv: true, kind: 'sidewalk' }).scale.set(340, 0.18, 4);
  put(gRoads, UNIT_BOX, M.asphalt, 'env-parking-driveway', -71, -0.05, 49, { cast: false, recv: true, kind: 'driveway' }).scale.set(8, 0.12, 6.4);

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

  // ---------------------------------------------------------------- parking lot (west of plaza)
  // Lot x[-92,-50] z[16,46]; two stall rows (2.7 x 5.5 m) facing a central aisle.
  put(gParking, UNIT_BOX, M.asphalt, 'env-parking-asphalt', -71, -0.05, 31, { cast: false, recv: true, kind: 'parking' }).scale.set(42, 0.12, 30);
  const stallLineItems = [];
  for (let i = 0; i <= 14; i++) {
    const x = -90 + i * 2.7;
    stallLineItems.push({ p: [x, 0.025, 36.75], s: [0.12, 0.02, 5.5] });
    stallLineItems.push({ p: [x, 0.025, 25.25], s: [0.12, 0.02, 5.5] });
  }
  stallLineItems.push({ p: [-71.1, 0.025, 39.5], s: [37.9, 0.02, 0.12] });
  stallLineItems.push({ p: [-71.1, 0.025, 22.5], s: [37.9, 0.02, 0.12] });
  inst(gParking, UNIT_BOX, paintWhite, 'env-parking-stall-lines', stallLineItems, { cast: false, kind: 'marking' });

  // ---------------------------------------------------------------- cars (instanced blocks: base + body + cabin)
  const carPalette = [0xf2f4f6, 0x22262c, 0xb9c0c7, 0xc23b3b, 0x2f5f9f, 0x707880, 0xd8d2c4];
  const carBaseItems = [], carBodyItems = [], carCabinItems = [];
  const tmpColor = new T.Color();
  function addCar(x, z, ry, cabinOff) {
    const c = pick(carPalette);
    const cabinHex = tmpColor.set(c).multiplyScalar(0.82).getHex();
    carBaseItems.push({ p: [x, 0.22, z], r: [0, ry, 0], s: [1.9, 0.42, 4.5] });
    carBodyItems.push({ p: [x, 0.66, z], r: [0, ry, 0], s: [1.85, 0.6, 4.4], c });
    const ox = Math.sin(ry) * cabinOff, oz = Math.cos(ry) * cabinOff;
    carCabinItems.push({ p: [x + ox, 1.2, z + oz], r: [0, ry, 0], s: [1.65, 0.58, 2.2], c: cabinHex });
  }
  for (let i = 0; i < 14; i++) {
    const x = -88.65 + i * 2.7;
    if (rand() < 0.58) addCar(x, 36.75, 0, 0.4);   // north row, rear toward +Z
    if (rand() < 0.58) addCar(x, 25.25, 0, -0.4);  // south row, rear toward -Z
  }
  addCar(-62, 54.1, Math.PI / 2, 0.4);  // traffic on the main road
  addCar(38, 54.1, Math.PI / 2, 0.4);
  addCar(-15, 57.9, -Math.PI / 2, 0.4);
  addCar(112, 57.9, -Math.PI / 2, 0.4);
  inst(gParking, UNIT_BOX, carDark, 'env-car-bases', carBaseItems, { kind: 'car' });
  inst(gParking, UNIT_BOX, carPaint, 'env-car-bodies', carBodyItems, { kind: 'car' });
  inst(gParking, UNIT_BOX, carPaint, 'env-car-cabins', carCabinItems, { kind: 'car' });

  // ---------------------------------------------------------------- pond (east lawn)
  const pondX = 62, pondZ = -12, pondRx = 17, pondRz = 12;
  const pondShape = new T.Shape();
  pondShape.absellipse(0, 0, pondRx, pondRz, 0, TAU, false, 0);
  const pondGeo = new T.ShapeGeometry(pondShape, 32);
  put(gWater, pondGeo, M.concreteDark, 'env-pond-bed', pondX, -0.3, pondZ, { rx: -Math.PI / 2, cast: false, kind: 'pond' });
  put(gWater, pondGeo, M.water, 'env-pond-water', pondX, 0.05, pondZ, { rx: -Math.PI / 2, cast: false, kind: 'water' });
  const rim = put(gWater, new T.RingGeometry(16.4, 17.9, 40), M.concreteDark, 'env-pond-rim', pondX, 0.08, pondZ,
    { rx: -Math.PI / 2, cast: false, recv: true, kind: 'pond' });
  rim.scale.set(1, pondRz / pondRx, 1);
  // Rocks around the rim + a small wooden pier on the west edge.
  const rockItems = [];
  for (let i = 0; i < 7; i++) {
    const a = (i / 7) * TAU + rr(-0.2, 0.2);
    const s = rr(0.5, 1.05);
    rockItems.push({
      p: [pondX + (pondRx + 1.2) * Math.cos(a), 0.18 * s, pondZ + (pondRz + 1.2) * Math.sin(a)],
      r: [rr(0, TAU), rr(0, TAU), 0], s: [s, s * 0.75, s],
    });
  }
  inst(gWater, new T.DodecahedronGeometry(1, 0), M.concrete, 'env-pond-rocks', rockItems, { kind: 'rock' });
  put(gWater, UNIT_BOX, M.wood, 'env-pond-pier', 47.8, 0.22, pondZ, { recv: true, kind: 'pier' }).scale.set(5.5, 0.14, 2.2);
  for (const dz of [-0.9, 0.9]) {
    put(gWater, new T.CylinderGeometry(0.09, 0.09, 0.75, 6), M.wood, 'env-pier-post', 49.9, -0.05, pondZ + dz, { kind: 'pier' });
  }

  // ---------------------------------------------------------------- trees (instanced trunk + 2 canopy blobs)
  const treeSpots = [];
  const addTree = (x, z, s) => treeSpots.push({ x, z, s: s === undefined ? rr(0.85, 1.3) : s });
  for (const sx of [-27, 27]) for (const z of [22, 28, 34, 40]) addTree(sx, z, rr(0.9, 1.1)); // plaza rows
  for (const x of [-145, -120, -95, -45, -20, 20, 45, 70, 95, 120, 145]) addTree(x, 49.2);   // near sidewalk
  for (const x of [-130, -100, -60, -30, 30, 60, 100, 130]) addTree(x, 63);                  // far sidewalk
  for (let i = 0; i < 8; i++) {                                                              // pond ring
    const a = (i / 8) * TAU + 0.35;
    addTree(pondX + 22.5 * Math.cos(a), pondZ + 17 * Math.sin(a));
  }
  for (const z of [18, 27, 36, 44]) addTree(-95.5, z);                                       // parking west edge
  for (const z of [20, 32, 44]) addTree(-44, z);                                             // parking east edge
  for (const [x, z] of [[38, -40], [52, -46], [82, -38], [92, -18], [36, 22], [90, 12]]) addTree(x, z); // east lawn
  for (const [x, z] of [[-105, -46], [-128, -34], [-136, 2], [-120, 14], [-104, 38]]) addTree(x, z);   // west lawn

  // Context buildings defined before scatter so their footprints block tree placement.
  const contextBuildings = [
    { x: -95, z: 88, w: 26, d: 18, h: 14, mat: 'concrete' },
    { x: -52, z: 82, w: 20, d: 16, h: 10, mat: 'wall' },
    { x: -14, z: 90, w: 24, d: 20, h: 18, mat: 'glass' },
    { x: 26, z: 84, w: 18, d: 16, h: 12, mat: 'concrete' },
    { x: 62, z: 92, w: 28, d: 18, h: 16, mat: 'wall' },
    { x: 104, z: 86, w: 22, d: 16, h: 11, mat: 'concrete' },
    { x: 105, z: 10, w: 20, d: 24, h: 13, mat: 'glass' },
    { x: 112, z: -32, w: 24, d: 18, h: 9, mat: 'concrete' },
    { x: -112, z: -18, w: 22, d: 20, h: 15, mat: 'wall' },
    { x: -118, z: 30, w: 18, d: 16, h: 10, mat: 'concrete' },
    { x: -40, z: -72, w: 26, d: 18, h: 12, mat: 'concrete' },
    { x: 8, z: -78, w: 22, d: 20, h: 17, mat: 'glass' },
    { x: 55, z: -68, w: 24, d: 16, h: 10, mat: 'wall' },
  ];

  // Seeded scatter on the back lawn, rejected against hardscape/buildings/pond.
  const blocked = [
    [-hw - 4, hw + 4, -hd - 4, hd + 4],
    [-37, 37, hd - 2, 48],
    [-175, 175, 45, 67],
    [-95, -47, 13, 49],
    [-78, -64, 44, 54],
  ];
  for (const b of contextBuildings) blocked.push([b.x - b.w / 2 - 4, b.x + b.w / 2 + 4, b.z - b.d / 2 - 4, b.z + b.d / 2 + 4]);
  const isBlocked = (x, z) =>
    blocked.some((r) => x > r[0] && x < r[1] && z > r[2] && z < r[3]) ||
    ((x - pondX) / (pondRx + 4)) ** 2 + ((z - pondZ) / (pondRz + 4)) ** 2 < 1;
  let placed = 0, tries = 0;
  while (placed < 14 && tries < 400) {
    tries++;
    const x = rr(-135, 135), z = rr(-92, -24);
    if (isBlocked(x, z)) continue;
    if (treeSpots.some((t) => (t.x - x) ** 2 + (t.z - z) ** 2 < 36)) continue;
    addTree(x, z); placed++;
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

  // ---------------------------------------------------------------- context buildings (low-rise backdrop)
  const bodyMats = { concrete: M.concrete, wall: M.wall, glass: M.glassDark };
  const bandItems = [], roofItems = [], plinthItems = [], hvacItems = [];
  contextBuildings.forEach((b, i) => {
    put(gContext, UNIT_BOX, bodyMats[b.mat], `env-context-body-${i}`, b.x, b.h / 2, b.z,
      { recv: true, kind: 'context-building' }).scale.set(b.w, b.h, b.d);
    plinthItems.push({ p: [b.x, 0.25, b.z], s: [b.w + 0.6, 0.5, b.d + 0.6] });
    roofItems.push({ p: [b.x, b.h + 0.17, b.z], s: [b.w + 0.4, 0.35, b.d + 0.4] });
    hvacItems.push({ p: [b.x + rr(-b.w / 4, b.w / 4), b.h + 0.8, b.z + rr(-b.d / 4, b.d / 4)], s: [rr(1.5, 3), 0.9, rr(1.2, 2.4)] });
    if (b.mat !== 'glass') { // window bands on all four faces, one per storey
      const n = Math.max(2, Math.round(b.h / 3.4));
      for (let f = 0; f < n; f++) {
        const y = (f + 0.5) * (b.h / n) + 0.2;
        bandItems.push({ p: [b.x, y, b.z + b.d / 2 + 0.08], s: [b.w * 0.9, 1.25, 0.14] });
        bandItems.push({ p: [b.x, y, b.z - b.d / 2 - 0.08], s: [b.w * 0.9, 1.25, 0.14] });
        bandItems.push({ p: [b.x + b.w / 2 + 0.08, y, b.z], s: [0.14, 1.25, b.d * 0.85] });
        bandItems.push({ p: [b.x - b.w / 2 - 0.08, y, b.z], s: [0.14, 1.25, b.d * 0.85] });
      }
    }
  });
  inst(gContext, UNIT_BOX, M.glassDark, 'env-context-window-bands', bandItems, { cast: false, kind: 'context-building' });
  inst(gContext, UNIT_BOX, M.slab, 'env-context-roofs', roofItems, { kind: 'context-building' });
  inst(gContext, UNIT_BOX, M.concreteDark, 'env-context-plinths', plinthItems, { kind: 'context-building' });
  inst(gContext, UNIT_BOX, M.concreteDark, 'env-context-hvac', hvacItems, { kind: 'context-building' });

  // ---------------------------------------------------------------- street lamps
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
  for (const x of [-140, -100, -60, -20, 20, 60, 100, 140]) lamp(x, 50.2, 5.4, 1);
  for (const x of [-80, 0, 80]) lamp(x, 61.8, 5.4, -1);
  for (const sx of [-31, 31]) for (const sz of [21, 41]) lamp(sx, sz, 4.2, 0);
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
      cars: carBodyItems.length,
      contextBuildings: contextBuildings.length,
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
