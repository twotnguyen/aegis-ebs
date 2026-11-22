// Shared building configuration — ALL 3D modules read this. Do not hardcode dimensions elsewhere.
export const BUILDING = {
  name: 'AEGIS Tower B01',
  floors: 10,
  floorHeight: 3.4,      // clear height per floor
  slabThickness: 0.4,
  width: 72,             // X footprint (m) — enlarged 44 -> 72 (2.7x total area)
  depth: 50,             // Z footprint (m) — enlarged 30 -> 50
  zonesPerFloor: 4,      // 2x2 quadrant zones A/B/C/D
  zoneInset: 2.2,        // gap from facade
  zoneGap: 1.8,          // gap between zones (corridor)
  coreWidth: 12,         // central elevator/stair core
  coreDepth: 9,
  podiumFloors: 1,       // ground podium slightly wider
  roofParapet: 1.1,
};
export const STATUS_COLORS = { online:0x22c55e, warning:0xfacc15, critical:0xef4444, offline:0x64748b };
export const ZONE_CODES = ['A','B','C','D'];
export const pitch = () => BUILDING.floorHeight + BUILDING.slabThickness;
export const floorY = (i) => i * pitch();                 // slab top Y of floor i (0-based)
export const totalHeight = () => BUILDING.floors * pitch();
// quadrant zone rectangle (local to floor center), 2x2: A=back-left B=back-right C=front-left D=front-right
export function zoneRect(zoneIndex){
  const hw = BUILDING.width/2 - BUILDING.zoneInset;
  const hd = BUILDING.depth/2 - BUILDING.zoneInset;
  const gx = BUILDING.zoneGap/2, gz = BUILDING.zoneGap/2;
  const sx = (zoneIndex % 2) === 0 ? -1 : 1;
  const sz = (zoneIndex < 2) ? -1 : 1;
  const w = hw - gx, d = hd - gz;
  return { x: sx*(gx + w/2), z: sz*(gz + d/2), w, d };
}
export const zoneCode = (floor, zi) => `F${String(floor+1).padStart(2,'0')}-${ZONE_CODES[zi]}`;
