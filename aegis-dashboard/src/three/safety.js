// safety.js — AN TOÀN / PCCC + thoát hiểm cho AEGIS Tower B01.
// Thành phần: biển EXIT phát sáng, 2 buồng thang thoát hiểm trong lõi,
// bình chữa cháy (instanced), đầu phun sprinkler (instanced), đèn strobe báo
// động theo zone, tuyến thoát hiểm xanh (TubeGeometry) và điểm tập kết ngoài trời.
// Theo CONTRACT.md: build(ctx) -> { group, update(status, dt), dispose }.
import * as THREE from 'three';
import * as configNS from './config.js';
import { makeMaterials } from './materials.js';

export function build(ctx) {
  const T = ctx.THREE || THREE;
  const cfg = ctx.config || configNS;
  const M = ctx.materials || makeMaterials();
  const { BUILDING, floorY, pitch, zoneRect, zoneCode } = cfg;
  const FLOORS = BUILDING.floors;
  const FH = BUILDING.floorHeight;
  const HW = BUILDING.width / 2;
  const HD = BUILDING.depth / 2;
  const CW = BUILDING.coreWidth / 2;   // nửa rộng lõi (X)
  const CD = BUILDING.coreDepth / 2;   // nửa sâu lõi (Z)
  const IHW = HW - BUILDING.zoneInset; // nửa khu nội thất (X)
  const IHD = HD - BUILDING.zoneInset; // nửa khu nội thất (Z)
  const GAP = BUILDING.zoneGap / 2;    // nửa chiều rộng hành lang

  const group = new T.Group();
  group.name = 'safety';
  ctx.root.add(group);

  // Tài nguyên thuộc sở hữu module này (dispose chỉ giải phóng những gì ta tạo,
  // KHÔNG dispose vật liệu dùng chung của thư viện materials).
  const ownGeoms = new Set();
  const ownMats = new Set();
  const ownTex = new Set();
  const geo = (g) => { ownGeoms.add(g); return g; };
  const mat = (m) => { ownMats.add(m); return m; };

  const dummy = new T.Object3D();
  const instanced = (name, geometry, material, items) => {
    const mesh = new T.InstancedMesh(geometry, material, items.length);
    mesh.name = name;
    items.forEach((it, i) => {
      dummy.position.set(it.x, it.y, it.z);
      dummy.rotation.set(it.rx || 0, it.ry || 0, it.rz || 0);
      dummy.scale.set(it.sx ?? 1, it.sy ?? 1, it.sz ?? 1);
      dummy.updateMatrix();
      mesh.setMatrixAt(i, dummy.matrix);
    });
    mesh.instanceMatrix.needsUpdate = true;
    mesh.castShadow = true;
    mesh.receiveShadow = true;
    mesh.userData = { kind: name, count: items.length, instances: items.map((it) => ({
      floor: it.floor, zone: it.zone, zoneCode: it.zoneCode, x: it.x, y: it.y, z: it.z,
    })) };
    group.add(mesh);
    return mesh;
  };
  // Gán zone theo góc phần tư của vị trí (thiết bị hành lang lấy zone gần nhất).
  const quadrant = (x, z) => (x > 0 ? 1 : 0) + (z >= 0 ? 2 : 0);

  // ---------------------------------------------------------------------------
  // 1) HAI BUỒNG THANG THOÁT HIỂM trong lõi (switchback, mỗi tầng 20 bậc).
  //    Giếng thang: kích thước tỉ lệ với lõi (coreWidth/coreDepth), dải giữa
  //    dành cho thang máy (structure.js). Cửa buồng thang mở ra hành lang Đông/Tây.
  // ---------------------------------------------------------------------------
  const STAIR_X = [-CW * 0.46, CW * 0.46];   // tâm 2 giếng thang
  const SHAFT_W = CW * 0.5, SHAFT_D = CD * 0.92;  // kích thước giếng
  const RUN_W = 1.3, TREAD = 0.28, RISER = pitch() / 20;
  const RUN_LEN = 10 * TREAD;          // 2.8
  const LAND_D = (SHAFT_D - RUN_LEN) / 2; // 1.3
  const RAIL_ANG = Math.atan2(pitch() / 2, RUN_LEN);
  const RAIL_LEN = Math.hypot(RUN_LEN, pitch() / 2);
  const DOOR_X = [-CW, CW];            // cửa trên mặt lõi Tây / Đông
  const unitBox = geo(new T.BoxGeometry(1, 1, 1));

  const stepItems = [], wallItems = [], railItems = [], doorItems = [], barItems = [];
  for (let f = 0; f < FLOORS; f++) {
    const base = floorY(f);
    for (let s = 0; s < 2; s++) {
      const sx = STAIR_X[s];
      // Bậc thang: vế A (x_local -0.75) đi lên theo +Z, vế B (+0.75) quay về -Z.
      for (let k = 0; k < 10; k++) {
        const hA = RISER * (k + 1);
        stepItems.push({ x: sx - 0.75, y: base + hA / 2, z: -LAND_D + TREAD * (k + 0.5), sx: RUN_W, sy: hA, sz: TREAD, floor: f, zone: null, zoneCode: 'CORE' });
        const hB = pitch() / 2 + RISER * (k + 1);
        stepItems.push({ x: sx + 0.75, y: base + hB / 2, z: LAND_D - TREAD * (k + 0.5), sx: RUN_W, sy: hB, sz: TREAD, floor: f, zone: null, zoneCode: 'CORE' });
      }
      // Chiếu nghỉ giữa (cao nửa tầng) và chiếu nghỉ tầng.
      stepItems.push({ x: sx, y: base + pitch() / 2 - RISER / 2, z: SHAFT_D / 2 - LAND_D / 2, sx: SHAFT_W, sy: RISER, sz: LAND_D, floor: f, zone: null, zoneCode: 'CORE' });
      stepItems.push({ x: sx, y: base - RISER / 2 + 0.01, z: -(SHAFT_D / 2 - LAND_D / 2), sx: SHAFT_W, sy: RISER, sz: LAND_D, floor: f, zone: null, zoneCode: 'CORE' });
      // Tay vịn thép nghiêng theo 2 vế.
      railItems.push({ x: sx - 1.4, y: base + pitch() / 4 + 0.9, z: 0, rx: -RAIL_ANG, sx: 0.05, sy: 0.05, sz: RAIL_LEN, floor: f, zone: null, zoneCode: 'CORE' });
      railItems.push({ x: sx + 1.4, y: base + 3 * pitch() / 4 + 0.9, z: 0, rx: RAIL_ANG, sx: 0.05, sy: 0.05, sz: RAIL_LEN, floor: f, zone: null, zoneCode: 'CORE' });
      // Vách giếng thang (2 bên + 2 đầu), chạy suốt chiều cao tòa nhà.
      if (f === 0) {
        const H = FLOORS * pitch();
        wallItems.push({ x: sx - SHAFT_W / 2, y: H / 2, z: 0, sx: 0.15, sy: H, sz: SHAFT_D, floor: 'all', zone: null, zoneCode: 'CORE' });
        wallItems.push({ x: sx + SHAFT_W / 2, y: H / 2, z: 0, sx: 0.15, sy: H, sz: SHAFT_D, floor: 'all', zone: null, zoneCode: 'CORE' });
        wallItems.push({ x: sx, y: H / 2, z: -SHAFT_D / 2, sx: SHAFT_W, sy: H, sz: 0.15, floor: 'all', zone: null, zoneCode: 'CORE' });
        wallItems.push({ x: sx, y: H / 2, z: SHAFT_D / 2, sx: SHAFT_W, sy: H, sz: 0.15, floor: 'all', zone: null, zoneCode: 'CORE' });
      }
      // Cửa buồng thang trên mặt lõi (khuôn + cánh thép + thanh đẩy EXIT xanh).
      const dx = DOOR_X[s];
      const side = s === 0 ? -1 : 1; // hướng mở ra hành lang
      wallItems.push({ x: dx, y: base + 1.15, z: -0.56, sx: 0.22, sy: 2.3, sz: 0.12, floor: f, zone: null, zoneCode: 'CORE' });
      wallItems.push({ x: dx, y: base + 1.15, z: 0.56, sx: 0.22, sy: 2.3, sz: 0.12, floor: f, zone: null, zoneCode: 'CORE' });
      wallItems.push({ x: dx, y: base + 2.4, z: 0, sx: 0.22, sy: 0.25, sz: 1.24, floor: f, zone: null, zoneCode: 'CORE' });
      doorItems.push({ x: dx, y: base + 1.1, z: 0, sx: 0.07, sy: 2.2, sz: 1.0, floor: f, zone: null, zoneCode: 'CORE' });
      barItems.push({ x: dx + side * 0.06, y: base + 1.0, z: 0, sx: 0.05, sy: 0.08, sz: 0.7, floor: f, zone: null, zoneCode: 'CORE' });
    }
  }
  instanced('stair-steps', unitBox, M.slab, stepItems);
  instanced('stair-walls', unitBox, M.concreteDark, wallItems);
  instanced('stair-rails', unitBox, M.steel, railItems);
  instanced('stair-doors', unitBox, M.steel, doorItems);
  instanced('stair-push-bars', unitBox, M.exitSign, barItems);

  // ---------------------------------------------------------------------------
  // 2) BIỂN EXIT phát sáng (materials.exitSign) — trên cửa mỗi buồng thang mỗi
  //    tầng + 2 cửa thoát hiểm tầng trệt (mặt trước/sau) + biển điểm tập kết.
  //    Chữ EXIT vẽ bằng canvas texture khi chạy trong trình duyệt (Node bỏ qua).
  // ---------------------------------------------------------------------------
  function textTexture(lines, w, h, fontPx) {
    if (typeof document === 'undefined') return null;
    const c = document.createElement('canvas');
    c.width = w; c.height = h;
    const g = c.getContext('2d');
    g.clearRect(0, 0, w, h);
    g.fillStyle = '#ffffff';
    g.textAlign = 'center';
    g.textBaseline = 'middle';
    const lh = h / (lines.length + 0.4);
    lines.forEach((ln, i) => {
      g.font = `bold ${fontPx}px system-ui, sans-serif`;
      g.fillText(ln, w / 2, h / 2 + (i - (lines.length - 1) / 2) * lh);
    });
    const tex = new T.CanvasTexture(c);
    tex.anisotropy = 4;
    ownTex.add(tex);
    return tex;
  }
  const exitTex = textTexture(['EXIT'], 128, 48, 34);
  const exitTextMat = exitTex ? mat(new T.MeshBasicMaterial({ map: exitTex, transparent: true, depthWrite: false, toneMapped: false })) : null;
  const signPanelGeo = geo(new T.BoxGeometry(0.9, 0.35, 0.06));
  const signFrameGeo = geo(new T.BoxGeometry(0.98, 0.43, 0.04));
  const signTextGeo = geo(new T.PlaneGeometry(0.82, 0.3));

  function exitSign(name, x, y, z, rotY, userData) {
    const g = new T.Group();
    g.name = name;
    const frame = new T.Mesh(signFrameGeo, M.mullion);
    frame.position.z = -0.02;
    const panel = new T.Mesh(signPanelGeo, M.exitSign);
    g.add(frame, panel);
    if (exitTextMat) {
      const txt = new T.Mesh(signTextGeo, exitTextMat);
      txt.position.z = 0.035;
      g.add(txt);
    }
    g.position.set(x, y, z);
    g.rotation.y = rotY;
    g.userData = userData;
    group.add(g);
    return g;
  }
  const signCounts = { stair: 0, exterior: 0 };
  for (let f = 0; f < FLOORS; f++) {
    const y = floorY(f) + 2.62;
    const fn = String(f + 1).padStart(2, '0');
    exitSign(`exit-sign-F${fn}-west`, -CW - 0.08, y, 0, -Math.PI / 2, { floor: f, zone: null, area: 'core-stair', kind: 'exit-sign' });
    exitSign(`exit-sign-F${fn}-east`, CW + 0.08, y, 0, Math.PI / 2, { floor: f, zone: null, area: 'core-stair', kind: 'exit-sign' });
    signCounts.stair += 2;
  }
  // Cửa thoát hiểm tầng trệt: mặt trước (+Z) và mặt sau (-Z), cuối hành lang Nam/Bắc.
  exitSign('exit-sign-ground-front', 0, 2.7, HD + 0.08, 0, { floor: 0, zone: null, area: 'facade-front', kind: 'exit-sign' });
  exitSign('exit-sign-ground-back', 0, 2.7, -HD - 0.08, Math.PI, { floor: 0, zone: null, area: 'facade-back', kind: 'exit-sign' });
  signCounts.exterior += 2;
  const groundDoorItems = [];
  for (const sz of [1, -1]) {
    groundDoorItems.push({ x: -0.62, y: 1.1, z: sz * HD, sx: 0.12, sy: 2.2, sz: 0.12, floor: 0, zone: null, zoneCode: 'CORE' });
    groundDoorItems.push({ x: 0.62, y: 1.1, z: sz * HD, sx: 0.12, sy: 2.2, sz: 0.12, floor: 0, zone: null, zoneCode: 'CORE' });
    groundDoorItems.push({ x: 0, y: 2.32, z: sz * HD, sx: 1.36, sy: 0.16, sz: 0.12, floor: 0, zone: null, zoneCode: 'CORE' });
    groundDoorItems.push({ x: -0.28, y: 1.05, z: sz * HD, sx: 0.5, sy: 2.1, sz: 0.05, floor: 0, zone: null, zoneCode: 'CORE' });
    groundDoorItems.push({ x: 0.28, y: 1.05, z: sz * HD, sx: 0.5, sy: 2.1, sz: 0.05, floor: 0, zone: null, zoneCode: 'CORE' });
  }
  instanced('ground-exit-doors', unitBox, M.steel, groundDoorItems);

  // 2b) BIỂN CHỈ HƯỚNG thoát hiểm tại các giao lộ hành lang mỗi tầng.
  //     Dùng chung canvas chữ EXIT ở trên, gom thành 2 InstancedMesh.
  const dirPanelItems = [], dirTextItems = [];
  const dirPanelGeo = geo(new T.BoxGeometry(0.08, 0.42, 1.0));
  const dirTextGeo = geo(new T.PlaneGeometry(0.86, 0.32));
  for (let f = 0; f < FLOORS; f++) {
    const y = floorY(f) + 2.55;
    for (const side of [-1, 1]) {
      const px = side * (CW + 0.14);
      dirPanelItems.push({ x: px, y, z: 0, floor: f, zone: null, zoneCode: 'CORE' });
      dirTextItems.push({ x: px + side * 0.05, y, z: 0, ry: side * Math.PI / 2, floor: f, zone: null, zoneCode: 'CORE' });
    }
  }
  instanced('corridor-exit-panels', dirPanelGeo, M.exitSign, dirPanelItems);
  if (exitTextMat) instanced('corridor-exit-text', dirTextGeo, exitTextMat, dirTextItems);
  signCounts.corridor = dirPanelItems.length;

  // ---------------------------------------------------------------------------
  // 3) BÌNH CHỮA CHÁY đỏ (InstancedMesh, materials.fireRed) dọc hành lang:
  //    6 bình/tầng (hành lang Tây, Đông, Nam, Bắc) × 10 tầng = 60 bình.
  // ---------------------------------------------------------------------------
  // Vị trí bình/tủ chữa cháy dọc 4 nhánh hành lang, lấy theo core + zoneInset.
  const ex = CW + (IHW - CW) * 0.34, exFar = CW + (IHW - CW) * 0.76;
  const ez = CD + (IHD - CD) * 0.34, ezFar = CD + (IHD - CD) * 0.76;
  const extSpots = [
    { x: -ex, z: GAP - 0.3 }, { x: -exFar, z: GAP - 0.3 },
    { x: ex, z: -(GAP - 0.3) }, { x: exFar, z: -(GAP - 0.3) },
    { x: -(GAP - 0.3), z: -ez }, { x: GAP - 0.3, z: ezFar },
  ];
  const extBodyItems = [], extTopItems = [], hoseItems = [], hoseDoorItems = [];
  const extBodyGeo = geo(new T.CylinderGeometry(0.09, 0.09, 0.45, 10, 1));
  for (let f = 0; f < FLOORS; f++) {
    const y = floorY(f);
    for (const p of extSpots) {
      const zi = quadrant(p.x, p.z);
      const meta = { floor: f, zone: zi, zoneCode: zoneCode(f, zi) };
      extBodyItems.push({ x: p.x, y: y + 0.55, z: p.z, ...meta });
      extTopItems.push({ x: p.x, y: y + 0.83, z: p.z, sx: 0.05, sy: 0.12, sz: 0.16, ...meta });
      // tủ họng vòi (hose cabinet) gắn ngay sát tường hành lang
      const alongX = Math.abs(p.x) > GAP;
      const hx = alongX ? p.x : Math.sign(p.x) * (GAP + 0.14);
      const hz = alongX ? Math.sign(p.z) * (GAP + 0.14) : p.z;
      hoseItems.push({ x: hx, y: y + 1.05, z: hz, sx: 0.5, sy: 1.0, sz: 0.2, ...meta });
      hoseDoorItems.push({
        x: hx + (alongX ? 0 : Math.sign(hx) * 0.12),
        y: y + 1.05, z: hz + (alongX ? Math.sign(hz) * 0.12 : 0),
        sx: alongX ? 0.44 : 0.05, sy: 0.92, sz: alongX ? 0.05 : 0.44, ...meta,
      });
    }
  }
  instanced('extinguishers', extBodyGeo, M.fireRed, extBodyItems);
  instanced('extinguisher-handles', unitBox, M.mullion, extTopItems);
  instanced('fire-hose-cabinets', unitBox, M.fireRed, hoseItems);
  instanced('fire-hose-doors', unitBox, M.steel, hoseDoorItems);

  // ---------------------------------------------------------------------------
  // 4) ĐẦU PHUN SPRINKLER trên trần (InstancedMesh): 12 đầu/tầng
  //    (2 đầu mỗi zone + 4 đầu hành lang) × 10 tầng = 120 đầu.
  // ---------------------------------------------------------------------------
  const sprinklerItems = [];
  for (let f = 0; f < FLOORS; f++) {
    const cy = floorY(f) + FH - 0.02; // trần = đáy slab tầng trên
    for (let zi = 0; zi < BUILDING.zonesPerFloor; zi++) {
      const r = zoneRect(zi);
      for (const dx of [-r.w / 4, r.w / 4]) {
        sprinklerItems.push({ x: r.x + dx, y: cy, z: r.z, floor: f, zone: zi, zoneCode: zoneCode(f, zi) });
      }
    }
    for (const p of [{ x: -ex, z: 0 }, { x: ex, z: 0 }, { x: 0, z: -ez }, { x: 0, z: ez }]) {
      const zi = quadrant(p.x, p.z);
      sprinklerItems.push({ x: p.x, y: cy, z: p.z, floor: f, zone: zi, zoneCode: zoneCode(f, zi) });
    }
  }
  const pipeGeo = geo(new T.CylinderGeometry(0.025, 0.025, 0.14, 6, 1));
  const deflGeo = geo(new T.CylinderGeometry(0.075, 0.075, 0.02, 6, 1));
  const mainPipeGeo = geo(new T.CylinderGeometry(1, 1, 1, 6, 1));
  const mainPipeItems = [];
  for (let f = 0; f < FLOORS; f++) {
    const py = floorY(f) + FH - 0.10;
    for (const z of [-GAP * 0.5, GAP * 0.5])
      mainPipeItems.push({ x: 0, y: py, z, rz: Math.PI / 2, sx: 0.055, sy: 2 * IHW, sz: 0.055, floor: f, zone: null, zoneCode: 'CORRIDOR' });
    for (const x of [-GAP * 0.5, GAP * 0.5])
      mainPipeItems.push({ x, y: py, z: 0, rx: Math.PI / 2, sx: 0.055, sy: 2 * IHD, sz: 0.055, floor: f, zone: null, zoneCode: 'CORRIDOR' });
  }
  instanced('sprinkler-main-pipes', mainPipeGeo, M.steel, mainPipeItems);
  const pipeItems = sprinklerItems.map((s) => ({ ...s, y: s.y - 0.07 }));
  const deflItems = sprinklerItems.map((s) => ({ ...s, y: s.y - 0.15 }));
  instanced('sprinkler-pipes', pipeGeo, M.steel, pipeItems);
  instanced('sprinkler-deflectors', deflGeo, M.steel, deflItems);

  // ---------------------------------------------------------------------------
  // 5) ĐÈN STROBE báo động (materials.beacon đỏ) — mỗi zone một đèn (40 đèn).
  //    Nhấp nháy nhanh khi zone critical; nhấp nháy chậm toàn tòa khi có alert.
  // ---------------------------------------------------------------------------
  const strobeBaseItems = [];
  const strobes = [];
  const domeGeo = geo(new T.SphereGeometry(0.13, 8, 4, 0, Math.PI * 2, 0, Math.PI / 2));
  const strobeCorners = [];
  for (let zi = 0; zi < BUILDING.zonesPerFloor; zi++) {
    const r = zoneRect(zi);
    const sgx = (zi % 2 === 0) ? -1 : 1, sgz = (zi < 2) ? -1 : 1;
    // góc ngoài của mỗi zone — nhìn rõ qua kính facade, không chồng lõi
    strobeCorners.push([r.x + sgx * r.w * 0.28, r.z + sgz * r.d * 0.28]);
  }
  for (let f = 0; f < FLOORS; f++) {
    const y = floorY(f) + 2.75;
    for (let zi = 0; zi < BUILDING.zonesPerFloor; zi++) {
      const [x, z] = strobeCorners[zi];
      const code = zoneCode(f, zi);
      strobeBaseItems.push({ x, y: y - 0.12, z, sx: 0.2, sy: 0.16, sz: 0.2, floor: f, zone: zi, zoneCode: code });
      const m = mat(M.beacon(0xef4444)); // mỗi đèn một material riêng để nhấp nháy độc lập
      m.emissiveIntensity = 0.3;
      const dome = new T.Mesh(domeGeo, m);
      dome.name = `strobe-${code}`;
      dome.position.set(x, y, z);
      dome.userData = { floor: f, zone: zi, zoneCode: code, kind: 'strobe' };
      group.add(dome);
      strobes.push({ mesh: dome, mat: m, zoneCode: code, phase: (f * 4 + zi) * 0.7 });
    }
  }
  instanced('strobe-bases', unitBox, M.mullion, strobeBaseItems);

  // ---------------------------------------------------------------------------
  // 6) TUYẾN THOÁT HIỂM màu xanh (TubeGeometry) dọc hành lang ra lối EXIT:
  //    mỗi zone một tuyến từ góc trong zone → hành lang vành đai quanh lõi →
  //    cửa buồng thang gần nhất. Tầng trệt có thêm tuyến ra 2 cửa ngoài trời,
  //    nối tiếp tuyến ngoài trời dẫn tới điểm tập kết.
  // ---------------------------------------------------------------------------
  const routeMat = mat(M.emissive(0x22c55e, 0.8));
  const routes = new T.Group();
  routes.name = 'evac-routes';
  group.add(routes);
  const R = 0.9; // lề hành lang vành đai quanh lõi
  const RX = CW + R, RZ = CD + R;
  function tube(name, pts, y, segs, userData) {
    const path = new T.CurvePath();
    const v = pts.map((p) => new T.Vector3(p[0], y, p[1]));
    for (let i = 0; i < v.length - 1; i++) path.add(new T.LineCurve3(v[i], v[i + 1]));
    const g = geo(new T.TubeGeometry(path, segs, 0.05, 4, false));
    const mesh = new T.Mesh(g, routeMat);
    mesh.name = name;
    mesh.userData = userData;
    routes.add(mesh);
    return mesh;
  }
  // Điểm xuất phát của mỗi tuyến nằm trong zone, lệch về phía lõi theo zoneRect.
  const zoneEntry = [];
  for (let zi = 0; zi < BUILDING.zonesPerFloor; zi++) {
    const r = zoneRect(zi);
    const sgx = (zi % 2 === 0) ? -1 : 1, sgz = (zi < 2) ? -1 : 1;
    zoneEntry.push([r.x - sgx * r.w * 0.25, r.z - sgz * r.d * 0.25]);
  }
  let routeCount = 0;
  for (let f = 0; f < FLOORS; f++) {
    const y = floorY(f) + 0.03;
    for (let zi = 0; zi < BUILDING.zonesPerFloor; zi++) {
      const entryX = zoneEntry[zi][0], entryZ = zoneEntry[zi][1];
      const sx = entryX > 0 ? RX : -RX;
      const sz = entryZ > 0 ? RZ : -RZ;
      const doorX = entryX > 0 ? CW + 0.2 : -CW - 0.2;
      tube(`evac-F${String(f + 1).padStart(2, '0')}-${zoneCode(f, zi).slice(-1)}`,
        [[entryX, entryZ], [sx, sz], [sx, 0], [doorX, 0]], y, 24,
        { floor: f, zone: zi, zoneCode: zoneCode(f, zi), kind: 'evac-route' });
      routeCount++;
    }
  }
  // Tầng trệt: từ cửa buồng thang ra cửa ngoài trời (trước / sau).
  tube('evac-ground-west-to-front', [[-CW - 0.2, 0], [-RX, 0], [-RX, RZ], [0, RZ], [0, HD - 0.1]], 0.03, 32, { floor: 0, zone: null, kind: 'evac-route', area: 'egress-front' });
  tube('evac-ground-east-to-back', [[CW + 0.2, 0], [RX, 0], [RX, -RZ], [0, -RZ], [0, -HD + 0.1]], 0.03, 32, { floor: 0, zone: null, kind: 'evac-route', area: 'egress-back' });
  routeCount += 2;

  // ---------------------------------------------------------------------------
  // ---------------------------------------------------------------------------
  // 7) ĐIỂM TẬP KẾT ngoài trời (thảm tròn xanh + viền, biển báo, tuyến nối tiếp).
  //    Điểm tập kết ngoài trời lệch Đông, ngoài sân lát (plaza x≤width/2+...)
  // nên không chắn lối vào sảnh; mọi tọa độ tỉ lệ với footprint.
  const AX = HW + BUILDING.width * 0.17;
  const AZ = HD + BUILDING.depth * 0.24;
  const AR = Math.min(3, BUILDING.depth * 0.06);
  const assembly = new T.Group();
  assembly.name = 'assembly-point';
  assembly.userData = { floor: 0, zone: null, kind: 'assembly-point', x: AX, z: AZ, radius: AR };
  const padMat = mat(M.emissive(0x22c55e, 0.35));
  const pad = new T.Mesh(geo(new T.CircleGeometry(AR, 24)), padMat);
  pad.rotation.x = -Math.PI / 2;
  pad.position.set(AX, 0.03, AZ);
  pad.name = 'assembly-pad';
  const ringMat = mat(M.emissive(0xffffff, 0.6));
  const ring = new T.Mesh(geo(new T.RingGeometry(AR + 0.1, AR + 0.4, 24)), ringMat);
  ring.rotation.x = -Math.PI / 2;
  ring.position.set(AX, 0.035, AZ);
  ring.name = 'assembly-ring';
  assembly.add(pad, ring);
  // Biển báo điểm tập kết (nền xanh, chữ trắng, 4 mũi tên chụm vào tâm).
  const post = new T.Mesh(unitBox, M.steel);
  post.scale.set(0.09, 2.3, 0.09);
  post.position.set(AX, 1.15, AZ + AR + 0.6);
  const asmPanelMat = mat(M.emissive(0x16a34a, 0.7));
  const asmPanel = new T.Mesh(geo(new T.BoxGeometry(1.5, 1.0, 0.06)), asmPanelMat);
  asmPanel.position.set(AX, 2.4, AZ + AR + 0.6);
  asmPanel.rotation.y = Math.PI; // quay mặt về phía tòa nhà
  assembly.add(post, asmPanel);
  const asmTex = textTexture(['ĐIỂM TẬP KẾT', 'ASSEMBLY POINT'], 256, 128, 30);
  if (asmTex) {
    const asmTxt = new T.Mesh(geo(new T.PlaneGeometry(1.4, 0.7)),
      mat(new T.MeshBasicMaterial({ map: asmTex, transparent: true, depthWrite: false, toneMapped: false })));
    asmTxt.position.set(AX, 2.4, AZ + AR + 0.56);
    asmTxt.rotation.y = Math.PI;
    assembly.add(asmTxt);
  }
  const arrowGeo = geo(new T.ConeGeometry(0.09, 0.24, 4));
  const arrowMat = mat(M.emissive(0xffffff, 0.9));
  const upY = new T.Vector3(0, 1, 0);
  for (const [dx, dy] of [[-0.55, 0.32], [0.55, 0.32], [-0.55, -0.32], [0.55, -0.32]]) {
    const a = new T.Mesh(arrowGeo, arrowMat);
    // Mũi tên chỉ vào tâm biển: đặt ở 4 góc, xoay hướng vào trong.
    a.position.set(AX + dx, 2.4 + dy, AZ + AR + 0.55);
    const dir = new T.Vector3(-dx, -dy, 0).normalize();
    a.quaternion.setFromUnitVectors(upY, dir);
    assembly.add(a);
  }
  group.add(assembly);
  // Tuyến ngoài trời: cửa trước → điểm tập kết; cửa sau → vòng mép sân → tập kết.
  const frontCornerZ = HD + BUILDING.depth * 0.05;  // trước đài phun nước
  const backCornerZ = -(HD + BUILDING.depth * 0.08);
  const sideX = HW + BUILDING.width * 0.11;         // vòng qua Đông tòa nhà
  tube('evac-outdoor-front', [[0, HD + 0.4], [0, frontCornerZ], [AX, frontCornerZ], [AX, AZ - AR + 0.4]], 0.04, 40, { floor: 0, zone: null, kind: 'evac-route', area: 'outdoor' });
  tube('evac-outdoor-back', [[0, -HD - 0.4], [0, backCornerZ], [sideX, backCornerZ], [sideX, AZ], [AX - AR + 0.5, AZ]], 0.04, 48, { floor: 0, zone: null, kind: 'evac-route', area: 'outdoor' });
  routeCount += 2;

  // ---------------------------------------------------------------------------
  // update(status, dt): strobe nhấp nháy theo trạng thái zone.
  //   - zone critical  → nhấp nháy nhanh ~2.5 Hz, sáng mạnh.
  //   - có alert toàn tòa (bất kỳ critical) → các zone còn lại nhấp nháy chậm.
  //   - bình thường → sáng mờ ổn định.
  // ---------------------------------------------------------------------------
  let clock = 0;
  const criticalSet = new Set();
  function update(status, dt) {
    const step = Math.min(Math.max(dt ?? 0.016, 0), 0.1);
    clock += step;
    criticalSet.clear();
    let anyCritical = false;
    for (const f of status?.floors ?? []) {
      for (const z of f.zones ?? []) {
        if (z.severity === 'critical') { criticalSet.add(z.zoneCode); anyCritical = true; }
      }
    }
    for (const a of status?.activeAlerts ?? []) {
      if (a.severity === 'critical' && a.zoneCode) { criticalSet.add(a.zoneCode); anyCritical = true; }
    }
    for (const s of strobes) {
      if (criticalSet.has(s.zoneCode)) {
        const on = Math.sin(clock * Math.PI * 5 + s.phase) > 0; // ~2.5 Hz
        s.mat.emissiveIntensity = on ? 3.2 : 0.12;
        s.mat.opacity = on ? 1.0 : 0.55;
      } else if (anyCritical) {
        s.mat.emissiveIntensity = 0.5 + 0.4 * Math.sin(clock * Math.PI * 1.2 + s.phase);
        s.mat.opacity = 0.85;
      } else {
        s.mat.emissiveIntensity = 0.3;
        s.mat.opacity = 0.9;
      }
    }
  }

  function dispose() {
    group.traverse((o) => { if (o.isInstancedMesh) o.dispose(); });
    for (const g of ownGeoms) g.dispose();
    for (const m of ownMats) m.dispose();
    for (const t of ownTex) t.dispose();
    ownGeoms.clear(); ownMats.clear(); ownTex.clear();
    if (group.parent) group.parent.remove(group);
  }

  group.userData = {
    kind: 'safety',
    devices: {
      exitSigns: signCounts.stair + signCounts.exterior + signCounts.corridor,
      stairwells: 2,
      extinguishers: extBodyItems.length,
      sprinklers: sprinklerItems.length,
      strobes: strobes.length,
      evacRoutes: routeCount,
      assemblyPoints: 1,
    },
  };

  return { group, update, dispose };
}
