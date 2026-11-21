// interior.js — per-floor interiors for AEGIS Tower B01 (10 floors x 4 zones).
// Contract: src/three/CONTRACT.md. Additive module: reads config/materials, never edits them.
// Contents: zone floor plates (tagged for statusViz), central corridor (zoneGap cross),
// low partitions (materials.wall) trimmed around the structure core, instanced furniture
// (office desks/chairs/monitors, meeting tables + whiteboards, pantry counters/tables),
// and potted plants. All repeated elements are InstancedMesh; target < 100k triangles.
import * as THREE from 'three';
import { mergeGeometries } from 'three/addons/utils/BufferGeometryUtils.js';
import { BUILDING, floorY, zoneRect, zoneCode } from './config.js';
import { makeMaterials } from './materials.js';

const FINISH = 0.06;   // zone floor finish thickness, sits on slab top (floorY)
const PART_H = 1.15;   // low partition height (sight lines stay open through facade glass)
const PART_T = 0.10;   // partition thickness
const ROOM_TYPES = ['office', 'meeting', 'pantry']; // cycled by (floor + zone) % 3

// Deterministic PRNG so the twin looks identical on every load.
function mulberry32(seed){
  let a = seed >>> 0;
  return function(){
    a |= 0; a = (a + 0x6D2B79F5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const box = (w, h, d, x = 0, y = 0, z = 0) => new THREE.BoxGeometry(w, h, d).translate(x, y, z);
const cyl = (rt, rb, h, seg, y = 0) => new THREE.CylinderGeometry(rt, rb, h, seg).translate(0, y, 0);
const merge = (...geos) => mergeGeometries(geos, false);

export function build(ctx){
  const ownsLib = !ctx.materials;
  const M = ctx.materials || makeMaterials();
  const rand = mulberry32(0xA3615);
  const group = new THREE.Group();
  group.name = 'interior';
  const ownedMaterials = [];

  const hw = BUILDING.width / 2 - BUILDING.zoneInset;   // interior half width  (20.4)
  const hd = BUILDING.depth / 2 - BUILDING.zoneInset;   // interior half depth  (13.4)
  const gx = BUILDING.zoneGap / 2, gz = BUILDING.zoneGap / 2;
  const cw = BUILDING.coreWidth / 2, cd = BUILDING.coreDepth / 2;
  const coreMX = cw + 0.6, coreMZ = cd + 0.6;           // keep-out margin around structure core
  const hitsCore = (x0, x1, z0, z1) => x1 > -coreMX && x0 < coreMX && z1 > -coreMZ && z0 < coreMZ;

  // ---- shared geometries (built once, reused by every instance/mesh) ----
  const zoneFloorGeo = box(hw - gx, FINISH, hd - gz);   // one plate per zoneRect
  // corridor cross = full X strip + two Z strips (split so tops never z-fight)
  const corridorGeo = merge(
    box(2 * hw, FINISH, BUILDING.zoneGap),
    box(BUILDING.zoneGap, FINISH, hd - gz, 0, 0, -(gz + (hd - gz) / 2)),
    box(BUILDING.zoneGap, FINISH, hd - gz, 0, 0,  (gz + (hd - gz) / 2)),
  );
  const partHLen = hw - cw, partVLen = hd - cd;         // partitions run facade -> core
  const partHGeo = box(partHLen, PART_H, PART_T);
  const partVGeo = box(PART_T, PART_H, partVLen);
  const capHGeo  = box(partHLen, 0.06, PART_T + 0.08);
  const capVGeo  = box(PART_T + 0.08, 0.06, partVLen);

  // furniture: simple block masses, merged per item (local origin at finished floor level)
  const deskGeo = merge(                              // 1.6 x 0.8 worktop, panel legs
    box(1.6, 0.05, 0.8, 0, 0.725, 0),
    box(0.06, 0.70, 0.74, -0.72, 0.35, 0),
    box(0.06, 0.70, 0.74,  0.72, 0.35, 0),
  );
  const chairGeo = merge(                             // block chair, faces +Z
    box(0.45, 0.05, 0.45, 0, 0.44, 0),
    box(0.45, 0.50, 0.05, 0, 0.72, -0.20),
    box(0.08, 0.40, 0.08, 0, 0.22, 0),
    box(0.36, 0.04, 0.36, 0, 0.02, 0),
  );
  const monitorGeo = merge(                           // frame + neck + foot, screen faces +Z
    box(0.56, 0.34, 0.03, 0, 1.09, 0),
    box(0.05, 0.16, 0.05, 0, 0.84, 0),
    box(0.24, 0.02, 0.16, 0, 0.76, 0),
  );
  const screenGeo = box(0.52, 0.30, 0.012, 0, 1.09, 0.022);
  const meetTableGeo = merge(
    box(3.4, 0.06, 1.3, 0, 0.73, 0),
    box(0.5, 0.70, 0.9, -1.2, 0.35, 0),
    box(0.5, 0.70, 0.9,  1.2, 0.35, 0),
  );
  const boardGeo = merge(                             // mobile whiteboard, faces +Z
    box(1.9, 1.15, 0.04, 0, 1.50, 0),
    box(0.05, 1.50, 0.05, -0.85, 0.75, 0),
    box(0.05, 1.50, 0.05,  0.85, 0.75, 0),
    box(0.5, 0.04, 0.4, -0.85, 0.02, 0),
    box(0.5, 0.04, 0.4,  0.85, 0.02, 0),
  );
  const counterGeo = merge(
    box(3.6, 0.86, 0.62, 0, 0.43, 0),
    box(3.7, 0.05, 0.68, 0, 0.885, 0),
  );
  const fridgeGeo  = box(0.75, 1.85, 0.70, 0, 0.925, 0);
  const coffeeGeo  = box(0.32, 0.42, 0.30, 0, 0.21, 0); // origin at counter top
  const ptableGeo  = merge(                           // round pantry table
    cyl(0.55, 0.55, 0.05, 14, 0.73),
    cyl(0.06, 0.06, 0.70, 8, 0.36),
    cyl(0.32, 0.32, 0.04, 14, 0.02),
  );
  const potGeo  = cyl(0.26, 0.20, 0.36, 10, 0.18);
  const leafGeo = new THREE.IcosahedronGeometry(0.5, 1); // low-poly bush, origin at center

  const screenMat = M.emissive(0xbfe3ff, 0.5);        // soft glow, readable through glass
  ownedMaterials.push(screenMat);

  // ---- instance batches: one InstancedMesh per (geometry, material) ----
  const lists = new Map();
  const use = (name, geo, mat) => { lists.set(name, { geo, mat, items: [] }); };
  use('partitions-h', partHGeo, M.wall);       use('partitions-v', partVGeo, M.wall);
  use('partition-caps-h', capHGeo, M.wood);    use('partition-caps-v', capVGeo, M.wood);
  use('desks', deskGeo, M.wood);               use('chairs', chairGeo, M.concreteDark);
  use('monitor-bodies', monitorGeo, M.mullion);use('monitor-screens', screenGeo, screenMat);
  use('meeting-tables', meetTableGeo, M.wood); use('whiteboards', boardGeo, M.wall);
  use('pantry-counters', counterGeo, M.wood);  use('fridges', fridgeGeo, M.steel);
  use('pantry-tables', ptableGeo, M.wood);     use('coffee-machines', coffeeGeo, M.mullion);
  use('plant-pots', potGeo, M.concreteDark);   use('plant-foliage', leafGeo, M.grass);
  const put = (name, x, y, z, ry = 0, sx = 1, sy = sx, sz = sx) =>
    lists.get(name).items.push({ x, y, z, ry, sx, sy, sz });

  const plant = (x, base, z, s) => {
    put('plant-pots', x, base, z, rand() * Math.PI * 2, s);
    put('plant-foliage', x, base + 0.78 * s, z, rand() * Math.PI * 2, s, s * (1 + rand() * 0.3), s);
  };

  let zoneMeshCount = 0;
  for (let i = 0; i < BUILDING.floors; i++){
    const fy = floorY(i);                 // slab top of floor i
    const base = fy + FINISH;             // finished floor level (furniture origin)
    const fg = new THREE.Group();
    fg.name = `interior-floor-${String(i + 1).padStart(2, '0')}`;
    fg.userData = { floor: i };
    group.add(fg);

    // corridor floor (central zoneGap cross, untagged — not a status zone)
    const corridor = new THREE.Mesh(corridorGeo, M.floor);
    corridor.name = `corridor-floor-F${String(i + 1).padStart(2, '0')}`;
    corridor.position.set(0, fy + FINISH / 2, 0);
    corridor.receiveShadow = true;
    fg.add(corridor);

    for (let zi = 0; zi < BUILDING.zonesPerFloor; zi++){
      const r = zoneRect(zi);
      const sx = (zi % 2 === 0) ? -1 : 1;
      const sz = (zi < 2) ? -1 : 1;
      const x0 = r.x - r.w / 2, x1 = r.x + r.w / 2;
      const z0 = r.z - r.d / 2, z1 = r.z + r.d / 2;
      const code = zoneCode(i, zi);

      // zone floor plate — THE tagged zone mesh for statusViz (own material clone
      // so per-zone recoloring never bleeds into other zones)
      const zm = M.floor.clone();
      ownedMaterials.push(zm);
      const plate = new THREE.Mesh(zoneFloorGeo, zm);
      plate.name = `zone-floor-${code}`;
      plate.position.set(r.x, fy + FINISH / 2, r.z);
      plate.receiveShadow = true;
      plate.userData = { floor: i, zone: zi, zoneCode: code };
      fg.add(plate);
      zoneMeshCount++;

      // low partitions on the two corridor-facing edges, trimmed at the core
      const py = fy + PART_H / 2, cy = fy + PART_H + 0.03;
      const hx = sx * (cw + partHLen / 2), hz = sz * (gz + PART_T / 2);
      const vx = sx * (gz + PART_T / 2),   vz = sz * (cd + partVLen / 2);
      put('partitions-h', hx, py, hz);     put('partitions-v', vx, py, vz);
      put('partition-caps-h', hx, cy, hz); put('partition-caps-v', vx, cy, vz);

      // plants along the facade edge of every zone (visible through the glass)
      const facadeZ = sz < 0 ? z0 + 0.7 : z1 - 0.7;
      plant(x0 + 0.7, base, facadeZ, 0.8 + rand() * 0.4);
      plant(x1 - 0.7, base, facadeZ, 0.8 + rand() * 0.4);
      plant(r.x,      base, facadeZ, 0.8 + rand() * 0.4);

      const type = ROOM_TYPES[(i + zi) % ROOM_TYPES.length];
      if (type === 'office'){
        // open-plan workstation grid; aisles kept along facade + corridor edges,
        // cells overlapping the core keep-out are skipped
        const startX = x0 + (sx < 0 ? 1.8 : 1.9), endX = x1 - (sx < 0 ? 1.9 : 1.8);
        const startZ = z0 + (sz < 0 ? 1.8 : 1.9), endZ = z1 - (sz < 0 ? 1.9 : 1.8);
        for (let cx = startX; cx <= endX + 1e-6; cx += 2.6){
          for (let cz = startZ; cz <= endZ + 1e-6; cz += 3.0){
            if (hitsCore(cx - 0.85, cx + 0.85, cz - 0.45, cz + 1.35)) continue;
            put('desks', cx, base, cz);
            put('chairs', cx, base, cz + 0.8, Math.PI);
            put('monitor-bodies', cx, base, cz - 0.25);
            put('monitor-screens', cx, base, cz - 0.25);
          }
        }
      } else if (type === 'meeting'){
        put('meeting-tables', r.x, base, r.z);
        for (const dx of [-1.2, -0.4, 0.4, 1.2]){
          put('chairs', r.x + dx, base, r.z + 1.2, Math.PI);
          put('chairs', r.x + dx, base, r.z - 1.2, 0);
        }
        put('chairs', r.x + 2.3, base, r.z, -Math.PI / 2);
        put('chairs', r.x - 2.3, base, r.z,  Math.PI / 2);
        put('whiteboards', r.x, base, r.z - sz * 3.4, sz < 0 ? Math.PI : 0);
      } else { // pantry
        const cz = sz < 0 ? z0 + 0.5 : z1 - 0.5;
        const cx = r.x + sx * 3.5;
        put('pantry-counters', cx, base, cz);
        put('fridges', r.x + sx * 1.0, base, cz);
        put('coffee-machines', cx + sx * 0.9, base + 0.91, cz);
        for (const tx of [r.x - sx * 2.0, r.x - sx * 6.0]){
          const tz = r.z + sz * 2.8;
          put('pantry-tables', tx, base, tz);
          for (let k = 0; k < 3; k++){
            const a = Math.PI / 6 + k * (2 * Math.PI / 3);
            put('chairs', tx + 0.9 * Math.sin(a), base, tz + 0.9 * Math.cos(a), a + Math.PI);
          }
        }
      }
    }

    // taller plants at the four corridor ends (elevator lobby greenery)
    plant(-(hw - 0.7), base, 0, 1.1 + rand() * 0.3);
    plant( (hw - 0.7), base, 0, 1.1 + rand() * 0.3);
    plant(0, base, -(hd - 0.7), 1.1 + rand() * 0.3);
    plant(0, base,  (hd - 0.7), 1.1 + rand() * 0.3);
  }

  // ---- realize instance batches ----
  const dummy = new THREE.Object3D();
  let furnitureInstances = 0;
  const partitionLists = new Set(['partitions-h', 'partitions-v', 'partition-caps-h', 'partition-caps-v']);
  for (const [name, L] of lists){
    if (!L.items.length) continue;
    const im = new THREE.InstancedMesh(L.geo, L.mat, L.items.length);
    im.name = name;
    L.items.forEach((it, k) => {
      dummy.position.set(it.x, it.y, it.z);
      dummy.rotation.set(0, it.ry, 0);
      dummy.scale.set(it.sx, it.sy, it.sz);
      dummy.updateMatrix();
      im.setMatrixAt(k, dummy.matrix);
    });
    im.instanceMatrix.needsUpdate = true;
    im.castShadow = true;
    im.frustumCulled = false; // instances span the whole tower; skip per-geometry culling
    group.add(im);
    if (!partitionLists.has(name)) furnitureInstances += L.items.length;
  }

  // ---- stats + teardown ----
  let triangles = 0;
  group.traverse(o => {
    if (!o.isMesh) return;
    const g = o.geometry;
    const per = g.index ? g.index.count / 3 : g.attributes.position.count / 3;
    triangles += per * (o.isInstancedMesh ? o.count : 1);
  });

  function dispose(){
    group.traverse(o => {
      if (!o.isMesh) return;
      o.geometry.dispose();
      if (o.isInstancedMesh && typeof o.dispose === 'function') o.dispose();
    });
    for (const m of ownedMaterials) m.dispose();
    if (ownsLib) for (const v of Object.values(M)) if (v && v.isMaterial) v.dispose();
    group.removeFromParent();
  }

  if (ctx.root) ctx.root.add(group);
  return {
    group,
    dispose,
    stats: { zones: zoneMeshCount, furnitureInstances, triangles: Math.round(triangles) },
  };
}
