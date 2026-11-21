// weather.js — atmosphere for AEGIS Tower B01: gradient sky dome (day/dusk/night
// cross-fade with sun/moon glow + procedural stars), distance fog, drifting
// billboard clouds and instanced rain streaks that toggle on/off.
// Contract: build(ctx) -> { group, setMode('day'|'night'|'dusk'), setRain(bool),
//   update(dt), applyScene(scene), dispose }.  applyScene installs scene.fog +
//   scene.background (and hides environment.js's static sky dome while active).
// Perf: rain is ONE InstancedMesh of camera-facing quads (2 tris/drop, 4500
// drops = 9000 tris < 20k); sky 960 tris; 16 cloud sprites = 32 tris.
// Headless-safe: cloud texture is a procedural DataTexture (no DOM/canvas).
import * as THREE from 'three';
import { BUILDING, totalHeight } from './config.js';

const TAU = Math.PI * 2;

// Deterministic PRNG (same family as environment.js) — identical weather layout
// on every load.
function mulberry32(seed) {
  let a = seed >>> 0;
  return function () {
    a |= 0; a = (a + 0x6D2B79F5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const clamp01 = (x) => (x < 0 ? 0 : x > 1 ? 1 : x);
// JS smoothstep that also accepts reversed edges (a > b).
const sstep = (a, b, x) => { const t = clamp01((x - a) / (b - a)); return t * t * (3 - 2 * t); };

// Sky radius sits inside camera.js's far plane (max(300, height*8) ≈ 304) and
// the dome is re-centred on the camera every frame, so it never clips.
const SKY_RADIUS = 270;
const RAIN_COUNT = 4500;          // × 2 tris = 9000 tris (< 20k budget)
const RAIN_RANGE = 110;           // half-width of the rain volume (m)
const RAIN_TOP = 105;             // respawn height (m), ~2.7× tower height
const CLOUD_COUNT = 16;

// Per-mode palette. Fog/background day+night values match lighting.js SKY so
// the two modules never fight over scene.fog.color / scene.background.
const MODES = {
  day: {
    top: 0x2c6fbb, horizon: 0xd9ecf4, ground: 0xc8d8e4, exponent: 0.82,
    sunDir: [0.45, 0.72, 0.35], sunColor: 0xfff3d6, sunIntensity: 1.0, halo: 0.22,
    stars: 0, fog: 0xcfdfee, fogNear: 90, fogFar: 285, bg: 0xbfd9ee,
    cloud: 0xffffff, cloudOp: 0.8, overcast: 0x9aa4ad, rain: 0xc4d2e0, rainOp: 0.5,
  },
  dusk: {
    top: 0x1f2f5e, horizon: 0xff9d5c, ground: 0x8a6a62, exponent: 0.55,
    sunDir: [-0.75, 0.09, -0.4], sunColor: 0xffb066, sunIntensity: 1.3, halo: 0.5,
    stars: 0.15, fog: 0xc08a72, fogNear: 70, fogFar: 245, bg: 0xd9a07a,
    cloud: 0xffb98a, cloudOp: 0.72, overcast: 0x6a5a60, rain: 0xe0c0a8, rainOp: 0.5,
  },
  night: {
    top: 0x04081a, horizon: 0x16233f, ground: 0x0a0f1c, exponent: 0.95,
    sunDir: [-0.45, 0.6, -0.35], sunColor: 0xcfe0ff, sunIntensity: 0.45, halo: 0.1,
    stars: 1, fog: 0x0b1122, fogNear: 55, fogFar: 215, bg: 0x0a1020,
    cloud: 0x33415f, cloudOp: 0.5, overcast: 0x0a0e16, rain: 0x93a6c8, rainOp: 0.42,
  },
};
const RAIN_FOG_TINT = 0x9aa2ab;   // rain desaturates fog toward gray
const RAIN_CLOUD_TINT = 0x77808c; // rain darkens clouds

// Module-level link so the standalone applyScene() export and the instance
// built by build() share state (single-scene dashboard, lighting.js pattern).
let activeApi = null;
export function applyScene(scene) {
  return activeApi ? activeApi.applyScene(scene) : null;
}

// Procedural cloud puff: RGBA DataTexture (works headless, no canvas).
function makeCloudTexture(T, rand) {
  const S = 128;
  const G = 32;
  const grid = new Float32Array(G * G);
  for (let i = 0; i < grid.length; i++) grid[i] = rand();
  const noise = (x, y) => {
    const xi = Math.floor(x), yi = Math.floor(y);
    const fx = sstep(0, 1, x - xi), fy = sstep(0, 1, y - yi);
    const at = (ix, iy) => grid[((iy % G) + G) % G * G + (((ix % G) + G) % G)];
    const a = at(xi, yi), b = at(xi + 1, yi), c = at(xi, yi + 1), d = at(xi + 1, yi + 1);
    return (a + (b - a) * fx) + ((c + (d - c) * fx) - (a + (b - a) * fx)) * fy;
  };
  const data = new Uint8Array(S * S * 4);
  for (let y = 0; y < S; y++) {
    for (let x = 0; x < S; x++) {
      const u = (x / (S - 1)) * 2 - 1, v = (y / (S - 1)) * 2 - 1;
      const r = Math.sqrt(u * u + (v * 2.1) * (v * 2.1)); // flattened puff
      const n = 0.6 * noise(u * 2.2 + 7.3, v * 2.2 + 2.9)
        + 0.3 * noise(u * 4.4 + 13.7, v * 4.4 + 5.1)
        + 0.1 * noise(u * 8.8 + 29.3, v * 8.8 + 11.7);
      const a = clamp01(sstep(1.0, 0.42, r) * (0.45 + 0.75 * n) - 0.1);
      const i = (y * S + x) * 4;
      data[i] = 255; data[i + 1] = 255; data[i + 2] = 255;
      data[i + 3] = Math.round(a * 255);
    }
  }
  const tex = new T.DataTexture(data, S, S, T.RGBAFormat);
  tex.magFilter = T.LinearFilter;
  tex.minFilter = T.LinearMipmapLinearFilter;
  tex.generateMipmaps = true;
  tex.wrapS = tex.wrapT = T.ClampToEdgeWrapping;
  tex.needsUpdate = true;
  return tex;
}

export function build(ctx = {}) {
  const T = ctx.THREE || THREE;
  const B = (ctx.config && ctx.config.BUILDING) || BUILDING;
  const totalFn = (ctx.config && typeof ctx.config.totalHeight === 'function')
    ? ctx.config.totalHeight : totalHeight;
  const towerH = totalFn();
  const root = ctx.root || null;
  const rand = mulberry32(0x5eed2027);
  const rr = (a, b) => a + (b - a) * rand();

  const group = new T.Group();
  group.name = 'weather';
  group.userData = { module: 'weather' };

  // ---- animated state (current values drive uniforms/fog by reference) -----
  const pal = {};
  for (const [k, p] of Object.entries(MODES)) {
    pal[k] = {
      top: new T.Color(p.top), horizon: new T.Color(p.horizon), ground: new T.Color(p.ground),
      sunColor: new T.Color(p.sunColor), fog: new T.Color(p.fog), bg: new T.Color(p.bg),
      cloud: new T.Color(p.cloud), overcast: new T.Color(p.overcast), rain: new T.Color(p.rain),
      sunDir: new T.Vector3(...p.sunDir).normalize(),
      exponent: p.exponent, sunIntensity: p.sunIntensity, halo: p.halo, stars: p.stars,
      fogNear: p.fogNear, fogFar: p.fogFar, cloudOp: p.cloudOp, rainOp: p.rainOp,
    };
  }
  const grayFog = new T.Color(RAIN_FOG_TINT);
  const rainCloudTint = new T.Color(RAIN_CLOUD_TINT);
  const copyState = (src) => ({
    top: src.top.clone(), horizon: src.horizon.clone(), ground: src.ground.clone(),
    sunColor: src.sunColor.clone(), fog: src.fog.clone(), bg: src.bg.clone(),
    cloud: src.cloud.clone(), overcast: src.overcast.clone(), rain: src.rain.clone(),
    sunDir: src.sunDir.clone(),
    exponent: src.exponent, sunIntensity: src.sunIntensity, halo: src.halo, stars: src.stars,
    fogNear: src.fogNear, fogFar: src.fogFar, cloudOp: src.cloudOp, rainOp: src.rainOp,
    overcastMix: 0, rainMix: 0, time: 0,
  });
  const cur = copyState(pal.day);
  const tgt = copyState(pal.day);

  let mode = 'day';
  let rainOn = false;
  let rainOpacity = 0;

  // ---- sky dome -------------------------------------------------------------
  const skyGeo = new T.SphereGeometry(SKY_RADIUS, 32, 16);
  const skyMat = new T.ShaderMaterial({
    uniforms: {
      uTop: { value: cur.top },
      uHorizon: { value: cur.horizon },
      uGround: { value: cur.ground },
      uExponent: { value: cur.exponent },
      uSunDir: { value: cur.sunDir },
      uSunColor: { value: cur.sunColor },
      uSunIntensity: { value: cur.sunIntensity },
      uHalo: { value: cur.halo },
      uStars: { value: cur.stars },
      uOvercast: { value: 0 },
      uOvercastColor: { value: cur.overcast },
      uTime: { value: 0 },
    },
    vertexShader: `
      varying vec3 vDir;
      void main() {
        vec4 wp = modelMatrix * vec4(position, 1.0);
        vDir = wp.xyz - cameraPosition;
        gl_Position = projectionMatrix * viewMatrix * wp;
      }
    `,
    fragmentShader: `
      uniform vec3 uTop; uniform vec3 uHorizon; uniform vec3 uGround;
      uniform float uExponent;
      uniform vec3 uSunDir; uniform vec3 uSunColor;
      uniform float uSunIntensity; uniform float uHalo;
      uniform float uStars; uniform float uOvercast; uniform vec3 uOvercastColor;
      uniform float uTime;
      varying vec3 vDir;
      float hash31(vec3 p) {
        p = fract(p * 0.3183099 + vec3(0.11, 0.17, 0.13));
        p *= 17.0;
        return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
      }
      void main() {
        vec3 dir = normalize(vDir);
        float h = max(dir.y, 0.0);
        vec3 col = mix(uHorizon, uTop, pow(h, uExponent));
        col = mix(col, uGround, 1.0 - smoothstep(-0.18, 0.0, dir.y));
        // Sun / moon disc + halo.
        float sd = max(dot(dir, uSunDir), 0.0);
        float disc = pow(sd, 700.0) * 1.7;
        float halo = pow(sd, 5.0) * uHalo;
        col += uSunColor * (disc + halo) * uSunIntensity * (1.0 - uOvercast);
        // Procedural stars (cell hash), twinkling, faded near horizon.
        if (uStars > 0.003) {
          vec3 g = dir * 150.0;
          vec3 cell = floor(g);
          vec3 f = fract(g) - 0.5;
          float h1 = hash31(cell);
          if (h1 > 0.986) {
            vec3 rp = vec3(hash31(cell + 1.7), hash31(cell + 3.1), hash31(cell + 5.3)) - 0.5;
            float d = length(f - rp * 0.55);
            float tw = 0.65 + 0.35 * sin(uTime * 2.4 + h1 * 90.0);
            float s = (1.0 - smoothstep(0.0, 0.055, d)) * tw * uStars
              * smoothstep(-0.02, 0.25, dir.y) * (1.0 - uOvercast);
            col += vec3(0.9, 0.95, 1.0) * s;
          }
        }
        col = mix(col, uOvercastColor, uOvercast);
        gl_FragColor = vec4(col, 1.0);
      }
    `,
    side: T.BackSide,
    depthWrite: false,
    fog: false,
  });
  const sky = new T.Mesh(skyGeo, skyMat);
  sky.name = 'weather-sky';
  sky.userData = { module: 'weather', kind: 'sky' };
  sky.castShadow = false;
  sky.receiveShadow = false;
  // renderOrder 0 (after environment.js's dome at -1) so this sky wins the
  // overdraw when both exist; applyScene() also hides the static dome.
  group.add(sky);

  // ---- billboard clouds (Sprites auto-face the camera) ----------------------
  const cloudTex = makeCloudTexture(T, rand);
  const cloudGroup = new T.Group();
  cloudGroup.name = 'weather-clouds';
  group.add(cloudGroup);
  const cloudMats = [0.95, 0.75, 0.55].map((f) => {
    const m = new T.SpriteMaterial({
      map: cloudTex, color: cur.cloud.clone(), transparent: true,
      opacity: cur.cloudOp * f, depthWrite: false, fog: true,
    });
    m.userData = { opFactor: f };
    return m;
  });
  const clouds = [];
  for (let i = 0; i < CLOUD_COUNT; i++) {
    const mat = cloudMats[i % cloudMats.length];
    const sp = new T.Sprite(mat);
    sp.name = `weather-cloud-${String(i + 1).padStart(2, '0')}`;
    sp.userData = { module: 'weather', kind: 'cloud' };
    const ang = rr(0, TAU);
    const rad = rr(60, 240);
    sp.position.set(Math.cos(ang) * rad, towerH * 2.4 + rr(0, 55), Math.sin(ang) * rad);
    sp.scale.set(rr(60, 130), rr(18, 38), 1);
    cloudGroup.add(sp);
    clouds.push({ sprite: sp, vx: rr(1.2, 3.0) });
  }

  // ---- instanced rain --------------------------------------------------------
  // One InstancedMesh of view-aligned streak quads. The vertex shader extracts
  // each instance centre + uniform scale, then offsets in VIEW space so every
  // streak faces the camera without per-frame rotation work.
  const rainGeo = new T.PlaneGeometry(0.07, 1.5); // 2 tris per drop
  const rainMat = new T.ShaderMaterial({
    uniforms: T.UniformsUtils.merge([
      T.UniformsLib.fog,
      { uColor: { value: new T.Color(MODES.day.rain) }, uOpacity: { value: 0 } },
    ]),
    vertexShader: `
      #include <common>
      #include <fog_pars_vertex>
      varying vec2 vUv;
      void main() {
        vUv = uv;
        vec4 mvPosition = modelViewMatrix * instanceMatrix * vec4(0.0, 0.0, 0.0, 1.0);
        float s = length(instanceMatrix[0].xyz);
        mvPosition.xy += position.xy * s;
        gl_Position = projectionMatrix * mvPosition;
        #include <fog_vertex>
      }
    `,
    fragmentShader: `
      uniform vec3 uColor; uniform float uOpacity;
      varying vec2 vUv;
      #include <common>
      #include <fog_pars_fragment>
      void main() {
        float edge = smoothstep(0.0, 0.35, vUv.x) * (1.0 - smoothstep(0.65, 1.0, vUv.x));
        float tip = smoothstep(0.0, 0.12, vUv.y) * (1.0 - smoothstep(0.88, 1.0, vUv.y));
        float a = uOpacity * edge * tip;
        if (a < 0.01) discard;
        gl_FragColor = vec4(uColor, a);
        #include <fog_fragment>
      }
    `,
    transparent: true,
    depthWrite: false,
    side: T.DoubleSide,
    fog: true,
  });
  rainMat.uniforms.uColor.value = cur.rain; // animate by reference
  const rain = new T.InstancedMesh(rainGeo, rainMat, RAIN_COUNT);
  rain.name = 'weather-rain';
  rain.userData = { module: 'weather', kind: 'rain', instances: RAIN_COUNT };
  rain.instanceMatrix.setUsage(T.DynamicDrawUsage);
  rain.frustumCulled = false; // volume follows the camera; bounds stay valid
  rain.castShadow = false;
  rain.receiveShadow = false;
  rain.visible = false;
  const rainRoot = new T.Group();
  rainRoot.name = 'weather-rain-root';
  rainRoot.add(rain);
  group.add(rainRoot);

  const px = new Float32Array(RAIN_COUNT);
  const py = new Float32Array(RAIN_COUNT);
  const pz = new Float32Array(RAIN_COUNT);
  const pv = new Float32Array(RAIN_COUNT); // fall speed
  const ps = new Float32Array(RAIN_COUNT); // uniform scale
  const dummy = new T.Object3D();
  const WIND_X = 2.2, WIND_Z = 1.0;
  for (let i = 0; i < RAIN_COUNT; i++) {
    px[i] = rr(-RAIN_RANGE, RAIN_RANGE);
    py[i] = rr(0, RAIN_TOP);
    pz[i] = rr(-RAIN_RANGE, RAIN_RANGE);
    pv[i] = rr(26, 44);
    ps[i] = rr(0.7, 1.4);
    dummy.position.set(px[i], py[i], pz[i]);
    dummy.scale.setScalar(ps[i]);
    dummy.rotation.set(0, 0, 0);
    dummy.updateMatrix();
    rain.setMatrixAt(i, dummy.matrix);
  }
  rain.instanceMatrix.needsUpdate = true;

  // ---- fog + background ------------------------------------------------------
  const fog = new T.Fog(cur.fog.getHex(), cur.fogNear, cur.fogFar);
  fog.color = cur.fog; // animate by reference
  let sceneRef = null;
  let prevBackground = null;
  let prevFog = null;
  const hiddenDomes = [];

  // ---- camera tracking (sky recentre + rain volume follow) -------------------
  let camera = ctx.camera || null;
  let camScan = 0;
  const camWorld = new T.Vector3();
  const camLocal = new T.Vector3();
  function findCamera() {
    if (camera) return camera;
    let o = group;
    while (o && !o.isScene) o = o.parent;
    if (!o) return null;
    const named = o.getObjectByName('twin-camera');
    if (named && named.isCamera) { camera = named; return camera; }
    let found = null;
    o.traverse((n) => { if (!found && n.isCamera) found = n; });
    camera = found;
    return camera;
  }

  // ---- targets + per-frame animation -----------------------------------------
  function computeTargets() {
    const p = pal[mode];
    const rm = cur.rainMix;
    tgt.top.copy(p.top); tgt.horizon.copy(p.horizon); tgt.ground.copy(p.ground);
    tgt.sunColor.copy(p.sunColor); tgt.sunDir.copy(p.sunDir);
    tgt.exponent = p.exponent; tgt.halo = p.halo;
    tgt.sunIntensity = p.sunIntensity * (1 - 0.7 * rm);
    tgt.stars = p.stars * (1 - rm);
    tgt.fog.copy(p.fog).lerp(grayFog, 0.5 * rm);
    tgt.fogNear = p.fogNear * (1 - 0.3 * rm);
    tgt.fogFar = p.fogFar * (1 - 0.42 * rm);
    tgt.bg.copy(p.bg).lerp(grayFog, 0.35 * rm);
    tgt.cloud.copy(p.cloud).lerp(rainCloudTint, 0.6 * rm);
    tgt.cloudOp = Math.min(0.95, p.cloudOp + 0.12 * rm);
    tgt.overcast.copy(p.overcast);
    tgt.overcastMix = 0.45 * rm;
    tgt.rain.copy(p.rain);
    tgt.rainOp = p.rainOp;
  }

  function setMode(next) {
    if (next !== 'day' && next !== 'night' && next !== 'dusk') return mode;
    mode = next;
    return mode;
  }
  function setRain(on) {
    rainOn = !!on;
    if (rainOn) rain.visible = true;
    return rainOn;
  }

  // Accepts update(dt) (task contract) or update(status, dt) (module contract).
  function update(a, b) {
    const dt = Math.min(Math.max(typeof b === 'number' ? b : (typeof a === 'number' ? a : 0.016), 0), 0.1);
    if (dt <= 0) return;
    cur.time += dt;

    // Rain amount + opacity fade.
    const rainTarget = rainOn ? 1 : 0;
    cur.rainMix += (rainTarget - cur.rainMix) * (1 - Math.exp(-3 * dt));
    if (Math.abs(rainTarget - cur.rainMix) < 0.002) cur.rainMix = rainTarget;
    rainOpacity += ((rainOn ? tgt.rainOp : 0) - rainOpacity) * (1 - Math.exp(-4 * dt));
    if (!rainOn && rainOpacity < 0.01) { rainOpacity = 0; rain.visible = false; }

    computeTargets();
    const k = 1 - Math.exp(-2.6 * dt);
    cur.top.lerp(tgt.top, k); cur.horizon.lerp(tgt.horizon, k); cur.ground.lerp(tgt.ground, k);
    cur.sunColor.lerp(tgt.sunColor, k); cur.fog.lerp(tgt.fog, k); cur.bg.lerp(tgt.bg, k);
    cur.cloud.lerp(tgt.cloud, k); cur.overcast.lerp(tgt.overcast, k); cur.rain.lerp(tgt.rain, k);
    cur.sunDir.lerp(tgt.sunDir, k).normalize();
    cur.exponent += (tgt.exponent - cur.exponent) * k;
    cur.sunIntensity += (tgt.sunIntensity - cur.sunIntensity) * k;
    cur.halo += (tgt.halo - cur.halo) * k;
    cur.stars += (tgt.stars - cur.stars) * k;
    cur.fogNear += (tgt.fogNear - cur.fogNear) * k;
    cur.fogFar += (tgt.fogFar - cur.fogFar) * k;
    cur.cloudOp += (tgt.cloudOp - cur.cloudOp) * k;
    cur.overcastMix += (tgt.overcastMix - cur.overcastMix) * k;

    // Push numeric uniforms (colors/vectors are shared by reference).
    const u = skyMat.uniforms;
    u.uExponent.value = cur.exponent;
    u.uSunIntensity.value = cur.sunIntensity;
    u.uHalo.value = cur.halo;
    u.uStars.value = cur.stars;
    u.uOvercast.value = cur.overcastMix;
    u.uTime.value = cur.time;
    fog.near = cur.fogNear;
    fog.far = cur.fogFar;
    for (const m of cloudMats) {
      m.color.copy(cur.cloud);
      m.opacity = cur.cloudOp * m.userData.opFactor;
    }
    rainMat.uniforms.uOpacity.value = rainOpacity;

    // Cloud drift (wrap around the world edge).
    for (const c of clouds) {
      c.sprite.position.x += c.vx * dt;
      if (c.sprite.position.x > 270) c.sprite.position.x = -270;
    }

    // Rain simulation — only while visible.
    if (rain.visible && rainOpacity > 0.005) {
      for (let i = 0; i < RAIN_COUNT; i++) {
        py[i] -= pv[i] * dt;
        px[i] += WIND_X * dt;
        pz[i] += WIND_Z * dt;
        if (py[i] < 0) {
          py[i] += RAIN_TOP;
          px[i] = rr(-RAIN_RANGE, RAIN_RANGE);
          pz[i] = rr(-RAIN_RANGE, RAIN_RANGE);
        }
        if (px[i] > RAIN_RANGE) px[i] -= RAIN_RANGE * 2;
        if (pz[i] > RAIN_RANGE) pz[i] -= RAIN_RANGE * 2;
        dummy.position.set(px[i], py[i], pz[i]);
        dummy.scale.setScalar(ps[i]);
        dummy.updateMatrix();
        rain.setMatrixAt(i, dummy.matrix);
      }
      rain.instanceMatrix.needsUpdate = true;
    }

    // Follow the camera: recentre the sky dome (never clips) and move the rain
    // volume with the viewpoint. Re-scan periodically (camera may attach late).
    camScan += dt;
    if (!camera || camScan >= 0.5) { camScan = 0; findCamera(); }
    if (camera) {
      camera.getWorldPosition(camWorld);
      group.updateWorldMatrix(true, false);
      camLocal.copy(camWorld);
      group.worldToLocal(camLocal);
      sky.position.copy(camLocal);
      rainRoot.position.set(camLocal.x, 0, camLocal.z);
    }
  }

  // ---- scene integration ------------------------------------------------------
  function applyScene(scene) {
    if (!scene || !scene.isScene) return null;
    if (sceneRef === scene) return scene;
    if (sceneRef) restoreScene();
    prevBackground = scene.background;
    prevFog = scene.fog;
    scene.background = cur.bg;
    scene.fog = fog;
    // Hide environment.js's static day-only dome so the animated sky is the
    // single source of truth (restored on dispose).
    scene.traverse((o) => {
      if (o.name === 'env-sky-dome' && o.visible) { o.visible = false; hiddenDomes.push(o); }
    });
    sceneRef = scene;
    findCamera();
    return scene;
  }
  function restoreScene() {
    if (!sceneRef) return;
    if (sceneRef.background === cur.bg) sceneRef.background = prevBackground;
    if (sceneRef.fog === fog) sceneRef.fog = prevFog;
    for (const o of hiddenDomes) o.visible = true;
    hiddenDomes.length = 0;
    sceneRef = null;
  }

  function dispose() {
    restoreScene();
    group.removeFromParent();
    rain.dispose();
    skyGeo.dispose();
    rainGeo.dispose();
    skyMat.dispose();
    rainMat.dispose();
    for (const m of cloudMats) m.dispose();
    cloudTex.dispose();
    if (activeApi === api) activeApi = null;
  }

  computeTargets();
  if (root) root.add(group);
  findCamera();

  const api = {
    group,
    sky, clouds: cloudGroup, rain,
    setMode, getMode: () => mode,
    setRain, isRaining: () => rainOn,
    update,
    applyScene,
    dispose,
  };
  activeApi = api;
  return api;
}
