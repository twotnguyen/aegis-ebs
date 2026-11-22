// cityContext.js — intentionally empty surrounding-city module.
// The far city (high-rise tower field, outer road grid, traffic, parks and hazy
// skyline silhouettes) was removed for performance: the scene focuses on
// AEGIS Tower B01, and near-field landscaping lives in environment.js.
// Contract preserved: build(ctx) -> { group, dispose } with userData.stats.
import * as THREE from 'three';
import { BUILDING } from './config.js';

export function build(ctx = {}) {
  const T = ctx.THREE || THREE;
  const B = (ctx.config && ctx.config.BUILDING) || BUILDING;

  const group = new T.Group();
  group.name = 'cityContext';

  // No geometry at all: nothing here competes with the main tower.
  group.userData = {
    module: 'cityContext',
    stats: {
      towers: 0,
      glassTowers: 0,
      cars: 0,
      trees: 0,
      parks: 0,
      skyline: 0,
      triangles: 0,
      footprint: [B.width, B.depth],
    },
  };
  if (ctx.root && ctx.root.add) ctx.root.add(group);

  let disposed = false;
  function dispose() {
    if (disposed) return;
    disposed = true;
    group.removeFromParent();
  }

  return { group, dispose };
}
