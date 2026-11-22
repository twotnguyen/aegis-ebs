// facade.js — AEGIS Tower curtain-wall facade.
// Per-floor glass bands (vision + spandrel), instanced mullion grid, ground-floor
// entrance lobby (canopy + glass doors + columns), roof parapet, "AEGIS TOWER"
// signage (canvas-texture planes) and a pulsing shield logo beacon on the roof.
// Contract: build(ctx) -> { group, update, dispose }. All dims from config.js.
import * as THREE from 'three';
import { BUILDING, floorY, pitch, totalHeight } from './config.js';
import { makeMaterials } from './materials.js';

export function build(ctx) {
  const T = (ctx && ctx.THREE) || THREE;
  const B = (ctx && ctx.config && (ctx.config.BUILDING || ctx.config)) || BUILDING;
  let ownLibrary = false;
  let M = ctx && ctx.materials;
  if (!M) { M = makeMaterials(); ownLibrary = true; }

  const P = (B === BUILDING) ? pitch() : B.floorHeight + B.slabThickness;
  const yOf = (B === BUILDING) ? floorY : (i) => i * P;
  const H = (B === BUILDING) ? totalHeight() : B.floors * P;

  const W = B.width, D = B.depth, FH = B.floorHeight, SLAB = B.slabThickness;
  const HW = W / 2, HD = D / 2;
  const PARAPET = B.roofParapet;

  // Even bay counts so the entrance centres exactly on bay boundaries.
  const nX = Math.max(4, 2 * Math.round(W / 3));   // bays along X (front/back)
  const nZ = Math.max(4, 2 * Math.round(D / 3));   // bays along Z (sides)
  const bayW = W / nX, bayD = D / nZ;
  const SILL = 0.9;                                 // spandrel upstand height
  const VISION = FH - SILL;                         // clear glass height
  const ENT_BAYS = Math.max(4, 2 * Math.round(W * 0.06)); // ~12% of frontage, even
  const entW = ENT_BAYS * bayW, entHalf = entW / 2;
  const entJ0 = nX / 2 - ENT_BAYS / 2;              // first skipped bay (floor 0 front)
  const DOOR_H = 2.9;
  const TRANSOM = SILL + VISION * 0.55;             // intermediate curtain-wall rail
  const FIN_STEP = 3;                               // brise-soleil on every Nth side bay
  const FIN_D = Math.min(0.9, D * 0.02);            // fin projection, from depth

  const group = new T.Group();
  group.name = 'facade';

  const ownedMaterials = [];   // materials created here (never dispose shared library)
  const ownedTextures = [];
  const bandMat = new T.MeshStandardMaterial({
    color: 0x7dd3fc, emissive: 0x38bdf8, emissiveIntensity: 0.5,
    roughness: 0.35, metalness: 0.2,
  });
  ownedMaterials.push(bandMat); // slim illuminated spandrel band
  const dummy = new T.Object3D();
  const unitBox = new T.BoxGeometry(1, 1, 1);
  const unitPlane = new T.PlaneGeometry(1, 1);

  // Face frames: local X = along face, local Y = up, local Z = outward normal.
  const FACES = [
    { key: 'front', th: 0,            fx: 0,   fz: HD,  n: nX, bay: bayW, half: HW },
    { key: 'back',  th: Math.PI,      fx: 0,   fz: -HD, n: nX, bay: bayW, half: HW },
    { key: 'right', th: Math.PI / 2,  fx: HW,  fz: 0,   n: nZ, bay: bayD, half: HD },
    { key: 'left',  th: -Math.PI / 2, fx: -HW, fz: 0,   n: nZ, bay: bayD, half: HD },
  ];
  const pushM = (arr, f, t, y, o, sx, sy, sz) => {
    const c = Math.cos(f.th), s = Math.sin(f.th);
    dummy.position.set(f.fx + t * c + o * s, y, f.fz - t * s + o * c);
    dummy.rotation.set(0, f.th, 0);
    dummy.scale.set(sx, sy, sz);
    dummy.updateMatrix();
    arr.push(dummy.matrix.clone());
  };
  const makeInstanced = (geo, mat, matrices, name, userData) => {
    const im = new T.InstancedMesh(geo, mat, matrices.length);
    for (let k = 0; k < matrices.length; k++) im.setMatrixAt(k, matrices[k]);
    im.instanceMatrix.needsUpdate = true;
    im.computeBoundingSphere();
    im.name = name;
    im.userData = userData;
    return im;
  };
  const box = (mat, sx, sy, sz, x, y, z, name, userData) => {
    const m = new T.Mesh(unitBox, mat);
    m.scale.set(sx, sy, sz);
    m.position.set(x, y, z);
    m.name = name || 'facade-box';
    m.userData = userData || {};
    return m;
  };

  // ---------------------------------------------------------------- floors
  for (let i = 0; i < B.floors; i++) {
    const base = yOf(i);
    const vision = [], spandrel = [], mullions = [], fins = [], band = [];
    const isEntranceFloor = (i === 0);

    for (const f of FACES) {
      const entranceFace = isEntranceFloor && f.key === 'front';
      // --- glass panes (per bay) ---
      for (let j = 0; j < f.n; j++) {
        const t = -f.half + (j + 0.5) * f.bay;
        const inEntrance = entranceFace && j >= entJ0 && j < entJ0 + ENT_BAYS;
        if (!inEntrance) {
          pushM(vision, f, t, base + SILL + VISION / 2, 0, f.bay - 0.08, VISION - 0.08, 1);
          pushM(spandrel, f, t, base + SILL / 2, 0, f.bay - 0.08, SILL - 0.06, 1);
        }
        // slab-edge spandrel band runs full width (above entrance head too)
        pushM(spandrel, f, t, base + FH + SLAB / 2, 0, f.bay - 0.08, SLAB - 0.04, 1);
      }
      // --- vertical mullions (bay boundaries, incl. corners) ---
      for (let j = 0; j <= f.n; j++) {
        if (entranceFace && j > entJ0 && j < entJ0 + ENT_BAYS) continue; // portal jambs instead
        const t = -f.half + j * f.bay;
        pushM(mullions, f, t, base + P / 2, 0.03, 0.09, P, 0.16);
      }
      // --- horizontal mullions: base, sill, head, mid transom ---
      const headY = base + FH, sillY = base + SILL, baseY = base + 0.05;
      pushM(mullions, f, 0, baseY, 0.03, f.half * 2, 0.1, 0.16);
      pushM(mullions, f, 0, headY, 0.03, f.half * 2, 0.1, 0.16);
      if (entranceFace) {
        const segLen = f.half - entHalf, segC = entHalf + segLen / 2;
        for (const yy of [sillY, base + TRANSOM]) {
          pushM(mullions, f, -segC, yy, 0.03, segLen, 0.1, 0.16);
          pushM(mullions, f, segC, yy, 0.03, segLen, 0.1, 0.16);
        }
      } else {
        pushM(mullions, f, 0, sillY, 0.03, f.half * 2, 0.1, 0.16);
        pushM(mullions, f, 0, base + TRANSOM, 0.03, f.half * 2, 0.08, 0.14);
      }
      // --- brise-soleil: vertical fins on the two side elevations ---
      if (f.key === 'left' || f.key === 'right') {
        for (let j = FIN_STEP / 2; j < f.n; j += FIN_STEP) {
          const t = -f.half + (j + 0.5) * f.bay;
          pushM(fins, f, t, base + SILL + VISION / 2, FIN_D / 2 + 0.12,
            0.14, VISION, FIN_D);
        }
      }
      // --- illuminated spandrel band at the slab edge, runs the full face ---
      pushM(band, f, 0, base + FH + SLAB + 0.03, 0.13,
        f.half * 2 - 0.2, 0.05, 0.07);
    }

    const tag = (part) => ({ floor: i, part });
    const fLabel = `F${String(i + 1).padStart(2, '0')}`;
    group.add(makeInstanced(unitPlane, M.glass, vision, `facade-glass-${fLabel}`, tag('curtain-wall')));
    group.add(makeInstanced(unitPlane, M.glassDark, spandrel, `facade-spandrel-${fLabel}`, tag('spandrel')));
    group.add(makeInstanced(unitBox, M.mullion, mullions, `facade-mullions-${fLabel}`, tag('mullions')));
    group.add(makeInstanced(unitBox, M.mullion, fins, `facade-sunfins-${fLabel}`, tag('brise-soleil')));
    group.add(makeInstanced(unitBox, bandMat, band, `facade-light-band-${fLabel}`, tag('spandrel-band')));
  }

  // ---------------------------------------------------------------- entrance (floor 0, front +Z)
  const entrance = new T.Group();
  entrance.name = 'facade-entrance';
  const entTag = { floor: 0, part: 'entrance' };
  const frontZ = HD;

  // Portal jambs + transom bar
  entrance.add(box(M.mullion, 0.2, FH, 0.24, -entHalf, FH / 2, frontZ + 0.06, 'entrance-jamb-L', entTag));
  entrance.add(box(M.mullion, 0.2, FH, 0.24, entHalf, FH / 2, frontZ + 0.06, 'entrance-jamb-R', entTag));
  entrance.add(box(M.mullion, entW, 0.14, 0.2, 0, DOOR_H + 0.07, frontZ + 0.06, 'entrance-transom-bar', entTag));

  // 4-6 glass door leaves + 2 sidelights, framed in mullion stiles/rails
  const nLeaves = Math.max(4, 2 * Math.round(entW / 4));
  const doorLeafW = Math.min(1.8, entW / (nLeaves + 1.5));
  const doorsW = doorLeafW * nLeaves;
  const sideW = (entW - doorsW) / 2;
  const doorGlassMat = M.glass;
  const addLeaf = (cx, w, h, y0, name) => {
    const g = new T.Mesh(unitPlane, doorGlassMat);
    g.scale.set(w - 0.1, h - 0.1, 1);
    g.position.set(cx, y0 + h / 2, frontZ + 0.1);
    g.name = name; g.userData = entTag;
    entrance.add(g);
    entrance.add(box(M.mullion, 0.07, h, 0.09, cx - w / 2 + 0.035, y0 + h / 2, frontZ + 0.1, name + '-stile-L', entTag));
    entrance.add(box(M.mullion, 0.07, h, 0.09, cx + w / 2 - 0.035, y0 + h / 2, frontZ + 0.1, name + '-stile-R', entTag));
    entrance.add(box(M.mullion, w, 0.09, 0.09, cx, y0 + h - 0.045, frontZ + 0.1, name + '-rail-T', entTag));
    entrance.add(box(M.mullion, w, 0.12, 0.09, cx, y0 + 0.06, frontZ + 0.1, name + '-rail-B', entTag));
  };
  for (let k = 0; k < nLeaves; k++) {
    const cx = -doorsW / 2 + (k + 0.5) * doorLeafW;
    addLeaf(cx, doorLeafW, DOOR_H, 0.02, `entrance-door-${k + 1}`);
    // pull handle
    entrance.add(box(M.steel, 0.04, 1.1, 0.06, cx + (k % 2 === 0 ? 0.5 : -0.5), 1.35, frontZ + 0.17, `entrance-handle-${k + 1}`, entTag));
  }
  addLeaf(-doorsW / 2 - sideW / 2, sideW, DOOR_H, 0.02, 'entrance-sidelight-L');
  addLeaf(doorsW / 2 + sideW / 2, sideW, DOOR_H, 0.02, 'entrance-sidelight-R');
  // transom glass above doors
  const transomH = FH - DOOR_H - 0.2;
  const transom = new T.Mesh(unitPlane, doorGlassMat);
  transom.scale.set(entW - 0.24, transomH, 1);
  transom.position.set(0, DOOR_H + 0.14 + transomH / 2, frontZ + 0.1);
  transom.name = 'entrance-transom-glass'; transom.userData = entTag;
  entrance.add(transom);

  // Canopy: slab + fascia + warm underside light strip + 2 steel columns
  const canopyY = FH + 0.15, canopyW = entW + 2.4, canopyD = Math.min(4.5, D * 0.076);
  entrance.add(box(M.concreteDark, canopyW, 0.16, canopyD, 0, canopyY, frontZ + canopyD / 2 - 0.15, 'entrance-canopy', entTag));
  entrance.add(box(M.mullion, canopyW + 0.1, 0.24, 0.1, 0, canopyY - 0.02, frontZ + canopyD - 0.2, 'entrance-canopy-fascia', entTag));
  const stripMat = M.emissive(0xffe9c4, 1.0); ownedMaterials.push(stripMat);
  entrance.add(box(stripMat, canopyW - 1.0, 0.05, 0.14, 0, canopyY - 0.1, frontZ + canopyD / 2, 'entrance-canopy-light', entTag));
  const colGeo = new T.CylinderGeometry(0.14, 0.14, canopyY - 0.08, 12);
  const baseGeo = new T.CylinderGeometry(0.3, 0.34, 0.07, 12);
  for (const sx of [-1, 1]) {
    const cx = sx * (entHalf + 0.9), cz = frontZ + canopyD - 0.5;
    const col = new T.Mesh(colGeo, M.steel);
    col.position.set(cx, (canopyY - 0.08) / 2, cz);
    col.name = `entrance-column-${sx < 0 ? 'L' : 'R'}`; col.userData = entTag;
    entrance.add(col);
    const bp = new T.Mesh(baseGeo, M.steel);
    bp.position.set(cx, 0.035, cz);
    bp.name = col.name + '-base'; bp.userData = entTag;
    entrance.add(bp);
  }
  // threshold plaza slab
  entrance.add(box(M.concreteDark, entW + 2.0, 0.06, canopyD + 0.4, 0, 0.03, frontZ + canopyD / 2 - 0.1, 'entrance-threshold', entTag));
  group.add(entrance);

  // ---------------------------------------------------------------- signage (canvas-texture planes)
  const makeSignTexture = (text) => {
    if (typeof document === 'undefined') return null; // headless fallback
    const cv = document.createElement('canvas');
    cv.width = 2048; cv.height = 128;
    const g = cv.getContext('2d');
    g.clearRect(0, 0, cv.width, cv.height);
    try { g.letterSpacing = '26px'; } catch (e) { /* older canvas */ }
    g.font = '700 96px Arial, Helvetica, sans-serif';
    g.textAlign = 'center'; g.textBaseline = 'middle';
    g.shadowColor = '#7dd3fc'; g.shadowBlur = 26;
    const grad = g.createLinearGradient(0, 10, 0, 118);
    grad.addColorStop(0, '#ffffff');
    grad.addColorStop(1, '#bae6fd');
    g.fillStyle = grad;
    g.fillText(text, cv.width / 2, cv.height / 2 + 4);
    g.shadowBlur = 0;
    g.fillText(text, cv.width / 2, cv.height / 2 + 4);
    const tex = new T.CanvasTexture(cv);
    tex.colorSpace = T.SRGBColorSpace;
    tex.anisotropy = 4;
    ownedTextures.push(tex);
    return tex;
  };
  const signTex = makeSignTexture('AEGIS TOWER');
  let signMat;
  if (signTex) {
    signMat = new T.MeshStandardMaterial({
      map: signTex, emissive: 0xffffff, emissiveMap: signTex,
      emissiveIntensity: 1.5, transparent: true, depthWrite: false,
      side: T.DoubleSide, roughness: 0.4,
    });
  } else {
    signMat = M.emissive(0x9fc6e8, 0.9);
  }
  ownedMaterials.push(signMat);
  const addSign = (w, x, y, z, rotY, name) => {
    const s = new T.Mesh(unitPlane, signMat);
    s.scale.set(w, w / 16, 1); // texture aspect 2048:128 = 16:1
    s.position.set(x, y, z);
    s.rotation.y = rotY;
    s.name = name;
    s.userData = { floor: B.floors - 1, part: 'signage' };
    group.add(s);
  };
  // main roof sign on front / back parapet faces — width capped by parapet height
  const roofSignW = Math.min(W * 0.42, PARAPET * 15.5);
  addSign(roofSignW, 0, H + PARAPET / 2, HD + 0.04, 0, 'sign-roof-front');
  addSign(roofSignW, 0, H + PARAPET / 2, -HD - 0.04, Math.PI, 'sign-roof-back');
  // same identity repeated on the two side elevations
  const sideSignW = roofSignW * 0.55;
  addSign(sideSignW, HW + 0.04, H + PARAPET / 2, 0, Math.PI / 2, 'sign-roof-right');
  addSign(sideSignW, -HW - 0.04, H + PARAPET / 2, 0, -Math.PI / 2, 'sign-roof-left');
  // entrance sign above canopy
  addSign(Math.min(entW * 0.8, W * 0.16), 0, FH + 0.85, HD + 0.16, 0, 'sign-entrance');

  // ---------------------------------------------------------------- roof parapet
  const roof = new T.Group();
  roof.name = 'facade-roof';
  const roofTag = { floor: B.floors - 1, part: 'parapet' };
  const pt = 0.25, py = H + PARAPET / 2;
  roof.add(box(M.concrete, W, PARAPET, pt, 0, py, HD - pt / 2, 'parapet-front', roofTag));
  roof.add(box(M.concrete, W, PARAPET, pt, 0, py, -HD + pt / 2, 'parapet-back', roofTag));
  roof.add(box(M.concrete, pt, PARAPET, D - 2 * pt, HW - pt / 2, py, 0, 'parapet-right', roofTag));
  roof.add(box(M.concrete, pt, PARAPET, D - 2 * pt, -HW + pt / 2, py, 0, 'parapet-left', roofTag));
  // metal coping caps
  const cy = H + PARAPET + 0.04;
  roof.add(box(M.mullion, W + 0.12, 0.08, pt + 0.12, 0, cy, HD - pt / 2, 'coping-front', roofTag));
  roof.add(box(M.mullion, W + 0.12, 0.08, pt + 0.12, 0, cy, -HD + pt / 2, 'coping-back', roofTag));
  roof.add(box(M.mullion, pt + 0.12, 0.08, D - 2 * pt + 0.12, HW - pt / 2, cy, 0, 'coping-right', roofTag));
  roof.add(box(M.mullion, pt + 0.12, 0.08, D - 2 * pt + 0.12, -HW + pt / 2, cy, 0, 'coping-left', roofTag));
  group.add(roof);

  // ---------------------------------------------------------------- logo beacon (shield + ring on mast)
  const beaconMat = M.beacon(0x38bdf8); ownedMaterials.push(beaconMat);
  const beaconGroup = new T.Group();
  beaconGroup.name = 'facade-beacon';
  const bx = -W / 4, bz = D / 4;
  const mastH = 2.4;
  const mastGeo = new T.CylinderGeometry(0.07, 0.09, mastH, 10);
  const mast = new T.Mesh(mastGeo, M.steel);
  mast.position.set(bx, H + mastH / 2, bz);
  mast.name = 'beacon-mast'; mast.userData = { floor: B.floors - 1, part: 'beacon' };
  beaconGroup.add(mast);

  const logoGroup = new T.Group();
  logoGroup.position.set(bx, H + mastH + 0.85, bz);
  logoGroup.userData = { floor: B.floors - 1, part: 'beacon' };
  // shield outline (AEGIS = shield), extruded, low-poly
  const sh = new T.Shape();
  sh.moveTo(0, 0.72);
  sh.quadraticCurveTo(0.45, 0.62, 0.6, 0.5);
  sh.lineTo(0.6, -0.05);
  sh.quadraticCurveTo(0.55, -0.45, 0, -0.72);
  sh.quadraticCurveTo(-0.55, -0.45, -0.6, -0.05);
  sh.lineTo(-0.6, 0.5);
  sh.quadraticCurveTo(-0.45, 0.62, 0, 0.72);
  const shieldGeo = new T.ExtrudeGeometry(sh, { depth: 0.16, bevelEnabled: false, curveSegments: 8 });
  shieldGeo.translate(0, 0, -0.08);
  const shield = new T.Mesh(shieldGeo, beaconMat);
  shield.name = 'beacon-shield'; shield.userData = logoGroup.userData;
  logoGroup.add(shield);
  const ringGeo = new T.TorusGeometry(1.02, 0.045, 8, 36);
  const ring = new T.Mesh(ringGeo, M.steel);
  ring.name = 'beacon-ring'; ring.userData = logoGroup.userData;
  logoGroup.add(ring);
  beaconGroup.add(logoGroup);
  group.add(beaconGroup);
  const logoBaseY = logoGroup.position.y;

  // ---------------------------------------------------------------- mount + API
  if (ctx && ctx.root) ctx.root.add(group);

  let clock = 0;
  const update = (status, dt) => {
    clock += (typeof dt === 'number' ? dt : 0.016);
    beaconMat.emissiveIntensity = 1.35 + Math.sin(clock * 2.2) * 0.65;
    logoGroup.position.y = logoBaseY + Math.sin(clock * 1.3) * 0.05;
  };

  const dispose = () => {
    const geos = new Set();
    group.traverse((o) => {
      if (o.geometry) geos.add(o.geometry);
      if (o.isInstancedMesh && typeof o.dispose === 'function') o.dispose();
    });
    geos.forEach((g) => g.dispose());
    ownedTextures.forEach((t) => t.dispose());
    ownedMaterials.forEach((m) => m.dispose());
    if (ownLibrary) Object.values(M).forEach((m) => { if (m && m.dispose) m.dispose(); });
    if (group.parent) group.parent.remove(group);
  };

  return { group, update, dispose };
}
