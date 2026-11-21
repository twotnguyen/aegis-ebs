# 3D Building Module Contract (aegis-dashboard/src/three/)

Goal: a realistic, well-structured office tower (AEGIS Tower B01, 10 floors x 4 zones) rendered with Three.js, composed from independent modules.

## Shared foundation (orchestrator-owned, DO NOT edit)
- `config.js`: BUILDING dimensions, STATUS_COLORS, floorY(i), pitch(), zoneRect(zi), zoneCode(floor,zi), totalHeight(). Use these — never hardcode sizes.
- `materials.js`: `makeMaterials()` returns PBR library {concrete, concreteDark, slab, steel, mullion, glass, glassDark, floor, wall, wood, grass, asphalt, water, exitSign, fireRed, status(s), emissive(hex,i), beacon(hex)}. Use these for coherence.

## Module interface (each component file)
Each module file (e.g. structure.js) MUST export:
```
export function build(ctx){
  // ctx = { THREE, config, materials, root (THREE.Group), helpers }
  // create meshes, add them to ctx.root (or a sub-Group added to root)
  return {
    group,                 // THREE.Group containing this module's objects
    update(status, dt),    // optional: per-frame/status animation (status = live STATUS_SNAPSHOT/DELTA-derived model)
    setStatus(status),     // optional: react to zone status changes
    dispose()              // optional: free geometries/materials
  };
}
```
- Import THREE via `import * as THREE from 'three'`; import config/materials from './config.js' / './materials.js'.
- Add objects to `ctx.root`. Keep meshes named (`mesh.name`) and tag zone meshes with `userData = { floor, zone, zoneCode }` so statusViz/camera can find them.
- Performance: reuse geometries/materials, use InstancedMesh for repeated elements (windows, columns, extinguishers). Target < 300k triangles total scene.
- DO NOT edit main.js, config.js, materials.js, or other modules' files. DO NOT run `npm run build` (assembler does the integrated build). You may syntax-check with `node --check` is not valid for ESM imports; instead ensure valid ESM syntax carefully.

## Composition (assembler wires main.js)
Assembler creates `root = new THREE.Group()`, calls each module.build(ctx), adds groups, sets up renderer/camera/lights, calls update/setStatus on live data, renders. Modules must be self-contained and additive.

## Modules & ownership
- structure.js: floor slabs, columns grid, beams, central elevator/stair core, podium.
- facade.js: glass curtain wall + mullions per floor, entrance canopy, roof parapet, signage.
- interior.js: per-floor zone partitions, corridors, rooms, basic furniture (instanced).
- safety.js: emergency exit signs, stairwells, fire extinguishers, sprinklers, alarm strobes, evacuation path lines.
- environment.js: ground plaza, roads/parking, trees, surrounding context buildings, sky.
- lighting.js: sun (directional+shadow), hemisphere/ambient, interior lights, night mode, tone mapping/exposure.
- statusViz.js: zone status color/pulse, alert beacons, device markers, ACK particles, heatmap (uses userData tags).
- camera.js: OrbitControls, fly-to-floor, cutaway/explode view, floor isolation, preset views.
- ui.js: DOM HUD (legend, floor selector, view toggles, tooltips) — overlay, styled.
