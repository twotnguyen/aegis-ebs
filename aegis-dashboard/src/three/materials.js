// Shared PBR material library — ALL 3D modules use these for visual coherence.
import * as THREE from 'three';
import { STATUS_COLORS } from './config.js';
export function makeMaterials(){
  const concrete = new THREE.MeshStandardMaterial({ color:0xb9bec4, roughness:0.9, metalness:0.02 });
  const concreteDark = new THREE.MeshStandardMaterial({ color:0x8d949c, roughness:0.95, metalness:0.02 });
  const slab = new THREE.MeshStandardMaterial({ color:0xd7dbe0, roughness:0.85, metalness:0.03 });
  const steel = new THREE.MeshStandardMaterial({ color:0x6b7280, roughness:0.35, metalness:0.85 });
  const mullion = new THREE.MeshStandardMaterial({ color:0x2b3440, roughness:0.4, metalness:0.7 });
  const glass = new THREE.MeshPhysicalMaterial({ color:0x9fc6e8, roughness:0.08, metalness:0, transmission:0.6, transparent:true, opacity:0.45, reflectivity:0.6, ior:1.5, thickness:0.4, side:THREE.DoubleSide });
  const glassDark = new THREE.MeshPhysicalMaterial({ color:0x35506b, roughness:0.1, metalness:0.1, transmission:0.4, transparent:true, opacity:0.6, side:THREE.DoubleSide });
  const floor = new THREE.MeshStandardMaterial({ color:0xe8e2d6, roughness:0.7, metalness:0.02 });
  const wall = new THREE.MeshStandardMaterial({ color:0xf1f3f5, roughness:0.8, metalness:0.02 });
  const wood = new THREE.MeshStandardMaterial({ color:0x9c6b3f, roughness:0.6, metalness:0.05 });
  const grass = new THREE.MeshStandardMaterial({ color:0x4f7a45, roughness:1, metalness:0 });
  const asphalt = new THREE.MeshStandardMaterial({ color:0x3a3f45, roughness:0.95, metalness:0 });
  const water = new THREE.MeshPhysicalMaterial({ color:0x2f6f9f, roughness:0.15, metalness:0, transmission:0.3, transparent:true, opacity:0.8 });
  const exitSign = new THREE.MeshStandardMaterial({ color:0x16a34a, emissive:0x16a34a, emissiveIntensity:0.9, roughness:0.5 });
  const fireRed = new THREE.MeshStandardMaterial({ color:0xdc2626, emissive:0x7f1d1d, emissiveIntensity:0.3, roughness:0.5 });
  const status = (s) => new THREE.MeshStandardMaterial({ color:STATUS_COLORS[s]||STATUS_COLORS.offline, emissive:STATUS_COLORS[s]||0, emissiveIntensity:0.25, roughness:0.5, transparent:true, opacity:0.85 });
  const emissive = (hex, intensity=1) => new THREE.MeshStandardMaterial({ color:hex, emissive:hex, emissiveIntensity:intensity, roughness:0.4 });
  const beacon = (hex) => new THREE.MeshStandardMaterial({ color:hex, emissive:hex, emissiveIntensity:1.4, transparent:true, opacity:0.9 });
  return { concrete, concreteDark, slab, steel, mullion, glass, glassDark, floor, wall, wood, grass, asphalt, water, exitSign, fireRed, status, emissive, beacon };
}
