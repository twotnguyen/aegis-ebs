// structure.js — AEGIS Tower B01 load-bearing structure (owner: structure module).
// Floor slabs, perimeter + interior column grids (instanced), floor beams (instanced),
// central elevator/stair core (concrete walls + steel doors), wider ground podium, roof.
// Contract: src/three/CONTRACT.md — all dimensions come from config.js, materials from materials.js.
import * as THREE from 'three';
import { BUILDING, floorY, pitch, totalHeight } from './config.js';
import { makeMaterials } from './materials.js';

const pad = (n) => String(n).padStart(2, '0');

export function build(ctx = {}) {
  const B = BUILDING;
  const mats = ctx.materials || makeMaterials();
  const ownsMaterials = !ctx.materials;

  const W = B.width, D = B.depth, F = B.floors;
  const fh = B.floorHeight, st = B.slabThickness;
  const H = totalHeight();                       // 38 m slab-top of roof
  const coreW = B.coreWidth, coreD = B.coreDepth;

  // Section sizes (detail constants local to this module; footprint/heights from config).
  const colP = 0.5;                              // perimeter column, square concrete
  const colI = 0.45;                             // interior column, square steel
  const beamW = 0.35, beamH = 0.5;               // steel beam section
  const wallT = 0.3;                             // core wall thickness
  const doorW = 1.2, doorH = 2.2;                // elevator door leaf pair
  const stairW = 1.0;                            // stair door
  const podiumM = 4;                             // podium extends 4 m each side
  const podiumCol = 0.6;
  const PW = W + 2 * podiumM, PD = D + 2 * podiumM;
  const podiumH = B.podiumFloors * pitch();      // podium roof slab top = floorY(1)

  const group = new THREE.Group();
  group.name = 'structure';

  // One shared unit box for EVERY element (regular meshes scale it, instanced
  // meshes compose per-instance scale) → 1 geometry, 12 triangles per box.
  const unitBox = new THREE.BoxGeometry(1, 1, 1);
  const geoms = new Set([unitBox]);
  const _m = new THREE.Matrix4();
  const _p = new THREE.Vector3();
  const _q = new THREE.Quaternion();
  const _s = new THREE.Vector3();

  function box(name, mat, sx, sy, sz, x, y, z, userData = {}, parent = group) {
    const mesh = new THREE.Mesh(unitBox, mat);
    mesh.name = name;
    mesh.scale.set(sx, sy, sz);
    mesh.position.set(x, y, z);
    mesh.userData = userData;
    mesh.castShadow = true;
    mesh.receiveShadow = true;
    parent.add(mesh);
    return mesh;
  }

  // items: [{ p:[x,y,z], s:[sx,sy,sz] }]
  function instanced(name, mat, items, userData = {}, parent = group) {
    const im = new THREE.InstancedMesh(unitBox, mat, items.length);
    im.name = name;
    im.userData = userData;
    im.castShadow = true;
    im.receiveShadow = true;
    for (let i = 0; i < items.length; i++) {
      _p.fromArray(items[i].p);
      _s.fromArray(items[i].s);
      _m.compose(_p, _q, _s);
      im.setMatrixAt(i, _m);
    }
    im.instanceMatrix.needsUpdate = true;
    im.computeBoundingSphere();
    parent.add(im);
    return im;
  }

  // ---------------------------------------------------------------- slabs
  // Slab i: top at floorY(i), i = 0..F (i = F is the roof slab).
  const slabs = new THREE.Group();
  slabs.name = 'slabs';
  group.add(slabs);
  for (let i = 0; i <= F; i++) {
    const roof = i === F;
    box(roof ? 'roof-slab' : `slab-f${pad(i + 1)}`, mats.slab,
      W, st, D, 0, floorY(i) - st / 2, 0, { floor: i }, slabs);
  }

  // ---------------------------------------------------------------- column / beam grid (computed once)
  const baysX = 8, baysZ = 5;
  const perimXs = [], perimZs = [];
  for (let j = 0; j <= baysX; j++) perimXs.push(-W / 2 + colP / 2 + j * (W - colP) / baysX);
  for (let k = 0; k <= baysZ; k++) perimZs.push(-D / 2 + colP / 2 + k * (D - colP) / baysZ);
  const zEdge = D / 2 - colP / 2, xEdge = W / 2 - colP / 2;
  const perimPos = [];
  for (const x of perimXs) { perimPos.push([x, -zEdge], [x, zEdge]); }
  for (let k = 1; k < baysZ; k++) { perimPos.push([-xEdge, perimZs[k]], [xEdge, perimZs[k]]); }

  const innerXs = [], innerZs = [];
  for (let j = 1; j < 6; j++) innerXs.push(-W / 2 + j * W / 6);
  for (let k = 1; k < 4; k++) innerZs.push(-D / 2 + k * D / 4);
  const innerPos = [];
  for (const x of innerXs) for (const z of innerZs) {
    // keep columns clear of the core walls
    if (Math.abs(x) <= coreW / 2 + colI / 2 + 0.05 && Math.abs(z) <= coreD / 2 + colI / 2 + 0.05) continue;
    innerPos.push([x, z]);
  }

  // Beam lines: X-spanning beams on every Z column line, Z-spanning on every X line.
  const beamZLines = [-zEdge, ...innerZs, zEdge];
  const beamXLines = [...perimXs];
  for (const x of innerXs) if (!beamXLines.some((v) => Math.abs(v - x) < 1e-6)) beamXLines.push(x);

  // ---------------------------------------------------------------- tower floors (per-floor groups → floor isolation)
  const floors = new THREE.Group();
  floors.name = 'floors';
  group.add(floors);
  for (let i = 0; i < F; i++) {
    const baseY = floorY(i);
    const fg = new THREE.Group();
    fg.name = `structure-f${pad(i + 1)}`;
    fg.userData = { floor: i };
    floors.add(fg);
    const ud = { floor: i };
    const colY = baseY + fh / 2;
    const beamY = baseY + fh - beamH / 2;         // beams hang under the slab above

    instanced(`columns-perimeter-f${pad(i + 1)}`, mats.concrete,
      perimPos.map(([x, z]) => ({ p: [x, colY, z], s: [colP, fh, colP] })), ud, fg);
    instanced(`columns-interior-f${pad(i + 1)}`, mats.steel,
      innerPos.map(([x, z]) => ({ p: [x, colY, z], s: [colI, fh, colI] })), ud, fg);
    instanced(`beams-x-f${pad(i + 1)}`, mats.steel,
      beamZLines.map((z) => ({ p: [0, beamY, z], s: [W - colP, beamH, beamW] })), ud, fg);
    instanced(`beams-z-f${pad(i + 1)}`, mats.steel,
      beamXLines.map((x) => ({ p: [x, beamY, 0], s: [beamW, beamH, D - colP] })), ud, fg);
  }

  // ---------------------------------------------------------------- podium (ground floor, wider footprint)
  // Ring slabs (4 strips) so they never z-fight the tower slabs they surround.
  const podium = new THREE.Group();
  podium.name = 'podium';
  podium.userData = { floor: 0 };
  group.add(podium);
  function podiumRing(topY, tag) {
    const y = topY - st / 2;
    const ud = { floor: 0 };
    box(`podium-${tag}-front`, mats.slab, PW, st, podiumM, 0, y, D / 2 + podiumM / 2, ud, podium);
    box(`podium-${tag}-back`, mats.slab, PW, st, podiumM, 0, y, -(D / 2 + podiumM / 2), ud, podium);
    box(`podium-${tag}-left`, mats.slab, podiumM, st, D, -(W / 2 + podiumM / 2), y, 0, ud, podium);
    box(`podium-${tag}-right`, mats.slab, podiumM, st, D, W / 2 + podiumM / 2, y, 0, ud, podium);
  }
  podiumRing(floorY(0), 'ground');
  podiumRing(podiumH, 'roof');

  const pColH = podiumH - st;
  const pXs = [], pZs = [];
  for (let j = 0; j <= 8; j++) pXs.push(-PW / 2 + podiumCol / 2 + j * (PW - podiumCol) / 8);
  for (let k = 0; k <= 6; k++) pZs.push(-PD / 2 + podiumCol / 2 + k * (PD - podiumCol) / 6);
  const pzEdge = PD / 2 - podiumCol / 2, pxEdge = PW / 2 - podiumCol / 2;
  const pPos = [];
  for (const x of pXs) { pPos.push([x, -pzEdge], [x, pzEdge]); }
  for (let k = 1; k < 6; k++) { pPos.push([-pxEdge, pZs[k]], [pxEdge, pZs[k]]); }
  instanced('podium-columns', mats.concrete,
    pPos.map(([x, z]) => ({ p: [x, pColH / 2, z], s: [podiumCol, pColH, podiumCol] })), { floor: 0 }, podium);
  const pBeamY = podiumH - st - beamH / 2;
  instanced('podium-beams', mats.steel, [
    { p: [0, pBeamY, -pzEdge], s: [PW - podiumCol, beamH, beamW] },
    { p: [0, pBeamY, pzEdge], s: [PW - podiumCol, beamH, beamW] },
    { p: [-pxEdge, pBeamY, 0], s: [beamW, beamH, PD - podiumCol] },
    { p: [pxEdge, pBeamY, 0], s: [beamW, beamH, PD - podiumCol] },
  ], { floor: 0 }, podium);

  // ---------------------------------------------------------------- central core (elevators + stair)
  const core = new THREE.Group();
  core.name = 'core';
  core.userData = { core: true };
  group.add(core);
  const coreUD = { core: true };
  const fx = coreW / 2 - wallT / 2;               // side-wall centerlines
  const fz = coreD / 2 - wallT / 2;
  const frontSpan = coreW - 2 * wallT;            // front wall fits between side walls
  // Solid walls: right (+X) and back (-Z), full height.
  box('core-wall-right', mats.concreteDark, wallT, H, coreD, fx, H / 2, 0, coreUD, core);
  box('core-wall-back', mats.concreteDark, frontSpan, H, wallT, 0, H / 2, -fz, coreUD, core);
  // Shaft divider between the two elevators + stair enclosure wall (left half, rear).
  box('core-divider', mats.concreteDark, 0.2, H, coreD - 2 * wallT, 0, H / 2, 0, coreUD, core);
  box('core-stair-wall', mats.concreteDark, coreW / 2 - wallT - 0.1, H, 0.2,
    -(coreW / 2 - wallT) / 2 - 0.05, H / 2, -1.5, coreUD, core);

  // Segmented walls with door openings: front = 2 elevator doors/floor, left = stair door/floor.
  const doorXs = [-1.8, 1.8];
  const segs = [];
  for (let i = 0; i < F; i++) {
    const y0 = floorY(i);
    // front piers between/around elevator openings
    const edges = [-frontSpan / 2, doorXs[0] - doorW / 2, doorXs[0] + doorW / 2,
      doorXs[1] - doorW / 2, doorXs[1] + doorW / 2, frontSpan / 2];
    for (let e = 0; e < 6; e += 2) {
      const w = edges[e + 1] - edges[e];
      segs.push({ p: [(edges[e] + edges[e + 1]) / 2, y0 + fh / 2, fz], s: [w, fh, wallT] });
    }
    for (const dx of doorXs) {                    // lintels above elevator doors
      segs.push({ p: [dx, y0 + doorH + (fh - doorH) / 2, fz], s: [doorW, fh - doorH, wallT] });
    }
    // left wall piers + lintel around stair door (opening centered z = 0)
    segs.push({ p: [-fx, y0 + fh / 2, -(coreD / 2 + stairW / 2) / 2], s: [wallT, fh, (coreD - stairW) / 2] });
    segs.push({ p: [-fx, y0 + fh / 2, (coreD / 2 + stairW / 2) / 2], s: [wallT, fh, (coreD - stairW) / 2] });
    segs.push({ p: [-fx, y0 + doorH + (fh - doorH) / 2, 0], s: [wallT, fh - doorH, stairW] });
  }
  instanced('core-walls-segments', mats.concreteDark, segs, coreUD, core);

  // Steel doors: each elevator = 2 leaves (center gap), stair = single leaf, recessed in wall.
  const doors = [];
  const leafW = (doorW - 0.04) / 2;
  for (let i = 0; i < F; i++) {
    const y = floorY(i) + doorH / 2;
    for (const dx of doorXs) {
      doors.push({ p: [dx - (leafW / 2 + 0.01), y, fz], s: [leafW, doorH, 0.08] });
      doors.push({ p: [dx + (leafW / 2 + 0.01), y, fz], s: [leafW, doorH, 0.08] });
    }
    doors.push({ p: [-fx, y, 0], s: [0.08, doorH, stairW] });
  }
  instanced('core-doors', mats.steel, doors, coreUD, core);

  // ---------------------------------------------------------------- roof details
  box('core-cap', mats.concreteDark, coreW + 0.4, 0.3, coreD + 0.4, 0, H + 0.15, 0, { floor: F }, core);
  const roof = new THREE.Group();
  roof.name = 'roof';
  roof.userData = { floor: F };
  group.add(roof);
  box('roof-plinth-1', mats.concreteDark, 3, 0.5, 2.2, -12, H + 0.25, -8, { floor: F }, roof);
  box('roof-plinth-2', mats.concreteDark, 3, 0.5, 2.2, 12, H + 0.25, -8, { floor: F }, roof);

  if (ctx.root) ctx.root.add(group);

  function dispose() {
    group.traverse((o) => {
      if (o.isInstancedMesh) o.dispose();
    });
    for (const g of geoms) g.dispose();
    if (ownsMaterials) for (const m of Object.values(mats)) if (m && m.dispose) m.dispose();
    if (group.parent) group.parent.remove(group);
  }

  return { group, dispose };
}
