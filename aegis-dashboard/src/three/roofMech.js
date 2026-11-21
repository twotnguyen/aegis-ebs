// roofMech.js — rooftop mechanical details for AEGIS Tower B01 (sits on totalHeight()).
// Instanced HVAC units + cooling towers (spinning fans), elevator overrun above the core,
// helipad marking (circle + H), lightning rods, steel railing on the parapet coping,
// blinking red aviation obstruction beacons and mushroom vent stacks.
// Contract: src/three/CONTRACT.md — build(ctx) -> { group, update, dispose }.
// update accepts (status, dt) per contract or (dt) alone. All building dims from config.js;
// materials.steel / concreteDark / beacon(hex) from the shared library. userData = { floor:'roof' }.
import * as THREE from 'three';
import { BUILDING, totalHeight } from './config.js';
import { makeMaterials } from './materials.js';

export function build(ctx = {}) {
  const T = ctx.THREE || THREE;
  const B = (ctx.config && (ctx.config.BUILDING || ctx.config)) || BUILDING;
  const H = (B === BUILDING) ? totalHeight() : B.floors * (B.floorHeight + B.slabThickness);
  let M = ctx.materials;
  let ownLibrary = false;
  if (!M) { M = makeMaterials(); ownLibrary = true; }

  const W = B.width, D = B.depth, HW = W / 2, HD = D / 2;
  const PARAPET = B.roofParapet;
  const coreW = B.coreWidth, coreD = B.coreDepth;
  const PT = 0.25;                          // parapet thickness (matches facade.js)
  const TAG = { floor: 'roof' };

  const group = new T.Group();
  group.name = 'roofMech';
  group.userData = { floor: 'roof' };

  const ownedMaterials = [];
  const own = (m) => { ownedMaterials.push(m); return m; };

  const dummy = new T.Object3D();
  const unitBox = new T.BoxGeometry(1, 1, 1);
  const unitCyl = new T.CylinderGeometry(1, 1, 1, 10);
  const unitSphere = new T.SphereGeometry(1, 12, 8);

  // items: { p:[x,y,z], r:[rx,ry,rz], s:[sx,sy,sz] }
  function inst(geo, mat, items, name) {
    const im = new T.InstancedMesh(geo, mat, items.length);
    items.forEach((it, i) => {
      dummy.position.set(it.p[0], it.p[1], it.p[2]);
      dummy.rotation.set(it.r ? it.r[0] : 0, it.r ? it.r[1] : 0, it.r ? it.r[2] : 0);
      dummy.scale.set(it.s ? it.s[0] : 1, it.s ? it.s[1] : 1, it.s ? it.s[2] : 1);
      dummy.updateMatrix();
      im.setMatrixAt(i, dummy.matrix);
    });
    im.instanceMatrix.needsUpdate = true;
    im.computeBoundingSphere();
    im.name = name;
    im.userData = { floor: 'roof' };
    im.castShadow = true;
    group.add(im);
    return im;
  }
  function box(mat, sx, sy, sz, x, y, z, name) {
    const m = new T.Mesh(unitBox, mat);
    m.scale.set(sx, sy, sz);
    m.position.set(x, y, z);
    m.name = name;
    m.userData = { floor: 'roof' };
    m.castShadow = true;
    group.add(m);
    return m;
  }

  // ---------------------------------------------------------------- elevator overrun
  // Core cap (structure.js) tops out at H + 0.3; machine-room overrun sits on it.
  const ow = coreW - 1.6, od = coreD - 1.6, oh = 2.7;
  const ob = H + 0.3;                       // overrun base
  box(M.concreteDark, ow, oh, od, 0, ob + oh / 2, 0, 'roofmech-elevator-overrun');
  const capTop = ob + oh + 0.22;
  box(M.concreteDark, ow + 0.5, 0.22, od + 0.5, 0, ob + oh + 0.11, 0, 'roofmech-overrun-cap');
  box(M.steel, 1.1, 2.05, 0.08, 1.5, ob + 1.025, od / 2 + 0.05, 'roofmech-overrun-door');
  box(M.concreteDark, 1.4, 0.18, 0.2, 1.5, ob + 2.16, od / 2 + 0.06, 'roofmech-overrun-lintel');
  inst(unitBox, M.steel, [
    { p: [-(ow / 2 + 0.04), ob + 1.9, 0], s: [0.08, 0.8, 1.6] },
    { p: [ow / 2 + 0.04, ob + 1.9, 0], s: [0.08, 0.8, 1.6] },
  ], 'roofmech-overrun-louvers');

  // ---------------------------------------------------------------- helipad (circle + H)
  const hx = W / 4, hz = D / 4 - 1, HR = 5.2;   // front-right quadrant, clear of core/beacon
  const paintWhite = own(new T.MeshStandardMaterial({
    color: 0xe9eaec, roughness: 0.55, metalness: 0, side: T.DoubleSide,
  }));
  const ringGeo = new T.RingGeometry(HR - 0.35, HR, 48, 1);
  const ring = new T.Mesh(ringGeo, paintWhite);
  ring.rotation.x = -Math.PI / 2;
  ring.position.set(hx, H + 0.025, hz);
  ring.name = 'roofmech-helipad-circle';
  ring.userData = { floor: 'roof' };
  group.add(ring);
  inst(unitBox, paintWhite, [
    { p: [hx - 0.95, H + 0.03, hz], s: [0.55, 0.02, 3.0] },
    { p: [hx + 0.95, H + 0.03, hz], s: [0.55, 0.02, 3.0] },
    { p: [hx, H + 0.03, hz], s: [2.45, 0.02, 0.55] },
  ], 'roofmech-helipad-H');

  // ---------------------------------------------------------------- HVAC units (instanced)
  // Two units ride the roof plinths from structure.js (±(HW-10), -(HD/2+0.5), top H+0.5).
  const plX = HW - 10, plZ = -(HD / 2 + 0.5);
  const hvacPos = [
    [plX, plZ, H + 0.5], [-plX, plZ, H + 0.5],
    [HW - 5, -(HD - 3.5), H], [-(HW - 5), HD - 4, H],
  ];
  inst(unitBox, M.concreteDark, hvacPos.map(([x, z, y], i) => ({
    p: [x, y + 0.75, z], s: [2.8, 1.5, 2.0],
  })), 'roofmech-hvac-units');
  inst(unitBox, M.steel, hvacPos.map(([x, z, y]) => ({
    p: [x + 1.44, y + 0.8, z], s: [0.06, 0.6, 0.9],
  })), 'roofmech-hvac-panels');
  // supply ducts from plinth units toward the core
  inst(unitBox, M.steel, [
    { p: [plX, H + 0.85, plZ + 2.8], s: [0.7, 0.6, 3.4] },
    { p: [-plX, H + 0.85, plZ + 2.8], s: [0.7, 0.6, 3.4] },
  ], 'roofmech-hvac-ducts');

  // ---------------------------------------------------------------- cooling towers (instanced)
  const ctZ = -(HD - 4);
  const ctXs = [-HW + 5, -HW + 9, -HW + 13];
  const ctBase = H + 0.15, ctH = 2.3;
  inst(unitBox, M.concreteDark, ctXs.map((x) => ({
    p: [x, H + 0.075, ctZ], s: [3.0, 0.15, 3.0],
  })), 'roofmech-cooling-pads');
  inst(unitBox, M.concreteDark, ctXs.map((x) => ({
    p: [x, ctBase + ctH / 2, ctZ], s: [2.7, ctH, 2.7],
  })), 'roofmech-cooling-towers');
  const shroudGeo = new T.CylinderGeometry(1.0, 1.05, 0.55, 16);
  inst(shroudGeo, M.steel, ctXs.map((x) => ({
    p: [x, ctBase + ctH + 0.275, ctZ], s: [1, 1, 1],
  })), 'roofmech-cooling-shrouds');
  const slats = [];
  for (const x of ctXs) for (let k = 0; k < 3; k++) {
    slats.push({ p: [x, ctBase + 0.55 + k * 0.6, ctZ + 1.38], s: [2.5, 0.14, 0.06] });
  }
  inst(unitBox, M.steel, slats, 'roofmech-cooling-louvers');

  // ---------------------------------------------------------------- spinning fans
  const fans = [];
  const bladeGeo = unitBox;
  function makeFan(x, y, z, bladeLen, speed, name) {
    const g = new T.Group();
    g.name = name;
    g.position.set(x, y, z);
    g.userData = { floor: 'roof' };
    const hub = new T.Mesh(unitCyl, M.steel);
    hub.scale.set(0.14, 0.2, 0.14);
    hub.name = name + '-hub';
    hub.userData = { floor: 'roof' };
    g.add(hub);
    const blades = new T.InstancedMesh(bladeGeo, M.steel, 4);
    for (let k = 0; k < 4; k++) {
      const th = (k * Math.PI) / 2;
      dummy.position.set(Math.cos(th) * bladeLen * 0.55, 0, Math.sin(th) * bladeLen * 0.55);
      dummy.rotation.set(0.32, -th, 0);
      dummy.scale.set(bladeLen, 0.03, 0.2);
      dummy.updateMatrix();
      blades.setMatrixAt(k, dummy.matrix);
    }
    blades.instanceMatrix.needsUpdate = true;
    blades.computeBoundingSphere();
    blades.name = name + '-blades';
    blades.userData = { floor: 'roof' };
    g.add(blades);
    group.add(g);
    fans.push({ g, speed });
    return g;
  }
  const ctFanY = ctBase + ctH + 0.55 + 0.05;
  ctXs.forEach((x, i) => makeFan(x, ctFanY, ctZ, 0.82, (i % 2 ? -1 : 1) * 3.0, `roofmech-ct-fan-${i + 1}`));
  makeFan(plX, H + 0.5 + 1.5 + 0.06, plZ, 0.62, 9.0, 'roofmech-hvac-fan-1');
  makeFan(-plX, H + 0.5 + 1.5 + 0.06, plZ, 0.62, -9.0, 'roofmech-hvac-fan-2');

  // ---------------------------------------------------------------- vent stacks (instanced)
  const ventPos = [
    [-(HW - 5), 2], [-(HW - 5), 6], [-6, HD - 4.5],
    [2, HD - 3.5], [HW - 5, -2], [6, -(HD - 4)],
  ];
  inst(unitCyl, M.steel, ventPos.map(([x, z]) => ({
    p: [x, H + 0.45, z], s: [0.22, 0.9, 0.22],
  })), 'roofmech-vent-stacks');
  inst(unitCyl, M.steel, ventPos.map(([x, z]) => ({
    p: [x, H + 0.96, z], s: [0.36, 0.12, 0.36],
  })), 'roofmech-vent-caps');

  // ---------------------------------------------------------------- railing on parapet coping
  const railY0 = H + PARAPET + 0.08;        // coping top (facade.js)
  const railH = 1.0;
  const rx = HW - PT / 2, rz = HD - PT / 2; // parapet centerlines
  const posts = [];
  const nX = 20, nZ = 14;
  for (let j = 0; j <= nX; j++) {
    const x = -rx + (2 * rx * j) / nX;
    posts.push({ p: [x, railY0 + railH / 2, rz], s: [0.06, railH, 0.06] });
    posts.push({ p: [x, railY0 + railH / 2, -rz], s: [0.06, railH, 0.06] });
  }
  for (let j = 1; j < nZ; j++) {
    const z = -rz + (2 * rz * j) / nZ;
    posts.push({ p: [rx, railY0 + railH / 2, z], s: [0.06, railH, 0.06] });
    posts.push({ p: [-rx, railY0 + railH / 2, z], s: [0.06, railH, 0.06] });
  }
  inst(unitBox, M.steel, posts, 'roofmech-railing-posts');
  const rails = [];
  for (const dy of [railH, railH * 0.55]) {
    rails.push({ p: [0, railY0 + dy, rz], s: [W, 0.07, 0.07] });
    rails.push({ p: [0, railY0 + dy, -rz], s: [W, 0.07, 0.07] });
    rails.push({ p: [rx, railY0 + dy, 0], s: [0.07, 0.07, D - 2 * PT] });
    rails.push({ p: [-rx, railY0 + dy, 0], s: [0.07, 0.07, D - 2 * PT] });
  }
  inst(unitBox, M.steel, rails, 'roofmech-railing-rails');

  // ---------------------------------------------------------------- lightning rods
  // Main air terminal on the overrun cap; corner finials on the railing corner posts.
  const mx = -(ow / 2 - 0.7), mz = -(od / 2 - 0.7);
  const mastH = 3.4, mastTop = capTop + mastH;
  const mastGeo = new T.CylinderGeometry(0.035, 0.06, mastH, 8);
  const mast = new T.Mesh(mastGeo, M.steel);
  mast.position.set(mx, capTop + mastH / 2, mz);
  mast.name = 'roofmech-lightning-mast';
  mast.userData = { floor: 'roof' };
  mast.castShadow = true;
  group.add(mast);
  const rodH = 1.6;
  const corners = [[rx, rz], [rx, -rz], [-rx, rz], [-rx, -rz]];
  inst(unitCyl, M.steel, corners.map(([x, z]) => ({
    p: [x, railY0 + rodH / 2, z], s: [0.03, rodH, 0.03],
  })), 'roofmech-corner-rods');
  const coneGeo = new T.ConeGeometry(0.035, 0.22, 8);
  inst(coneGeo, M.steel, corners.slice(1, 3).map(([x, z]) => ({
    p: [x, railY0 + rodH + 0.11, z], s: [1, 1, 1],
  })), 'roofmech-corner-tips');

  // ---------------------------------------------------------------- aviation beacons (red, blinking)
  const redMat = own(M.beacon(0xff2a2a));
  const beaconBases = [
    { p: [mx, mastTop + 0.05, mz], s: [0.11, 0.1, 0.11] },
    { p: [rx, railY0 + rodH + 0.02, rz], s: [0.07, 0.08, 0.07] },
    { p: [-rx, railY0 + rodH + 0.02, rz], s: [0.07, 0.08, 0.07] },
  ];
  inst(unitCyl, M.steel, beaconBases, 'roofmech-beacon-bases');
  const mainBeacon = new T.Mesh(unitSphere, redMat);
  mainBeacon.scale.setScalar(0.17);
  mainBeacon.position.set(mx, mastTop + 0.22, mz);
  mainBeacon.name = 'roofmech-beacon-main';
  mainBeacon.userData = { floor: 'roof' };
  group.add(mainBeacon);
  inst(unitSphere, redMat, [
    { p: [rx, railY0 + rodH + 0.14, rz], s: [0.1, 0.1, 0.1] },
    { p: [-rx, railY0 + rodH + 0.14, rz], s: [0.1, 0.1, 0.1] },
  ], 'roofmech-beacons-corner');
  const tipGeo = new T.ConeGeometry(0.025, 0.28, 8);
  const tip = new T.Mesh(tipGeo, M.steel);
  tip.position.set(mx, mastTop + 0.53, mz);
  tip.name = 'roofmech-lightning-tip';
  tip.userData = { floor: 'roof' };
  group.add(tip);

  // ---------------------------------------------------------------- mount + API
  if (ctx.root) ctx.root.add(group);

  let clock = 0;
  // Accepts contract signature update(status, dt) or bare update(dt).
  const update = (status, dt) => {
    const d = typeof dt === 'number' ? dt : (typeof status === 'number' ? status : 0.016);
    clock += d;
    for (const f of fans) f.g.rotation.y += f.speed * d;
    const pulse = 0.5 + 0.5 * Math.sin(clock * 3.8);
    const blink = pulse * pulse * pulse;
    redMat.emissiveIntensity = 0.25 + 2.6 * blink;
    redMat.opacity = 0.55 + 0.45 * blink;
  };

  const dispose = () => {
    const geos = new Set();
    group.traverse((o) => {
      if (o.geometry) geos.add(o.geometry);
      if (o.isInstancedMesh && typeof o.dispose === 'function') o.dispose();
    });
    geos.forEach((g) => g.dispose());
    ownedMaterials.forEach((m) => m.dispose());
    if (ownLibrary) Object.values(M).forEach((m) => { if (m && m.isMaterial) m.dispose(); });
    if (group.parent) group.parent.remove(group);
  };

  return { group, update, dispose };
}
