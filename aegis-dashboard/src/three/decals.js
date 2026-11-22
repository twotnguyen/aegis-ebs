// decals.js — procedural CanvasTexture paint & signage for AEGIS Tower B01.
// Contract: src/three/CONTRACT.md — build(ctx) -> { group, dispose }.
// Contents (all painted on planes / instanced decal planes, < 1k triangles):
//   1. Per-floor interior markings: dashed zone boundaries + zoneCode labels,
//      corridor tile grout pattern + centre dashes (core footprint cut out).
//   2. Green evacuation floor arrows pointing at the core stair doors.
//   3. Elevator floor-number signs (F01..F10) above the two core doors/floor.
//   4. Parking-lot markings decal (stalls, numbers, aisle arrows, P, accessible
//      stall) aligned with environment.js lot geometry.
//   5. Zebra crosswalks: near sidewalk (road crossing continuation) + plaza
//      approach on the entrance axis.
//   6. Lobby AEGIS floor medallion + shield logo on the core front wall.
//   7. Decorative tile ring (hoa văn gạch) around the plaza fountain.
// Textures are created lazily inside build() and only when a DOM exists
// (typeof document guard) — headless builds get flat fallback materials.
// All dimensions come from config.js; outdoor layout constants mirror
// environment.js (plaza z[15,46], lot x[-92,-50] z[16,46], sidewalk z[46,52]).
import * as THREE from 'three';
import * as configNS from './config.js';
import { makeMaterials } from './materials.js';

const pad = (n) => String(n).padStart(2, '0');
const FINISH = 0.06;      // interior.js finished-floor thickness above slab top
const DECAL_DY = 0.004;   // paint lift above the finished floor (no z-fight)

