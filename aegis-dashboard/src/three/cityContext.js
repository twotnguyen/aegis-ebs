// cityContext.js — outer urban context around AEGIS Tower B01.
// Complements environment.js (which owns the plaza, main road z[52,60], parking,
// pond, low-rise backdrop and sky dome): this module adds the far city —
//   * instanced high-rise towers (glass / concrete, faint emissive windows),
//   * an outer road grid (z = ±120, x = ±160) with lane markings + crosswalks,
//   * instanced traffic, two parks with trees, roadside trees,
//   * a hazy two-ring skyline silhouette inside the sky dome.
// The tower field is rejection-sampled so NOTHING overlaps the main building
// footprint + plaza (keep-clear around origin) or any environment.js feature.
// Contract: build(ctx) -> { group, dispose }.  Repeated elements are InstancedMesh;
// shared materials come from ctx.materials (materials.js) and are never disposed here.
import * as THREE from 'three';
import { BUILDING } from './config.js';
import { makeMaterials } from './materials.js';

const TAU = Math.PI * 2;

// Deterministic PRNG — identical city on every load.
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

  // Only materials created here (or the whole fallback library) are ours to dispose.
  const owned = [];
  const own = (m) => { owned.push(m); return m; };
  if (ownsLib) for (const v of Object.values(M)) if (v && v.isMaterial) owned.push(v);

  const rand = mulberry32(0xC17C2026);
  const rr = (a, b) => a + (b - a) * rand();
  const pick = (arr) => arr[Math.floor(rand() * arr.length)];

  const group = new T.Group();
  group.name = 'cityContext';
  const sub = (name) => { const g = new T.Group(); g.name = name; group.add(g); return g; };
  const gRoads = sub('city-roads');
  const gTraffic = sub('city-traffic');
  const gTowers = sub('city-towers');
  const gParks = sub('city-parks');
  const gVeg = sub('city-vegetation');
  const gSkyline = sub('city-skyline');

  // ---------------------------------------------------------------- helpers
  const UNIT_BOX = new T.BoxGeometry(1, 1, 1); // shared by every scaled-box instance set

  function put(parent, geo, mat, name, x, y, z, o = {}) {
    const m = new T.Mesh(geo, mat);
    m.name = name;
    m.position.set(x, y, z);
    if (o.rx) m.rotation.x = o.rx;
    if (o.ry) m.rotation.y = o.ry;
    m.castShadow = o.cast !== false;
    m.receiveShadow = !!o.recv;
    m.userData = { module: 'cityContext', kind: o.kind || name };
    parent.add(m);
    return m;
  }

  // items: { p:[x,y,z], r:[rx,ry,rz], s:[sx,sy,sz], c:hex }
  function inst(parent, geo, mat, name, items, o = {}) {
    if (!items.length) return null;
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
    im.userData = { module: 'cityContext', kind: o.kind || name, instances: items.length };
    parent.add(im);
    return im;
  }

  // Local paint/detail materials (library has no road paint / car paint / haze).
  const paintWhite = own(new T.MeshStandardMaterial({ color: 0xe9eaec, roughness: 0.55, metalness: 0 }));
  const paintYellow = own(new T.MeshStandardMaterial({ color: 0xd8b84a, roughness: 0.55, metalness: 0 }));
  const carPaint = own(new T.MeshStandardMaterial({ color: 0xffffff, roughness: 0.35, metalness: 0.65 }));
  const carDark = own(new T.MeshStandardMaterial({ color: 0x1c2126, roughness: 0.85, metalness: 0.1 }));
  const winWarm = own(M.emissive(0xffd9a0, 0.5));   // faint lit office windows
  const winCool = own(M.emissive(0xd6e9ff, 0.38));  // faint curtain-wall floor glow
  const beaconRed = own(M.emissive(0xff4433, 1.3)); // aviation warning on tall roofs
  // Baked-haze silhouette materials (no lights/fog needed; sky dome horizon ≈ 0xd9ecf4).
  const hazeNear = own(new T.MeshBasicMaterial({ color: 0x93a9bd, transparent: true, opacity: 0.62, fog: false, depthWrite: false }));
  const hazeFar = own(new T.MeshBasicMaterial({ color: 0xb7c9d8, transparent: true, opacity: 0.45, fog: false, depthWrite: false }));

  const hw = B.width / 2, hd = B.depth / 2; // tower footprint centred at origin

  // ---------------------------------------------------------------- exclusion map
  // Rects [x0,x1,z0,z1] that tower footprints (expanded by a margin) must avoid:
  // main building + plaza, environment.js roads/parking/low-rises, this module's
  // own road corridors and parks. Keeps the origin clear for BUILDING + plaza.
  const EX = [
    [-hw - 20, hw + 20, -hd - 12, 50],          // AEGIS tower + plaza + margin
    [-180, 180, 44, 66],                        // env main road + sidewalks
    [-97, -45, 11, 51],                         // env parking lot + driveway
    [-264, 264, 109, 131],                      // city road z=+120 corridor
    [-264, 264, -131, -109],                    // city road z=-120 corridor
    [149, 171, -264, 264],                      // city road x=+160 corridor
    [-171, -149, -264, 264],                    // city road x=-160 corridor
  ];
  // environment.js low-rise context buildings (x, z, w, d) + margin.
  const ENV_LOWRISE = [
    [-95, 88, 26, 18], [-52, 82, 20, 16], [-14, 90, 24, 20], [26, 84, 18, 16],
    [62, 92, 28, 18], [104, 86, 22, 16], [105, 10, 20, 24], [112, -32, 24, 18],
    [-112, -18, 22, 20], [-118, 30, 18, 16], [-40, -72, 26, 18], [8, -78, 22, 20],
    [55, -68, 24, 16],
  ];
  for (const [x, z, w, d] of ENV_LOWRISE) EX.push([x - w / 2 - 5, x + w / 2 + 5, z - d / 2 - 5, z + d / 2 + 5]);
  const POND = { x: 62, z: -12, rx: 23, rz: 18 }; // env pond + margin (ellipse test)

  const rectsOverlap = (a, b) => a[0] < b[1] && a[1] > b[0] && a[2] < b[3] && a[3] > b[2];
  const hitsPond = (r) => {
    const px = Math.max(r[0], Math.min(POND.x, r[1]));
    const pz = Math.max(r[2], Math.min(POND.z, r[3]));
    return ((px - POND.x) / POND.rx) ** 2 + ((pz - POND.z) / POND.rz) ** 2 < 1;
  };
  const isBlocked = (r) => EX.some((e) => rectsOverlap(r, e)) || hitsPond(r);

  // ---------------------------------------------------------------- parks (placed first; block towers)
  const parks = [
    { x: 214, z: 62, w: 62, d: 50, pond: true },
    { x: -214, z: -62, w: 62, d: 50, pond: false },
  ];
  const treeSpots = []; // { x, z, s, far }
  const addTree = (x, z, s, far) => treeSpots.push({ x, z, s, far: !!far });

  const parkPond = { x: parks[0].x + 12, z: parks[0].z - 9, r: 5.5 };
  parks.forEach((p, pi) => {
    EX.push([p.x - p.w / 2 - 4, p.x + p.w / 2 + 4, p.z - p.d / 2 - 4, p.z + p.d / 2 + 4]);
    put(gParks, new T.PlaneGeometry(p.w, p.d), M.grass, `city-park-${pi}-lawn`, p.x, 0.02, p.z,
      { rx: -Math.PI / 2, cast: false, recv: true, kind: 'park' });
    // Crossing concrete paths.
    put(gParks, UNIT_BOX, M.concrete, `city-park-${pi}-path-x`, p.x, 0.05, p.z,
      { cast: false, recv: true, kind: 'park' }).scale.set(p.w - 8, 0.1, 2.6);
    put(gParks, UNIT_BOX, M.concrete, `city-park-${pi}-path-z`, p.x, 0.05, p.z,
      { cast: false, recv: true, kind: 'park' }).scale.set(2.6, 0.1, p.d - 8);
    if (p.pond) {
      put(gParks, new T.CircleGeometry(parkPond.r + 0.5, 24), M.concreteDark, 'city-park-pond-bed',
        parkPond.x, 0.03, parkPond.z, { rx: -Math.PI / 2, cast: false, kind: 'park' });
      put(gParks, new T.CircleGeometry(parkPond.r, 24), M.water, 'city-park-pond-water',
        parkPond.x, 0.09, parkPond.z, { rx: -Math.PI / 2, cast: false, kind: 'park' });
    }
    // Benches flanking the east-west path.
    const seatItems = [], backItems = [];
    for (const dx of [-16, -8, 8, 16]) {
      const bz = p.z + (dx < 0 ? 2.3 : -2.3);
      seatItems.push({ p: [p.x + dx, 0.46, bz], s: [2.2, 0.12, 0.62] });
      backItems.push({ p: [p.x + dx, 0.78, bz + (dx < 0 ? 0.28 : -0.28)], s: [2.2, 0.55, 0.1] });
    }
    inst(gParks, UNIT_BOX, M.wood, `city-park-${pi}-bench-seats`, seatItems, { kind: 'bench' });
    inst(gParks, UNIT_BOX, M.wood, `city-park-${pi}-bench-backs`, backItems, { kind: 'bench' });
    // Park trees, rejected against paths / pond / each other.
    let placed = 0, tries = 0;
    while (placed < 13 && tries < 300) {
      tries++;
      const x = p.x + rr(-p.w / 2 + 4, p.w / 2 - 4);
      const z = p.z + rr(-p.d / 2 + 4, p.d / 2 - 4);
      if (Math.abs(x - p.x) < 3.2 || Math.abs(z - p.z) < 3.2) continue;
      if (p.pond && (x - parkPond.x) ** 2 + (z - parkPond.z) ** 2 < (parkPond.r + 3) ** 2) continue;
      if (treeSpots.some((t) => (t.x - x) ** 2 + (t.z - z) ** 2 < 25)) continue;
      addTree(x, z, rr(0.95, 1.45), false); placed++;
    }
  });

  // ---------------------------------------------------------------- outer road grid
  // Two east-west avenues (z = ±120) + two north-south streets (x = ±160),
  // crossing the env main road at (±160, 56). Same section as env roads:
  // asphalt 8 m wide, 3 m sidewalks, dashed yellow centre + white edge lines.
  const ROAD_LEN = 524, ROAD_W = 8, WALK_W = 3;
  const H_ROADS = [120, -120], V_ROADS = [160, -160];
  for (const z of H_ROADS) {
    put(gRoads, UNIT_BOX, M.asphalt, `city-road-h-${z}`, 0, -0.05, z,
      { cast: false, recv: true, kind: 'road' }).scale.set(ROAD_LEN, 0.12, ROAD_W);
    for (const side of [-1, 1]) {
      put(gRoads, UNIT_BOX, M.slab, `city-walk-h-${z}-${side}`, 0, -0.03, z + side * (ROAD_W / 2 + WALK_W / 2),
        { cast: false, recv: true, kind: 'sidewalk' }).scale.set(ROAD_LEN, 0.18, WALK_W);
    }
  }
  for (const x of V_ROADS) {
    put(gRoads, UNIT_BOX, M.asphalt, `city-road-v-${x}`, x, -0.05, 0,
      { cast: false, recv: true, kind: 'road' }).scale.set(ROAD_W, 0.12, ROAD_LEN);
    for (const side of [-1, 1]) {
      put(gRoads, UNIT_BOX, M.slab, `city-walk-v-${x}-${side}`, x + side * (ROAD_W / 2 + WALK_W / 2), -0.03, 0,
        { cast: false, recv: true, kind: 'sidewalk' }).scale.set(WALK_W, 0.18, ROAD_LEN);
    }
  }

  // Lane markings (instanced). Dashes break at intersections and crosswalks.
  const nearAny = (v, list, gap) => list.some((c) => Math.abs(v - c) < gap);
  const dashItems = [], edgeItems = [], zebraItems = [];
  for (const z of H_ROADS) {
    for (let x = -256; x <= 256; x += 7) {
      if (nearAny(x, V_ROADS, 11) || Math.abs(x) < 6) continue;
      dashItems.push({ p: [x, 0.025, z], s: [2.8, 0.02, 0.18] });
    }
    for (const side of [-1, 1]) edgeItems.push({ p: [0, 0.025, z + side * 3.6], s: [ROAD_LEN, 0.02, 0.15] });
    for (let i = 0; i < 8; i++) zebraItems.push({ p: [0, 0.028, z - 3.3 + i * 0.95], s: [3.2, 0.02, 0.55] });
  }
  for (const x of V_ROADS) {
    for (let z = -256; z <= 256; z += 7) {
      if (nearAny(z, H_ROADS, 11) || Math.abs(z - 56) < 11) continue; // incl. env main road
      dashItems.push({ p: [x, 0.025, z], r: [0, Math.PI / 2, 0], s: [2.8, 0.02, 0.18] });
    }
    for (const side of [-1, 1]) edgeItems.push({ p: [x + side * 3.6, 0.025, 0], s: [0.15, 0.02, ROAD_LEN] });
  }
  inst(gRoads, UNIT_BOX, paintYellow, 'city-road-centre-dashes', dashItems, { cast: false, kind: 'marking' });
  inst(gRoads, UNIT_BOX, paintWhite, 'city-road-edge-lines', edgeItems, { cast: false, kind: 'marking' });
  inst(gRoads, UNIT_BOX, paintWhite, 'city-crosswalks', zebraItems, { cast: false, kind: 'marking' });

  // ---------------------------------------------------------------- roadside trees (far greenery)
  for (const z of H_ROADS) {
    const tz = z + Math.sign(z) * 5.5; // outer sidewalk only
    for (let x = -238; x <= 238; x += 40) {
      if (nearAny(x, V_ROADS, 14) || Math.abs(x) < 8) continue;
      addTree(x + rr(-2, 2), tz, rr(0.8, 1.15), true);
    }
  }
  for (const x of V_ROADS) {
    const tx = x + Math.sign(x) * 5.5;
    for (let z = -238; z <= 238; z += 40) {
      if (nearAny(z, H_ROADS, 14) || Math.abs(z - 56) < 14) continue;
      addTree(tx, z + rr(-2, 2), rr(0.8, 1.15), true);
    }
  }

  // ---------------------------------------------------------------- high-rise tower field
  // Districts lie beyond the road grid; every footprint is rejected against the
  // exclusion map and previously placed towers, so the origin (BUILDING + plaza)
  // and all environment.js features stay untouched.
  const districts = [
    { x0: -252, x1: 252, z0: 134, z1: 258, n: 14, tall: true },  // north downtown
    { x0: -252, x1: 252, z0: -258, z1: -134, n: 12, tall: false }, // south
    { x0: 174, x1: 258, z0: -98, z1: 98, n: 9, tall: true },     // east
    { x0: -258, x1: -174, z0: -98, z1: 98, n: 9, tall: false },  // west
  ];
  const towers = [];
  for (const d of districts) {
    let placed = 0, tries = 0;
    while (placed < d.n && tries < 500) {
      tries++;
      const w = rr(14, 26), dep = rr(14, 26);
      const x = rr(d.x0 + w / 2, d.x1 - w / 2);
      const z = rr(d.z0 + dep / 2, d.z1 - dep / 2);
      const rect = [x - w / 2 - 3, x + w / 2 + 3, z - dep / 2 - 3, z + dep / 2 + 3];
      if (isBlocked(rect)) continue;
      if (towers.some((t) => rectsOverlap(rect, t.rect))) continue;
      // Height variety; keep towers near the hero below/around its height so B01 reads.
      let h = d.tall ? 20 + Math.pow(rand(), 1.35) * 42 : 16 + Math.pow(rand(), 1.5) * 30;
      if (Math.hypot(x, z) < 190) h = Math.min(h, 44);
      h = Math.round(h * 2) / 2;
      const glass = rand() < 0.45;
      towers.push({ x, z, w, d: dep, h, glass, rect });
      placed++;
    }
  }

  const concreteTints = [0xffffff, 0xe8e4dc, 0xd9dde2, 0xcfd6dd, 0xe2d9cf];
  const glassTints = [0xffffff, 0xcfe0ee, 0xb9d2e6, 0xd6e6f2];
  const glassBodyItems = [], concreteBodyItems = [], plinthItems = [], roofItems = [], hvacItems = [];
  const bandItems = [], litItems = [], stripItems = [], antennaItems = [], beaconItems = [];
  const tmpColor = new T.Color();

  for (const t of towers) {
    const { x, z, w, h, glass } = t; const d = t.d;
    if (glass) glassBodyItems.push({ p: [x, h / 2, z], s: [w, h, d], c: pick(glassTints) });
    else concreteBodyItems.push({ p: [x, h / 2, z], s: [w, h, d], c: pick(concreteTints) });
    plinthItems.push({ p: [x, 0.3, z], s: [w + 0.8, 0.6, d + 0.8] });
    roofItems.push({ p: [x, h + 0.2, z], s: [w + 0.5, 0.4, d + 0.5] });
    const nHvac = 1 + Math.floor(rand() * 2);
    for (let i = 0; i < nHvac; i++) {
      hvacItems.push({ p: [x + rr(-w / 4, w / 4), h + 0.85, z + rr(-d / 4, d / 4)], s: [rr(1.6, 3.2), 0.9, rr(1.4, 2.6)] });
    }
    if (h > 48) { // aviation antenna + warning beacon on the tall roofs
      antennaItems.push({ p: [x, h + 2.4, z], s: [1, 4, 1] });
      beaconItems.push({ p: [x, h + 4.5, z], s: [1, 1, 1] });
    }
    const n = Math.max(3, Math.round(h / 3.6)); // storeys
    for (let f = 0; f < n; f++) {
      const y = (f + 0.5) * (h / n);
      if (!glass) {
        // Dark glass window bands on all four faces, one per storey.
        bandItems.push({ p: [x, y, z + d / 2 + 0.07], s: [w * 0.86, 1.35, 0.14] });
        bandItems.push({ p: [x, y, z - d / 2 - 0.07], s: [w * 0.86, 1.35, 0.14] });
        bandItems.push({ p: [x + w / 2 + 0.07, y, z], s: [0.14, 1.35, d * 0.86] });
        bandItems.push({ p: [x - w / 2 - 0.07, y, z], s: [0.14, 1.35, d * 0.86] });
        // A few warmly lit offices (faint emissive), capped for the tri budget.
        for (const face of [0, 1, 2, 3]) {
          if (litItems.length >= 320 || rand() > 0.16) continue;
          const ly = y + rr(-0.1, 0.1);
          if (face < 2) {
            const sz = face === 0 ? 1 : -1;
            litItems.push({ p: [x + rr(-w * 0.3, w * 0.3), ly, z + sz * (d / 2 + 0.16)], s: [w * 0.24, 1.0, 0.1] });
          } else {
            const sx = face === 2 ? 1 : -1;
            litItems.push({ p: [x + sx * (w / 2 + 0.16), ly, z + rr(-d * 0.3, d * 0.3)], s: [0.1, 1.0, d * 0.24] });
          }
        }
      } else if (rand() < 0.55) {
        // Faint cool glow strips on lit storeys of curtain-wall towers.
        stripItems.push({ p: [x, y, z + d / 2 + 0.1], s: [w * 0.92, 0.45, 0.1] });
        stripItems.push({ p: [x, y, z - d / 2 - 0.1], s: [w * 0.92, 0.45, 0.1] });
        stripItems.push({ p: [x + w / 2 + 0.1, y, z], s: [0.1, 0.45, d * 0.92] });
        stripItems.push({ p: [x - w / 2 - 0.1, y, z], s: [0.1, 0.45, d * 0.92] });
      }
    }
  }
  inst(gTowers, UNIT_BOX, M.glassDark, 'city-tower-glass-bodies', glassBodyItems, { recv: true, kind: 'tower' });
  inst(gTowers, UNIT_BOX, M.concrete, 'city-tower-concrete-bodies', concreteBodyItems, { recv: true, kind: 'tower' });
  inst(gTowers, UNIT_BOX, M.concreteDark, 'city-tower-plinths', plinthItems, { kind: 'tower' });
  inst(gTowers, UNIT_BOX, M.slab, 'city-tower-roofs', roofItems, { kind: 'tower' });
  inst(gTowers, UNIT_BOX, M.concreteDark, 'city-tower-hvac', hvacItems, { kind: 'tower' });
  inst(gTowers, UNIT_BOX, M.glassDark, 'city-tower-window-bands', bandItems, { cast: false, kind: 'tower-window' });
  inst(gTowers, UNIT_BOX, winWarm, 'city-tower-lit-windows', litItems, { cast: false, kind: 'tower-window' });
  inst(gTowers, UNIT_BOX, winCool, 'city-tower-glass-strips', stripItems, { cast: false, kind: 'tower-window' });
  inst(gTowers, new T.CylinderGeometry(0.1, 0.16, 1, 6), M.steel, 'city-tower-antennas', antennaItems, { kind: 'tower' });
  inst(gTowers, new T.SphereGeometry(0.24, 8, 6), beaconRed, 'city-tower-beacons', beaconItems, { cast: false, kind: 'tower' });

  // ---------------------------------------------------------------- traffic (instanced block cars)
  const carPalette = [0xf2f4f6, 0x22262c, 0xb9c0c7, 0xc23b3b, 0x2f5f9f, 0x707880, 0xd8d2c4];
  const carBaseItems = [], carBodyItems = [], carCabinItems = [];
  function addCar(x, z, ry) {
    const c = pick(carPalette);
    const cabinHex = tmpColor.set(c).multiplyScalar(0.82).getHex();
    carBaseItems.push({ p: [x, 0.22, z], r: [0, ry, 0], s: [1.9, 0.42, 4.5] });
    carBodyItems.push({ p: [x, 0.66, z], r: [0, ry, 0], s: [1.85, 0.6, 4.4], c });
    const ox = Math.sin(ry) * 0.4, oz = Math.cos(ry) * 0.4;
    carCabinItems.push({ p: [x + ox, 1.2, z + oz], r: [0, ry, 0], s: [1.65, 0.58, 2.2], c: cabinHex });
  }
  for (const z of H_ROADS) {
    for (let i = 0; i < 7; i++) {
      const x = rr(-244, 244);
      if (nearAny(x, V_ROADS, 15) || Math.abs(x) < 9) continue;
      addCar(x, z - 1.9, Math.PI / 2);  // near lane, heading +X
      if (rand() < 0.7) {
        const x2 = rr(-244, 244);
        if (!nearAny(x2, V_ROADS, 15) && Math.abs(x2) > 9) addCar(x2, z + 1.9, -Math.PI / 2);
      }
    }
  }
  for (const x of V_ROADS) {
    for (let i = 0; i < 6; i++) {
      const z = rr(-244, 244);
      if (nearAny(z, H_ROADS, 15) || Math.abs(z - 56) < 15) continue;
      addCar(x - 1.9, z, 0);            // near lane, heading +Z
      if (rand() < 0.7) {
        const z2 = rr(-244, 244);
        if (!nearAny(z2, H_ROADS, 15) && Math.abs(z2 - 56) > 15) addCar(x + 1.9, z2, Math.PI);
      }
    }
  }
  inst(gTraffic, UNIT_BOX, carDark, 'city-car-bases', carBaseItems, { kind: 'car' });
  inst(gTraffic, UNIT_BOX, carPaint, 'city-car-bodies', carBodyItems, { kind: 'car' });
  inst(gTraffic, UNIT_BOX, carPaint, 'city-car-cabins', carCabinItems, { kind: 'car' });

  // ---------------------------------------------------------------- trees (instanced trunk + canopy)
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
  inst(gVeg, new T.CylinderGeometry(0.16, 0.26, 2.6, 6), M.wood, 'city-tree-trunks', trunkItems, { kind: 'tree' });
  inst(gVeg, new T.IcosahedronGeometry(1, 1), M.grass, 'city-tree-canopies', canopyItems, { kind: 'tree' });

  // ---------------------------------------------------------------- hazy skyline (two silhouette rings)
  // Flat baked-haze boxes inside the env sky dome (r480) / terrain disc (r478);
  // they read as a faded far downtown and never compete with the hero tower.
  function skylineRing(name, mat, count, r0, r1, h0, h1, w0, w1) {
    const items = [];
    for (let i = 0; i < count; i++) {
      const a = (i / count) * TAU + rr(-0.4, 0.4) * (TAU / count);
      const r = rr(r0, r1);
      const w = rr(w0, w1), d = rr(w0, w1), h = rr(h0, h1);
      const tint = tmpColor.setScalar(rr(0.9, 1.06)).getHex();
      items.push({ p: [r * Math.cos(a), h / 2, r * Math.sin(a)], r: [0, rr(0, TAU), 0], s: [w, h, d], c: tint });
    }
    const im = inst(gSkyline, UNIT_BOX, mat, name, items, { cast: false, kind: 'skyline' });
    if (im) im.renderOrder = 1;
    return items.length;
  }
  const skyNear = skylineRing('city-skyline-near', hazeNear, 46, 300, 362, 28, 90, 12, 26);
  const skyFar = skylineRing('city-skyline-far', hazeFar, 46, 382, 446, 40, 125, 16, 34);

  // ---------------------------------------------------------------- stats + wiring
  let triangles = 0;
  group.traverse((o) => {
    if (!o.isMesh || !o.geometry) return;
    const g = o.geometry;
    const per = g.index ? g.index.count / 3 : (g.attributes.position ? g.attributes.position.count / 3 : 0);
    triangles += per * (o.isInstancedMesh ? o.count : 1);
  });
  group.userData = {
    module: 'cityContext',
    stats: {
      towers: towers.length,
      glassTowers: glassBodyItems.length,
      cars: carBodyItems.length,
      trees: treeSpots.length,
      parks: parks.length,
      skyline: skyNear + skyFar,
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
    owned.forEach((m) => m.dispose()); // never the shared makeMaterials() library
    group.removeFromParent();
  }

  return { group, dispose };
}