export function build(ctx = {}) {
  const T = ctx.THREE || THREE;
  const cfg = ctx.config || configNS;
  const { BUILDING, floorY, zoneRect, zoneCode } = cfg;
  const M = ctx.materials || makeMaterials();
  const ownsLib = !ctx.materials;

  const F = BUILDING.floors;
  const hw = BUILDING.width / 2 - BUILDING.zoneInset;   // 20.4 interior half X
  const hd = BUILDING.depth / 2 - BUILDING.zoneInset;   // 13.4 interior half Z
  const gx = BUILDING.zoneGap / 2, gz = BUILDING.zoneGap / 2;
  const cw = BUILDING.coreWidth / 2, cd = BUILDING.coreDepth / 2;
  const HD = BUILDING.depth / 2;                        // facade front z = 15

  const group = new T.Group();
  group.name = 'decals';
  const sub = (name) => { const g = new T.Group(); g.name = name; group.add(g); return g; };
  const gFloors = sub('decals-floors');
  const gSigns = sub('decals-elevator-signs');
  const gParking = sub('decals-parking');
  const gPlaza = sub('decals-plaza');
  const gLobby = sub('decals-lobby');

  // ---- owned resources (dispose frees ONLY these + fallback lib if owned) ----
  const ownedTex = [];
  const ownedMats = [];
  const ownMat = (m) => { ownedMats.push(m); return m; };

  const unitPlane = new T.PlaneGeometry(1, 1);          // shared by every decal
  const noRaycast = () => {};                           // paint never blocks picking

  // Lazy canvas texture factory — returns null headless (no DOM).
  function canvasTex(w, h, draw) {
    if (typeof document === 'undefined') return null;
    const cv = document.createElement('canvas');
    cv.width = w; cv.height = h;
    const g = cv.getContext('2d');
    if (!g) return null;
    draw(g, w, h);
    const tex = new T.CanvasTexture(cv);
    tex.colorSpace = T.SRGBColorSpace;
    tex.anisotropy = 4;
    ownedTex.push(tex);
    return tex;
  }

  // Paint material: canvas map when available, flat fallback otherwise.
  function paintMat(tex, fallback, opts = {}) {
    if (!tex) return fallback;
    return ownMat(new T.MeshStandardMaterial(Object.assign({
      map: tex, transparent: true, depthWrite: false,
      roughness: 0.8, metalness: 0,
      polygonOffset: true, polygonOffsetFactor: -2, polygonOffsetUnits: -2,
    }, opts)));
  }
  const basicFallback = (color, opacity) =>
    ownMat(new T.MeshBasicMaterial({ color, transparent: true, opacity, depthWrite: false }));

  function plane(parent, mat, name, w, h, x, y, z, o = {}) {
    const m = new T.Mesh(unitPlane, mat);
    m.name = name;
    m.scale.set(w, h, 1);
    m.position.set(x, y, z);
    if (o.flat) m.rotation.x = -Math.PI / 2;            // floor decal (canvas top = -Z)
    if (o.ry) m.rotation.y = o.ry;
    m.castShadow = false;
    m.receiveShadow = false;
    m.renderOrder = o.order ?? 2;
    m.raycast = noRaycast;
    m.userData = Object.assign({ module: 'decals', kind: o.kind || name }, o.ud || {});
    parent.add(m);
    return m;
  }

  // =====================================================================
  // 1) Per-floor interior markings (one 1024px texture per floor)
  // =====================================================================
  const PW = 2 * hw, PD = 2 * hd;                       // 40.8 x 26.8 m
  const CW = 1024, CH = Math.round(CW * PD / PW);       // canvas aspect matches plane
  const floorMats = [];
  for (let i = 0; i < F; i++) {
    const code = (zi) => zoneCode(i, zi);
    const tex = canvasTex(CW, CH, (g) => {
      const sx = CW / PW, sy = CH / PD;
      const X = (x) => (x + hw) * sx, Z = (z) => (z + hd) * sy;
      g.clearRect(0, 0, CW, CH);

      // corridor cross: subtle tile grout + edge lines + centre dashes
      g.save();
      g.beginPath();
      g.rect(X(-hw), Z(-gz), PW * sx, BUILDING.zoneGap * sy);
      g.rect(X(-gx), Z(-hd), BUILDING.zoneGap * sx, (hd - gz) * sy);
      g.rect(X(-gx), Z(gz), BUILDING.zoneGap * sx, (hd - gz) * sy);
      g.clip();
      g.strokeStyle = 'rgba(71,85,105,0.10)';           // hoa văn gạch: 0.85 m tiles
      g.lineWidth = 1.5;
      const step = 0.85 * sx;
      for (let px = 0; px <= CW; px += step) { g.beginPath(); g.moveTo(px, 0); g.lineTo(px, CH); g.stroke(); }
      for (let py = 0; py <= CH; py += step) { g.beginPath(); g.moveTo(0, py); g.lineTo(CW, py); g.stroke(); }
      g.restore();
      g.strokeStyle = 'rgba(100,116,139,0.35)';
      g.lineWidth = 2;
      for (const zz of [-gz, gz]) { g.beginPath(); g.moveTo(X(-hw), Z(zz)); g.lineTo(X(hw), Z(zz)); g.stroke(); }
      for (const xx of [-gx, gx]) {
        g.beginPath(); g.moveTo(X(xx), Z(-hd)); g.lineTo(X(xx), Z(-gz)); g.stroke();
        g.beginPath(); g.moveTo(X(xx), Z(gz)); g.lineTo(X(xx), Z(hd)); g.stroke();
      }
      g.strokeStyle = 'rgba(148,163,184,0.55)';
      g.lineWidth = 3;
      g.setLineDash([16, 12]);
      g.beginPath(); g.moveTo(X(-hw), Z(0)); g.lineTo(X(hw), Z(0)); g.stroke();
      g.beginPath(); g.moveTo(X(0), Z(-hd)); g.lineTo(X(0), Z(hd)); g.stroke();
      g.setLineDash([]);

      // hazard stripes at the four core approaches (yellow / dark diagonal)
      const hazard = (hxc, hzc, w, d) => {
        const px = X(hxc - w / 2), py = Z(hzc - d / 2), pw = w * sx, ph = d * sy;
        g.save();
        g.beginPath(); g.rect(px, py, pw, ph); g.clip();
        g.fillStyle = 'rgba(250,204,21,0.35)'; g.fillRect(px, py, pw, ph);
        g.strokeStyle = 'rgba(17,24,39,0.55)';
        g.lineWidth = Math.max(3, 0.12 * sx);
        for (let p = -d; p <= w + d; p += 0.45) {
          const x0 = X(hxc - w / 2 + p), y0 = Z(hzc - d / 2);
          g.beginPath(); g.moveTo(x0, y0); g.lineTo(x0 + d * sx, y0 + d * sy); g.stroke();
        }
        g.restore();
      };
      hazard(cw + 0.55, 0, 1.2, 1.6); hazard(-cw - 0.55, 0, 1.2, 1.6);
      hazard(0, cd + 0.55, 1.6, 1.2); hazard(0, -cd - 0.55, 1.6, 1.2);

      // wayfinding: green egress captions along the corridor, translucent floor no.
      g.save();
      g.textAlign = 'center'; g.textBaseline = 'middle';
      g.fillStyle = 'rgba(22,163,74,0.75)';
      g.font = `700 ${Math.round(0.72 * sx)}px Arial, Helvetica, sans-serif`;
      g.fillText('→ EXIT / THOÁT HIỂM', X(-hw * 0.52), Z(-gz * 0.45));
      g.fillText('EXIT / THOÁT HIỂM ←', X(hw * 0.52), Z(gz * 0.45));
      g.fillStyle = 'rgba(100,116,139,0.22)';
      g.font = `700 ${Math.round(1.6 * sx)}px Arial, Helvetica, sans-serif`;
      g.fillText(`F${pad(i + 1)}`, X(-hw + 3.2), Z(-hd + 2.2));
      g.fillText(`F${pad(i + 1)}`, X(hw - 3.2), Z(hd - 2.2));
      g.restore();

      // zone boundaries (dashed) + zoneCode labels at the corridor-facing corner
      g.font = `700 ${Math.round(1.3 * sx)}px Arial, Helvetica, sans-serif`;
      for (let zi = 0; zi < BUILDING.zonesPerFloor; zi++) {
        const r = zoneRect(zi);
        const sgnX = (zi % 2 === 0) ? -1 : 1, sgnZ = (zi < 2) ? -1 : 1;
        const x0 = r.x - r.w / 2 + 0.15, x1 = r.x + r.w / 2 - 0.15;
        const z0 = r.z - r.d / 2 + 0.15, z1 = r.z + r.d / 2 - 0.15;
        g.strokeStyle = 'rgba(148,163,184,0.5)';
        g.lineWidth = 2.5;
        g.setLineDash([10, 8]);
        g.strokeRect(X(x0), Z(z0), (x1 - x0) * sx, (z1 - z0) * sy);
        g.setLineDash([]);
        const lx = r.x - sgnX * (r.w / 2 - 2.2);
        const lz = r.z - sgnZ * (r.d / 2 - 1.6);
        g.fillStyle = 'rgba(100,116,139,0.6)';
        g.textAlign = sgnX < 0 ? 'right' : 'left';
        g.textBaseline = sgnZ < 0 ? 'bottom' : 'top';
        g.fillText(code(zi), X(lx), Z(lz));
      }

      // keep the structural core footprint transparent (walls/stairs live there)
      g.clearRect(X(-cw - 0.15), Z(-cd - 0.15), (2 * cw + 0.3) * sx, (2 * cd + 0.3) * sy);
    });
    floorMats.push(paintMat(tex, basicFallback(0x94a3b8, 0.15)));
  }

  // =====================================================================
  // 2) Evacuation arrows — one shared texture, one InstancedMesh (80 planes)
  //    Stair doors sit on the core west/east faces at z = 0 (safety.js).
  // =====================================================================
  const arrowTex = canvasTex(256, 256, (g) => {
    g.clearRect(0, 0, 256, 256);
    g.shadowColor = 'rgba(22,163,74,0.9)';
    g.shadowBlur = 14;
    g.fillStyle = '#16a34a';
    g.strokeStyle = 'rgba(255,255,255,0.85)';
    g.lineWidth = 6;
    g.beginPath();                                    // arrow points canvas-up
    g.moveTo(128, 26); g.lineTo(206, 128); g.lineTo(150, 128);
    g.lineTo(150, 228); g.lineTo(106, 228); g.lineTo(106, 128);
    g.lineTo(50, 128); g.closePath();
    g.fill(); g.stroke();
  });
  const arrowMat = arrowTex
    ? ownMat(new T.MeshStandardMaterial({
        map: arrowTex, transparent: true, depthWrite: false, roughness: 0.6,
        emissive: 0x16a34a, emissiveMap: arrowTex, emissiveIntensity: 0.35,
        polygonOffset: true, polygonOffsetFactor: -2, polygonOffsetUnits: -2,
      }))
    : M.exitSign;                                     // shared lib fallback (not owned)

  const arrowItems = [];
  // Các mũi tên nằm trên 2 nhánh hành lang, khoảng cách lấy theo core/zoneRect.
  const ax = cw + (hw - cw) * 0.28, axFar = cw + (hw - cw) * 0.62;
  const az = cd + (hd - cd) * 0.30, azFar = cd + (hd - cd) * 0.60;
  for (let i = 0; i < F; i++) {
    const y = floorY(i) + FINISH + DECAL_DY;
    // X corridor → stair doors at x = ±coreWidth/2
    for (const [x, dx] of [[-ax, 1], [-axFar, 1], [ax, -1], [axFar, -1]])
      arrowItems.push({ x, y, z: 0, dx, dz: 0, floor: i });
    // Z corridors → the X corridor (then stairs)
    for (const [z, dz] of [[-az, 1], [-azFar, 1], [az, -1], [azFar, -1]])
      arrowItems.push({ x: 0, y, z, dx: 0, dz, floor: i });
  }
  const arrows = new T.InstancedMesh(unitPlane, arrowMat, arrowItems.length);
  arrows.name = 'decals-exit-arrows';
  {
    const dummy = new T.Object3D();
    arrowItems.forEach((it, k) => {
      dummy.position.set(it.x, it.y, it.z);
      // canvas-up maps to -Z after rotateX(-90°); ry aims it along (dx,dz)
      dummy.rotation.set(-Math.PI / 2, 0, Math.atan2(-it.dx, -it.dz));
      dummy.scale.set(0.85, 0.85, 1);
      dummy.updateMatrix();
      arrows.setMatrixAt(k, dummy.matrix);
    });
    arrows.instanceMatrix.needsUpdate = true;
    arrows.computeBoundingSphere();
  }
  arrows.castShadow = false;
  arrows.renderOrder = 2;
  arrows.raycast = noRaycast;
  arrows.userData = { module: 'decals', kind: 'exit-arrows', floor: 'all', instances: arrowItems.length };
  gFloors.add(arrows);

  // =====================================================================
  // 2b) Hazard stripe decals at the four zone doorways on every floor
  //     (one shared texture + one InstancedMesh). Position follows zoneRect.
  // =====================================================================
  const hazardTex = canvasTex(128, 64, (g, w, h) => {
    g.fillStyle = 'rgba(250,204,21,0.85)'; g.fillRect(0, 0, w, h);
    g.strokeStyle = 'rgba(17,24,39,0.9)'; g.lineWidth = 10;
    for (let p = -h; p < w + h; p += 22) {
      g.beginPath(); g.moveTo(p, 0); g.lineTo(p + h, h); g.stroke();
    }
  });
  const hazardMat = hazardTex
    ? ownMat(new T.MeshStandardMaterial({
        map: hazardTex, transparent: true, depthWrite: false, roughness: 0.8,
        polygonOffset: true, polygonOffsetFactor: -2, polygonOffsetUnits: -2,
      }))
    : basicFallback(0xfacc15, 0.55);
  const hazardItems = [];
  for (let i = 0; i < F; i++) {
    const y = floorY(i) + FINISH + DECAL_DY + 0.002;
    for (let zi = 0; zi < BUILDING.zonesPerFloor; zi++) {
      const r = zoneRect(zi);
      const sgnX = (zi % 2 === 0) ? -1 : 1;
      hazardItems.push({ x: r.x - sgnX * (r.w / 2 - 0.95), y, z: r.z, floor: i, zone: zi });
    }
  }
  const hazards = new T.InstancedMesh(unitPlane, hazardMat, hazardItems.length);
  hazards.name = 'decals-zone-hazard-stripes';
  {
    const dummy = new T.Object3D();
    hazardItems.forEach((it, k) => {
      dummy.position.set(it.x, it.y, it.z);
      dummy.rotation.set(-Math.PI / 2, 0, 0);
      dummy.scale.set(1.6, 0.55, 1);
      dummy.updateMatrix();
      hazards.setMatrixAt(k, dummy.matrix);
    });
    hazards.instanceMatrix.needsUpdate = true;
    hazards.computeBoundingSphere();
  }
  hazards.castShadow = false;
  hazards.renderOrder = 3;
  hazards.raycast = noRaycast;
  hazards.userData = { module: 'decals', kind: 'hazard-stripes', floor: 'all', instances: hazardItems.length };
  gFloors.add(hazards);

  // =====================================================================
  // 3) Elevator floor-number signs above the core doors (structure.js:
  //    doorXs ±1.8, doorH 2.2, front wall face z = coreDepth/2).
  // =====================================================================
  const SIGN_W = 0.9, SIGN_H = SIGN_W * 160 / 256;
  const signZ = cd + 0.02;
  const doorDx = cw * 0.3;                  // structure.js elevator door centres (±1.8 với lõi 12 m)
  const signMats = [];
  for (let i = 0; i < F; i++) {
    const tex = canvasTex(256, 160, (g) => {
      g.clearRect(0, 0, 256, 160);
      g.fillStyle = 'rgba(17,24,39,0.92)';
      if (typeof g.roundRect === 'function') { g.beginPath(); g.roundRect(4, 4, 248, 152, 14); g.fill(); }
      else g.fillRect(4, 4, 248, 152);
      g.strokeStyle = 'rgba(56,189,248,0.8)';
      g.lineWidth = 3;
      if (typeof g.roundRect === 'function') { g.beginPath(); g.roundRect(4, 4, 248, 152, 14); g.stroke(); }
      else g.strokeRect(4, 4, 248, 152);
      g.textAlign = 'center'; g.textBaseline = 'middle';
      g.fillStyle = '#f8fafc';
      g.font = '700 62px Arial, Helvetica, sans-serif';
      g.fillText(`F${pad(i + 1)}`, 128, 62);
      g.fillStyle = '#7dd3fc';
      g.font = '600 26px Arial, Helvetica, sans-serif';
      try { g.letterSpacing = '3px'; } catch (e) { /* older canvas */ }
      g.fillText(`TẦNG ${i + 1}`, 128, 122);
    });
    signMats.push(tex
      ? ownMat(new T.MeshStandardMaterial({
          map: tex, emissive: 0xffffff, emissiveMap: tex, emissiveIntensity: 0.75,
          transparent: true, depthWrite: false, roughness: 0.4,
        }))
      : ownMat(M.emissive(0x9fc6e8, 0.8)));
  }
  const signDummy = new T.Object3D();
  for (let i = 0; i < F; i++) {
    const im = new T.InstancedMesh(unitPlane, signMats[i], 2);
    im.name = `decals-elevator-sign-F${pad(i + 1)}`;
    [-doorDx, doorDx].forEach((dx, k) => {
      signDummy.position.set(dx, floorY(i) + 2.62, signZ);
      signDummy.rotation.set(0, 0, 0);
      signDummy.scale.set(SIGN_W, SIGN_H, 1);
      signDummy.updateMatrix();
      im.setMatrixAt(k, signDummy.matrix);
    });
    im.instanceMatrix.needsUpdate = true;
    im.computeBoundingSphere();
    im.castShadow = false;
    im.renderOrder = 3;
    im.raycast = noRaycast;
    im.userData = { module: 'decals', kind: 'elevator-sign', floor: i, zoneCode: `F${pad(i + 1)}-CORE` };
    gSigns.add(im);
  }

  // =====================================================================
  // 4) Parking lot markings (environment.js: lot x[-92,-50] z[16,46],
  //    stall rows centred z 36.75 / 25.25, aisle z 31, driveway x ≈ -71).
  // =====================================================================
  // environment.js (không thuộc module này) giữ bãi đỗ cố định x[-92,-50] z[16,46].
  // Các hằng số cảnh quan này chỉ để khớp bãi đỗ, KHÔNG phải kích thước tòa nhà.
  const LOT_X0 = -92, LOT_X1 = -50, LOT_Z0 = 16, LOT_Z1 = 46;
  const LOT_W = LOT_X1 - LOT_X0, LOT_D = LOT_Z1 - LOT_Z0;
  const LOT_X = (LOT_X0 + LOT_X1) / 2, LOT_Z = (LOT_Z0 + LOT_Z1) / 2;
  const parkTex = canvasTex(1050, 750, (g, w, h) => {
    const sx = w / LOT_W, sy = h / LOT_D;
    const X = (x) => (x - LOT_X0) * sx, Z = (z) => (z - LOT_Z0) * sy;
    g.clearRect(0, 0, w, h);
    g.strokeStyle = 'rgba(233,234,236,0.92)';
    g.lineWidth = Math.max(2, 0.12 * sx);
    for (let i = 0; i <= 14; i++) {                   // stall dividers, both rows
      const x = -90 + i * 2.7;
      g.beginPath(); g.moveTo(X(x), Z(34)); g.lineTo(X(x), Z(39.5)); g.stroke();
      g.beginPath(); g.moveTo(X(x), Z(22.5)); g.lineTo(X(x), Z(28)); g.stroke();
    }
    for (const z of [39.5, 22.5]) {                   // row end caps
      g.beginPath(); g.moveTo(X(-90), Z(z)); g.lineTo(X(-52.2), Z(z)); g.stroke();
    }
    g.strokeStyle = 'rgba(216,184,74,0.9)';           // aisle centre dashes
    g.lineWidth = Math.max(2, 0.15 * sx);
    g.setLineDash([2.6 * sx, 3.4 * sx]);
    g.beginPath(); g.moveTo(X(-89), Z(31)); g.lineTo(X(-53), Z(31)); g.stroke();
    g.setLineDash([]);
    const aisleArrow = (x, dir) => {                  // white direction arrows
      g.fillStyle = 'rgba(233,234,236,0.9)';
      g.beginPath();
      g.moveTo(X(x + dir * 1.5), Z(31));
      g.lineTo(X(x - dir * 0.6), Z(31 - 0.8));
      g.lineTo(X(x - dir * 0.6), Z(31 - 0.28));
      g.lineTo(X(x - dir * 1.5), Z(31 - 0.28));
      g.lineTo(X(x - dir * 1.5), Z(31 + 0.28));
      g.lineTo(X(x - dir * 0.6), Z(31 + 0.28));
      g.lineTo(X(x - dir * 0.6), Z(31 + 0.8));
      g.closePath(); g.fill();
    };
    aisleArrow(-80, 1); aisleArrow(-62, -1);
    g.fillStyle = 'rgba(233,234,236,0.55)';           // stall numbers
    g.font = `600 ${Math.round(0.55 * sx)}px Arial, Helvetica, sans-serif`;
    g.textAlign = 'center'; g.textBaseline = 'middle';
    for (let i = 0; i < 14; i++) {
      const x = -88.65 + i * 2.7;
      g.fillText(pad(i + 1), X(x), Z(38.8));
      g.fillText(pad(i + 1), X(x), Z(23.2));
    }
    const badge = (x, z, s) => {                      // blue rounded square
      g.fillStyle = 'rgba(37,99,235,0.9)';
      const px = X(x) - s * sx / 2, py = Z(z) - s * sy / 2;
      if (typeof g.roundRect === 'function') { g.beginPath(); g.roundRect(px, py, s * sx, s * sy, 6); g.fill(); }
      else g.fillRect(px, py, s * sx, s * sy);
    };
    badge(-71, 43.2, 2.4);                            // P at the driveway entry
    g.fillStyle = '#ffffff';
    g.font = `700 ${Math.round(1.6 * sx)}px Arial, Helvetica, sans-serif`;
    g.fillText('P', X(-71), Z(43.2));
    badge(-69.75, 36.75, 1.7);                        // accessible stall (row N, #08)
    g.strokeStyle = '#ffffff'; g.fillStyle = '#ffffff';
    g.lineWidth = Math.max(2, 0.12 * sx);
    const ax = X(-69.75), az = Z(36.75), u = sx;      // simplified wheelchair glyph
    g.beginPath(); g.arc(ax + 0.18 * u, az - 0.52 * u, 0.16 * u, 0, Math.PI * 2); g.fill();
    g.beginPath();
    g.moveTo(ax + 0.18 * u, az - 0.3 * u); g.lineTo(ax + 0.18 * u, az + 0.05 * u);
    g.lineTo(ax + 0.5 * u, az + 0.05 * u); g.lineTo(ax + 0.62 * u, az + 0.4 * u);
    g.stroke();
    g.beginPath(); g.arc(ax - 0.05 * u, az + 0.28 * u, 0.34 * u, Math.PI * 0.45, Math.PI * 1.9); g.stroke();
  });
  const parkMat = paintMat(parkTex, basicFallback(0xe9eaec, 0.5), { roughness: 0.7 });
  plane(gParking, parkMat, 'decals-parking-markings', LOT_W, LOT_D, LOT_X, 0.045, LOT_Z,
    { flat: true, kind: 'parking-markings', ud: { area: 'parking' } });

  // =====================================================================
  // 5) Zebra crosswalks on the entrance axis (x = 0): sidewalk z[46,52]
  //    continues the road zebra (environment.js), plaza approach z[41.5,45.4].
  // =====================================================================
  const zebraTex = canvasTex(256, 432, (g, w, h) => {
    g.clearRect(0, 0, w, h);
    g.fillStyle = 'rgba(233,234,236,0.92)';
    const period = (0.95 / 6) * h, stripe = (0.55 / 6) * h;
    for (let k = 0; k < 6; k++) g.fillRect(0, (0.15 / 6) * h + k * period, w, stripe);
  });
  const zebraMat = paintMat(zebraTex, basicFallback(0xe9eaec, 0.5), { roughness: 0.7 });
  plane(gPlaza, zebraMat, 'decals-crosswalk-sidewalk', 3.6, 6, 0, 0.075, 49,
    { flat: true, kind: 'crosswalk', ud: { area: 'sidewalk' } });
  plane(gPlaza, zebraMat, 'decals-crosswalk-plaza', 3.6, 3.9, 0, 0.035, 43.45,
    { flat: true, kind: 'crosswalk', ud: { area: 'plaza' } });

  // =====================================================================
  // 6) Lobby: AEGIS floor medallion inside the entrance + shield logo on
  //    the core front wall (ground floor only).
  // =====================================================================
  const shieldPath = (g, cx, cy, s) => {
    g.beginPath();
    g.moveTo(cx, cy - 0.72 * s);
    g.quadraticCurveTo(cx + 0.45 * s, cy - 0.62 * s, cx + 0.6 * s, cy - 0.5 * s);
    g.lineTo(cx + 0.6 * s, cy + 0.05 * s);
    g.quadraticCurveTo(cx + 0.55 * s, cy + 0.45 * s, cx, cy + 0.72 * s);
    g.quadraticCurveTo(cx - 0.55 * s, cy + 0.45 * s, cx - 0.6 * s, cy + 0.05 * s);
    g.lineTo(cx - 0.6 * s, cy - 0.5 * s);
    g.quadraticCurveTo(cx - 0.45 * s, cy - 0.62 * s, cx, cy - 0.72 * s);
    g.closePath();
  };
  const medallionTex = canvasTex(512, 512, (g) => {
    g.clearRect(0, 0, 512, 512);
    g.strokeStyle = 'rgba(100,116,139,0.8)'; g.lineWidth = 6;
    g.beginPath(); g.arc(256, 256, 240, 0, Math.PI * 2); g.stroke();
    g.strokeStyle = 'rgba(14,165,233,0.7)'; g.lineWidth = 3;
    g.beginPath(); g.arc(256, 256, 200, 0, Math.PI * 2); g.stroke();
    const ringText = 'AEGIS TOWER • B01 • SẢNH CHÍNH • ';
    g.fillStyle = 'rgba(71,85,105,0.85)';
    g.font = '600 27px Arial, Helvetica, sans-serif';
    g.textAlign = 'center'; g.textBaseline = 'middle';
    for (let k = 0; k < ringText.length; k++) {
      const a = (k / ringText.length) * Math.PI * 2 - Math.PI / 2;
      g.save();
      g.translate(256 + 220 * Math.cos(a), 256 + 220 * Math.sin(a));
      g.rotate(a + Math.PI / 2);
      g.fillText(ringText[k], 0, 0);
      g.restore();
    }
    shieldPath(g, 256, 205, 92);
    g.fillStyle = 'rgba(14,165,233,0.28)'; g.fill();
    g.strokeStyle = 'rgba(2,132,199,0.9)'; g.lineWidth = 5; g.stroke();
    g.fillStyle = 'rgba(51,65,85,0.9)';
    g.font = '700 56px Arial, Helvetica, sans-serif';
    try { g.letterSpacing = '8px'; } catch (e) { /* older canvas */ }
    g.fillText('AEGIS', 256, 342);
  });
  const medallionMat = paintMat(medallionTex, basicFallback(0x38bdf8, 0.3), { roughness: 0.6 });
  plane(gLobby, medallionMat, 'decals-lobby-medallion', 3.4, 3.4, 0, FINISH + DECAL_DY + 0.002, HD - BUILDING.zoneInset - 2,
    { flat: true, kind: 'lobby-logo', ud: { floor: 0 } });

  const wallLogoTex = canvasTex(512, 256, (g) => {
    g.clearRect(0, 0, 512, 256);
    shieldPath(g, 118, 128, 86);
    g.fillStyle = 'rgba(56,189,248,0.3)'; g.fill();
    g.strokeStyle = '#7dd3fc'; g.lineWidth = 5; g.stroke();
    g.textAlign = 'center'; g.textBaseline = 'middle';
    const grad = g.createLinearGradient(0, 60, 0, 150);
    grad.addColorStop(0, '#ffffff'); grad.addColorStop(1, '#bae6fd');
    g.fillStyle = grad;
    g.font = '700 74px Arial, Helvetica, sans-serif';
    try { g.letterSpacing = '6px'; } catch (e) { /* older canvas */ }
    g.fillText('AEGIS', 322, 104);
    g.fillStyle = 'rgba(186,230,253,0.9)';
    g.font = '600 27px Arial, Helvetica, sans-serif';
    g.fillText('TOWER B01', 322, 168);
  });
  const wallLogoMat = wallLogoTex
    ? ownMat(new T.MeshStandardMaterial({
        map: wallLogoTex, emissive: 0xffffff, emissiveMap: wallLogoTex,
        emissiveIntensity: 0.9, transparent: true, depthWrite: false, roughness: 0.4,
      }))
    : ownMat(M.emissive(0x38bdf8, 0.7));
  plane(gLobby, wallLogoMat, 'decals-lobby-wall-logo', 2.2, 1.1, 0, 1.75, cd + 0.03,
    { kind: 'lobby-logo', order: 3, ud: { floor: 0, part: 'core-wall' } });

  // =====================================================================
  // 7) Decorative tile ring around the plaza fountain (environment.js:
  //    basin centre (0,33) r≈4.1; ring r 4.35..5.7, benches clear at r 7.6).
  // =====================================================================
  const RING_PLANE = 11.6;
  const ringTex = canvasTex(512, 512, (g) => {
    g.clearRect(0, 0, 512, 512);
    const cx = 256, cy = 256, ppm = 512 / RING_PLANE;
    const r0 = 4.35 * ppm, r1 = 5.7 * ppm, N = 28;
    for (let k = 0; k < N; k++) {
      const a0 = (k / N) * Math.PI * 2, a1 = ((k + 1) / N) * Math.PI * 2;
      g.beginPath();
      g.arc(cx, cy, r1, a0 + 0.008, a1 - 0.008);
      g.arc(cx, cy, r0, a1 - 0.008, a0 + 0.008, true);
      g.closePath();
      g.fillStyle = k % 2 ? 'rgba(185,190,196,0.5)' : 'rgba(226,229,233,0.5)';
      g.fill();
      g.strokeStyle = 'rgba(60,66,74,0.55)';
      g.lineWidth = 2;
      g.stroke();
    }
    g.strokeStyle = 'rgba(100,116,139,0.7)'; g.lineWidth = 3;
    for (const r of [r0, r1]) { g.beginPath(); g.arc(cx, cy, r, 0, Math.PI * 2); g.stroke(); }
  });
  const ringMat = paintMat(ringTex, basicFallback(0xb9bec4, 0.25), { roughness: 0.9 });
  plane(gPlaza, ringMat, 'decals-fountain-tile-ring', RING_PLANE, RING_PLANE, 0, 0.035, 33,
    { flat: true, kind: 'tile-pattern', ud: { area: 'plaza' } });

  // ---- per-floor grouping (floor decal planes; camera can isolate by floor) ----
  for (let i = 0; i < F; i++) {
    const fg = new T.Group();
    fg.name = `decals-floor-F${pad(i + 1)}`;
    fg.userData = { floor: i };
    gFloors.add(fg);
    plane(fg, floorMats[i], `decals-floor-markings-F${pad(i + 1)}`, PW, PD,
      0, floorY(i) + FINISH + DECAL_DY, 0,
      { flat: true, kind: 'floor-markings', ud: { floor: i } });
  }

  // ---- stats + wiring ----
  let triangles = 0;
  group.traverse((o) => {
    if (!o.isMesh || !o.geometry) return;
    const g = o.geometry;
    const per = g.index ? g.index.count / 3 : (g.attributes.position ? g.attributes.position.count / 3 : 0);
    triangles += per * (o.isInstancedMesh ? o.count : 1);
  });
  group.userData = {
    module: 'decals',
    stats: {
      floors: F,
      exitArrows: arrowItems.length,
      hazardDecals: hazardItems.length,
      elevatorSigns: F * 2,
      textures: ownedTex.length,
      triangles: Math.round(triangles),
    },
  };
  if (ctx.root && ctx.root.add) ctx.root.add(group);

  // ---- dispose: own geometries/textures/materials only; shared library
  //      materials are freed only when this build created the library. ----
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
    ownedTex.forEach((t) => t.dispose());
    ownedMats.forEach((m) => m.dispose());
    if (ownsLib) for (const v of Object.values(M)) if (v && v.isMaterial) v.dispose();
    group.removeFromParent();
  }

  return { group, dispose };
}
