import * as THREE from 'three';
import { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js';
import { MeshoptDecoder } from 'three/addons/libs/meshopt_decoder.module.js';
import { RoomEnvironment } from 'three/addons/environments/RoomEnvironment.js';
import { search, routesTo, flowTraffic, roadInfo, testRoute, testAlternative, stepLabel, ARROWS, onNote } from './nav.js';
import { directionTexture, limitTexture, stopTexture, yieldTexture, crossingTexture, radarTexture } from './signs.js';
import { RoadData } from './roaddata.js';
import { Link } from './link.js';
import { icon } from './icons.js';
import { parseShared, googleMapsNav, wazeLink, sharePlace } from './sharedplaces.js';
import { VoiceGuide } from './voice.js';

// ------------------------------------------------------------------ constants (same road model as the Android app)
const CAR_LEN = 4.4;
const SEGS = 46, SEG = 2, BACK = 12, GRID_STEP = 0.5;
const GRID = Math.floor((BACK + SEGS * SEG) / GRID_STEP) + 2;
const LANE_W = 3.7;
const $ = (id) => document.getElementById(id);

// Procedural bends: heading (rad, + = turning right) as a function of metres driven.
const theta = (d) => -0.012 * 90 * Math.cos(d / 90) - 0.008 * 37 * Math.cos(d / 37 + 1);
let course = null, s0 = 0, odo0 = 0;
const thetaFn = (d) => (course ? course.theta(s0 + (d - odo0)) : theta(d));
const kappa = (d) => (thetaFn(d + 6) - thetaFn(d - 6)) / 12;
const clamp = (v, a, b) => Math.min(b, Math.max(a, v));
const hash = (i, k) => { let h = (i * 73856093) ^ (k * 19349663); h = Math.imul(h ^ (h >>> 13), 1274126177); return ((h ^ (h >>> 16)) & 0xffff) / 65535; };

// ------------------------------------------------------------------ renderer / scene
const canvas = $('gl');
const renderer = new THREE.WebGLRenderer({ canvas, antialias: true, powerPreference: 'high-performance' });
renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
renderer.toneMapping = THREE.ACESFilmicToneMapping;
renderer.shadowMap.enabled = false;

const scene = new THREE.Scene();
const camera = new THREE.PerspectiveCamera(45, 1, 0.1, 400);
const pmrem = new THREE.PMREMGenerator(renderer);
scene.environment = pmrem.fromScene(new RoomEnvironment(), 0.04).texture;

const sun = new THREE.DirectionalLight(0xfff4e0, 2.4);
sun.position.set(-30, 50, 20);
scene.add(sun);
const hemi = new THREE.HemisphereLight(0xcfe3ff, 0x2d4a2a, 0.6);
scene.add(hemi);

const DAY = { bg: 0xcfe0f2, fog: 0xcfe0f2, sun: 2.4, hemi: 0.6, env: 1.0, asphalt: 0x30343b, grass: 0x2d6b34 };
const NIGHT = { bg: 0x070b18, fog: 0x070b18, sun: 0.25, hemi: 0.18, env: 0.12, asphalt: 0x15171c, grass: 0x0f2415 };
let night = false;

const asphalt = new THREE.MeshStandardMaterial({ color: DAY.asphalt, roughness: 0.9 });
const TINTS = [0x625712, 0x6e3c0f, 0x701818];            // asphalt with some traffic / dense / jam
const roadMats = [asphalt, ...TINTS.map((c) => new THREE.MeshStandardMaterial({ color: c, roughness: 0.9 }))];
const paint = new THREE.MeshStandardMaterial({ color: 0xe8e8e8, roughness: 0.6 });
const grassMat = new THREE.MeshStandardMaterial({ color: DAY.grass, roughness: 1 });
const ground = new THREE.Mesh(new THREE.PlaneGeometry(600, 600), grassMat);
ground.rotation.x = -Math.PI / 2; ground.position.y = -0.03;
scene.add(ground);

// ------------------------------------------------------------------ road
const gx = new Float32Array(GRID), gz = new Float32Array(GRID);
const CB = -14.75;                       // centre of the second carriageway on a motorway
let hwMode = false, roadMode = 'auto';    // roadMode: auto (route / GPS), city, hw
const barrierMat = new THREE.MeshStandardMaterial({ color: 0x9aa1aa, metalness: 0.5, roughness: 0.5 });
const roadSegs = [];
for (let i = 0; i < SEGS; i++) {
  const road = new THREE.Mesh(new THREE.BoxGeometry(2 * LANE_W, 0.02, SEG + 0.08), asphalt);
  const eL = new THREE.Mesh(new THREE.BoxGeometry(0.14, 0.012, SEG + 0.08), paint);
  const eR = new THREE.Mesh(new THREE.BoxGeometry(0.14, 0.012, SEG + 0.08), paint);
  const dash = new THREE.Mesh(new THREE.BoxGeometry(0.14, 0.012, 1.2), paint);
  const roadB = new THREE.Mesh(new THREE.BoxGeometry(2 * LANE_W, 0.02, SEG + 0.08), asphalt);
  const eBL = new THREE.Mesh(new THREE.BoxGeometry(0.14, 0.012, SEG + 0.08), paint);
  const eBR = new THREE.Mesh(new THREE.BoxGeometry(0.14, 0.012, SEG + 0.08), paint);
  const dashB = new THREE.Mesh(new THREE.BoxGeometry(0.14, 0.012, 1.2), paint);
  const barrier = new THREE.Mesh(new THREE.BoxGeometry(0.45, 0.75, SEG + 0.08), barrierMat);
  for (const m of [roadB, eBL, eBR, dashB, barrier]) { m.visible = false; scene.add(m); }
  scene.add(road, eL, eR, dash);
  roadSegs.push({ road, eL, eR, dash, roadB, eBL, eBR, dashB, barrier });
}

/** x, z and yaw of the road at distance d ahead (+lat to the right of our lane centre). */
function pose(d, lat, d0, th0) {
  const dEnd = -BACK + (GRID - 1) * GRID_STEP;
  const dd = Math.min(d, dEnd);
  const k = clamp(Math.floor((dd + BACK) / GRID_STEP), 0, GRID - 1);
  const phi = thetaFn(d0 + dd) - th0;
  const cp = Math.cos(phi), sp = Math.sin(phi), ext = d - dd;
  return { x: gx[k] + lat * cp + sp * ext, z: gz[k] + lat * sp - cp * ext, yaw: -phi };
}

function updateRoad(d0) {
  const th0 = thetaFn(d0), k0 = Math.floor(BACK / GRID_STEP);
  gx[k0] = 0; gz[k0] = 0;
  for (let k = k0 + 1; k < GRID; k++) {
    const phi = thetaFn(d0 + (k - k0 - 0.5) * GRID_STEP) - th0;
    gx[k] = gx[k - 1] + Math.sin(phi) * GRID_STEP; gz[k] = gz[k - 1] - Math.cos(phi) * GRID_STEP;
  }
  for (let k = k0 - 1; k >= 0; k--) {
    const phi = thetaFn(d0 + (k - k0 + 0.5) * GRID_STEP) - th0;
    gx[k] = gx[k + 1] - Math.sin(phi) * GRID_STEP; gz[k] = gz[k + 1] + Math.cos(phi) * GRID_STEP;
  }
  const baseIdx = Math.floor(d0 / SEG), frac = d0 - baseIdx * SEG, cA = -LANE_W / 2;
  roadSegs.forEach((s, j) => {
    const d = -BACK + j * SEG - frac;
    const k = clamp(Math.floor((d + BACK) / GRID_STEP), 0, GRID - 1);
    const phi = thetaFn(d0 + d) - th0, cp = Math.cos(phi), sp = Math.sin(phi), yaw = -phi;
    const place = (m, lat, y) => { m.position.set(gx[k] + lat * cp, y, gz[k] + lat * sp); m.rotation.y = yaw; };
    place(s.road, cA, 0); place(s.eR, cA + LANE_W, 0.003); place(s.eL, cA - LANE_W, 0.003); place(s.dash, cA, 0.004);
    const lv = course ? course.trafficLevelAt(s0 + (d0 - odo0) + d) : 0;
    if (s.lv !== lv) { s.lv = lv; s.road.material = roadMats[lv]; }
    const n = baseIdx + j - BACK / SEG;
    const dashOn = ((n % 3) + 3) % 3 === 0;
    s.dash.visible = dashOn;
    s.roadB.visible = s.eBL.visible = s.eBR.visible = s.barrier.visible = hwMode; s.dashB.visible = hwMode && dashOn;
    if (hwMode) {
      place(s.roadB, CB, 0); place(s.eBR, CB + LANE_W, 0.003); place(s.eBL, CB - LANE_W, 0.003); place(s.dashB, CB, 0.004);
      place(s.barrier, (cA - LANE_W + CB + LANE_W) / 2, 0.375);
    }
  });
}

// ------------------------------------------------------------------ roadside props (lamps and trees), recycled along the road
function makeLamp() {
  const g = new THREE.Group();
  const metal = new THREE.MeshStandardMaterial({ color: 0x40464f, metalness: 0.6, roughness: 0.5 });
  const post = new THREE.Mesh(new THREE.CylinderGeometry(0.07, 0.09, 7, 8), metal); post.position.y = 3.5;
  const arm = new THREE.Mesh(new THREE.BoxGeometry(1.8, 0.09, 0.09), metal); arm.position.set(0.9, 7, 0);
  const lamp = new THREE.Mesh(new THREE.BoxGeometry(0.6, 0.12, 0.3), new THREE.MeshStandardMaterial({ color: 0xffffff, emissive: 0xffe9b0, emissiveIntensity: 0 }));
  lamp.position.set(1.6, 6.92, 0); lamp.name = 'bulb';
  g.add(post, arm, lamp);
  return g;
}
function makeTree(i) {
  const g = new THREE.Group();
  const trunk = new THREE.Mesh(new THREE.CylinderGeometry(0.18, 0.25, 2.2, 6), new THREE.MeshStandardMaterial({ color: 0x6b4a2e, roughness: 1 }));
  trunk.position.y = 1.1; g.add(trunk);
  const leaf = new THREE.MeshStandardMaterial({ color: i % 2 ? 0x2f8f4e : 0x3d9a58, roughness: 0.9, flatShading: true });
  for (let c = 0; c < 3; c++) {
    const cone = new THREE.Mesh(new THREE.ConeGeometry(1.9 - c * 0.45, 2.6, 7), leaf);
    cone.position.y = 3 + c * 1.5; g.add(cone);
  }
  return g;
}
const CATS = [
  { name: 'lamp', spacing: 32, ahead: 100, back: 14, edge: 1.1, jitter: 0, make: makeLamp, rot: true },
  { name: 'tree', spacing: 12, ahead: 100, back: 14, edge: 5, jitter: 7, make: makeTree, rot: false },
];
for (const c of CATS) {
  c.n = Math.ceil((c.ahead + c.back) / c.spacing) + 2;
  c.slots = [];
  for (const side of [-1, 1]) for (let s = 0; s < c.n; s++) {
    const obj = c.make(s); obj.visible = false; scene.add(obj); c.slots.push({ obj, side, s });
  }
}
function updateProps(d0, th0) {
  for (const c of CATS) {
    for (const sl of c.slots) {
      const nStart = Math.floor((d0 - c.back) / c.spacing);
      const k = nStart + ((((sl.s - nStart) % c.n) + c.n) % c.n);
      const d = k * c.spacing + (sl.side < 0 ? c.spacing / 2 : 0) - d0;
      const vis = d >= -c.back && d <= c.ahead;
      sl.obj.visible = vis;
      if (!vis) continue;
      const off = c.edge + c.jitter * hash(k, sl.side + 3);
      const lat = sl.side > 0 ? 1.85 + off : (hwMode ? -18.45 : -5.55) - off;
      const p = pose(d, lat, d0, th0);
      sl.obj.position.set(p.x, 0, p.z);
      sl.obj.rotation.y = p.yaw + (c.rot && sl.side > 0 ? Math.PI : 0) + (c.rot ? 0 : hash(k, 9) * 6.28);
      if (!c.rot) { const f = 0.8 + 0.5 * hash(k, 5); sl.obj.scale.setScalar(f); }
      const bulb = sl.obj.getObjectByName('bulb');
      if (bulb) bulb.material.emissiveIntensity = night ? 4 : 0;
    }
  }
}

// ------------------------------------------------------------------ the car
const carRoot = new THREE.Group();      // yaw / roll / pitch / park offsets
const carInner = new THREE.Group();     // scaled, centred model
carRoot.add(carInner);
scene.add(carRoot);
const wheelPairs = [];
const lampMats = { head: [], tail: [], brake: [], sigL: [], sigR: [], tailL: [], tailR: [] };
const bodyMats = [];
const headSpots = [];
let carReady = false;
let stripRig = null;           // thin light strips drawn on the original Model 3's lamp clusters
const doorPivots = [];

function classifyMaterial(m) {
  const n = (m.name || '').toLowerCase();
  if (/^primary/.test(n)) bodyMats.push(m);
  if (/left.{0,6}rear.?light/.test(n)) { m.userData.keepMap = true; lampMats.tailL.push(m); }          // the visible rear clusters double as turn signals
  else if (/right.{0,6}rear.?light/.test(n)) { m.userData.keepMap = true; lampMats.tailR.push(m); }
  else if (/indicator/.test(n)) (/_l/.test(n) ? lampMats.sigL : lampMats.sigR).push(m);
  else if (/brea?k/.test(n)) lampMats.brake.push(m);
  else if (/rear.?light/.test(n)) lampMats.tail.push(m);
  else if (/(front.{0,6}light|fog)/.test(n)) lampMats.head.push(m);
}

function buildTesla(gltf) {
  const model = gltf.scene;
  // the full-width rear light bar shares its material with the right rear cluster: give it its own so a right-turn signal lights only the cluster
  { model.updateMatrixWorld(true); const cand = [];
    model.traverse((o) => { if (o.isMesh && o.material && o.material.name === 'right_rear_light') cand.push([o, new THREE.Box3().setFromObject(o).getSize(new THREE.Vector3()).x]); });
    if (cand.length > 1) { cand.sort((p, q) => q[1] - p[1]); const bar = cand[0][0]; bar.material = bar.material.clone(); bar.material.name = 'rear_light_strip'; } }
  // drop the ground-reflection / fake shadow planes baked into the model
  const junk = [];
  model.traverse((o) => {
    if (!o.isMesh) return;
    const names = [o.name].concat([].concat(o.material).map((m) => m.name || "")).join(" ").toLowerCase();
    if (/pantulan|shadow|reflection/.test(names)) junk.push(o); else [].concat(o.material).forEach(classifyMaterial);
  });
  junk.forEach((o) => o.parent && o.parent.remove(o));
  // Place the car using only its body and wheels: the model also carries oversized helper meshes
  // (wiper / steering parts) that reach the floor and 4 m up, which used to lift the car into the air.
  model.updateMatrixWorld(true);
  const ref = new THREE.Box3(), wheelBox = new THREE.Box3();
  model.traverse((o) => {
    if (!o.isMesh) return;
    const b = new THREE.Box3().setFromObject(o);
    if (/^wheels_/.test(o.name)) { ref.union(b); wheelBox.union(b); }
    else if (/^body/.test(o.name)) ref.union(b);
  });
  const stray = [];
  model.traverse((o) => {
    if (!o.isMesh) return;
    const b = new THREE.Box3().setFromObject(o);
    if (b.min.y < wheelBox.min.y - 0.9) stray.push(o);   // only the helper mesh that reaches far below the wheels
  });
  stray.forEach((o) => o.parent && o.parent.remove(o));
  const size = ref.getSize(new THREE.Vector3());
  const s = CAR_LEN / Math.max(size.x, size.z);
  const c = ref.getCenter(new THREE.Vector3());
  carInner.add(model);
  carInner.scale.setScalar(s);
  carInner.position.set(-c.x * s, -wheelBox.min.y * s, -c.z * s);
  carInner.updateMatrixWorld(true);
  const box = new THREE.Box3().setFromObject(model);

  // wheel pairs ("wheels" = front pair, "wheels001" = rear pair) and doors: collect first, then re-parent (changing the tree while traversing it skips nodes)
  const wheelNodes = [], doorNodes = [];
  model.traverse((o) => { if (/^wheels(001)?$/.test(o.name)) wheelNodes.push(o); else if (/^door_(lf|lr|rf|rr)$/.test(o.name)) doorNodes.push(o); });
  for (const o of wheelNodes) {          // spin about the lateral axis through their centre
    const centre = new THREE.Box3().setFromObject(o).getCenter(new THREE.Vector3());
    const pivot = new THREE.Group(); carRoot.add(pivot);
    pivot.position.copy(centre); pivot.updateMatrixWorld(true); pivot.attach(o); wheelPairs.push(pivot);
  }
  for (const o of doorNodes) {           // hinge at the front edge of each door, swing about the vertical axis
    const b = new THREE.Box3().setFromObject(o), c = b.getCenter(new THREE.Vector3());
    const pivot = new THREE.Group(); carRoot.add(pivot); pivot.position.set(c.x, c.y, b.min.z); pivot.updateMatrixWorld(true); pivot.attach(o);
    doorPivots.push({ pivot, sign: c.x >= 0 ? 1 : -1 });
  }

  // Lights of the original Model 3: its lamp materials share one texture atlas and the clusters are solid blocks, so lighting the material lights
  // the whole housing. Instead draw thin glowing strips on the clusters: red at the back (amber while that side's signal blinks), white at the front.
  {
    model.updateMatrixWorld(true);
    const found = {};
    model.traverse((o) => {
      if (!o.isMesh || !o.material) return;
      const n = o.material.name;
      if (n === 'left_rear_light' || n === 'right_rear_light' || n === 'left_front_light' || n === 'right_front_light') found[n] = new THREE.Box3().setFromObject(o);
    });
    const rig = { rear: {}, front: [] };
    const strip = (box, rear) => {
      const sz = box.getSize(new THREE.Vector3()), c = box.getCenter(new THREE.Vector3());
      const m = new THREE.Mesh(new THREE.BoxGeometry(sz.x * 0.85, Math.max(0.012, sz.y * 0.13), 0.012), new THREE.MeshBasicMaterial({ color: 0xff2a1a, toneMapped: false }));
      m.position.set(c.x, rear ? box.max.y - sz.y * 0.3 : c.y - sz.y * 0.05, rear ? box.max.z + 0.004 : box.min.z - 0.12);
      m.visible = false; carRoot.add(m); return m;
    };
    if (found.left_rear_light) rig.rear.left = strip(found.left_rear_light, true);
    if (found.right_rear_light) rig.rear.right = strip(found.right_rear_light, true);
    for (const n of ['left_front_light', 'right_front_light']) if (found[n]) rig.front.push(strip(found[n], false));
    for (const m of rig.front) m.material.color.setRGB(1, 0.97, 0.85);
    if (rig.rear.left || rig.rear.right) stripRig = rig;
  }

  // headlight beams
  const len = box.max.z - box.min.z;
  for (const sx of [-0.7, 0.7]) {
    const sp = new THREE.SpotLight(0xfff2cc, 0, 60, 0.45, 0.5, 1.5);
    sp.position.set(sx, 0.7, -len / 2 + 0.2);
    sp.target.position.set(sx * 0.9, 0.2, -len / 2 - 20);
    carRoot.add(sp, sp.target);
    headSpots.push(sp);
  }
  carReady = true;
}

const loader = new GLTFLoader().setMeshoptDecoder(MeshoptDecoder);

// Selectable main cars. Tesla (the original) has wheels that spin, doors that open and lamps by name; the others are placed generically.
// len = real length in metres, rot = extra yaw (deg) so the front points down the road.
const CARS = [
  { id: 'tesla', name: 'Tesla Model 3 (original)', file: 'assets/tesla_model3.glb' },
  { id: 'modelo3', name: 'Model 3 (ligero)', file: 'assets/cars/modelo3.glb', len: 4.69, rot: 180, body: /^car_paint$/i },
  { id: 'modely', name: 'Tesla Model Y', file: 'assets/cars/tesla_model_y.glb', len: 4.75, rot: 180, body: /^Material_2125767099$/ },
  { id: 'opel', name: 'Opel GT', file: 'assets/cars/opel_gt.glb', len: 4.1, rot: 180, body: /^bodyMAT$/ },
  { id: 'c130', name: 'Datsun 240Z (130)', file: 'assets/cars/car130.glb', len: 4.2, rot: 180, body: /^body[12]?$/ },
  { id: 'mercedes', name: 'Mercedes Clase E (W212)', file: 'assets/cars/mercedes_e_class.glb', len: 4.87, rot: 0, body: /^mat_body$/ },
  { id: 'su7', name: 'Xiaomi SU7', file: 'assets/cars/xiaomi_su7.glb', len: 5.0, rot: 180, body: /CarPaint_SU7_Base1$/ },
];
let carToken = 0;

function clearCar() {
  carReady = false;
  carRoot.clear(); carInner.clear(); carRoot.add(carInner); carInner.scale.setScalar(1); carInner.position.set(0, 0, 0);
  stripRig = null; wheelPairs.length = 0; doorPivots.length = 0; bodyMats.length = 0; headSpots.length = 0;
  for (const k of Object.keys(lampMats)) lampMats[k].length = 0;
  origColors.clear();
}

function addHeadSpots(len) {
  for (const sx of [-0.7, 0.7]) {
    const sp = new THREE.SpotLight(0xfff2cc, 0, 60, 0.45, 0.5, 1.5);
    sp.position.set(sx, 0.7, -len / 2 + 0.2);
    sp.target.position.set(sx * 0.9, 0.2, -len / 2 - 20);
    carRoot.add(sp, sp.target);
    headSpots.push(sp);
  }
}

function buildGeneric(gltf, cfg) {
  const model = gltf.scene;
  const junk = [];
  model.traverse((o) => {
    if (!o.isMesh) return;
    const names = [o.name].concat([].concat(o.material).map((m) => m.name || '')).join(' ').toLowerCase();
    if (/pantulan|shadow|reflection|ground|floor/.test(names)) junk.push(o);
  });
  junk.forEach((o) => o.parent && o.parent.remove(o));
  model.updateMatrixWorld(true);
  // orient: long axis along z, then the model-specific yaw
  let box = new THREE.Box3().setFromObject(model);
  let sz = box.getSize(new THREE.Vector3());
  const holder = new THREE.Group(); holder.add(model);
  holder.rotation.y = (sz.x > sz.z ? Math.PI / 2 : 0) + (cfg.rot || 0) * Math.PI / 180;
  holder.updateMatrixWorld(true);
  box = new THREE.Box3().setFromObject(holder); sz = box.getSize(new THREE.Vector3());
  const s = cfg.len / Math.max(sz.x, sz.z), c = box.getCenter(new THREE.Vector3());
  carInner.add(holder);
  carInner.scale.setScalar(s);
  carInner.position.set(-c.x * s, -box.min.y * s, -c.z * s);
  carInner.updateMatrixWorld(true);

  // body paint = the biggest opaque material that is not glass / tyre / interior
  const area = new Map();
  model.traverse((o) => {
    if (!o.isMesh) return;
    const n = o.geometry.index ? o.geometry.index.count : o.geometry.attributes.position.count;
    for (const m of [].concat(o.material)) {
      classifyGeneric(m);
      area.set(m, (area.get(m) || 0) + n);
    }
  });
  if (cfg.body) for (const m of area.keys()) if (cfg.body.test(m.name || '')) bodyMats.push(m);
  let best = null;
  for (const [m, n] of cfg.body ? [] : area) {
    const nm = (m.name || '').toLowerCase();
    if (m.transparent || /glass|window|tire|tyre|rubber|wheel|rim|chrome|interior|black|plate|light|lens|mirror|seat|dark|license/.test(nm)) continue;
    if (/car.?paint|body|paint|primary|coat/.test(nm)) { bodyMats.push(m); best = null; }
    else if (!bodyMats.length && (!best || n > area.get(best))) best = m;
  }
  if (!bodyMats.length && best) bodyMats.push(best);
  // wheels and doors that come as named nodes (WheelFL, DoorFL...): spin / swing them like the original Model 3
  model.updateMatrixWorld(true); carInner.updateMatrixWorld(true);
  const wheelNodes = [], doorNodes = [];
  model.traverse((o) => { if (/^wheel(f|r)[lr]$/i.test(o.name)) wheelNodes.push(o); else if (/^door(f|r)[lr]$/i.test(o.name)) doorNodes.push(o); });
  for (const o of wheelNodes) {
    const b = new THREE.Box3().setFromObject(o), centre = b.getCenter(new THREE.Vector3());
    const pivot = new THREE.Group(); carRoot.add(pivot); pivot.position.copy(centre); pivot.updateMatrixWorld(true); pivot.attach(o); wheelPairs.push(pivot);
  }
  for (const o of doorNodes) {
    const b = new THREE.Box3().setFromObject(o), c2 = b.getCenter(new THREE.Vector3());
    const pivot = new THREE.Group(); carRoot.add(pivot); pivot.position.set(c2.x, c2.y, b.min.z); pivot.updateMatrixWorld(true); pivot.attach(o);
    doorPivots.push({ pivot, sign: c2.x >= 0 ? 1 : -1 });
  }
  addHeadSpots(cfg.len);
  carReady = true;
}
function classifyGeneric(m) {
  const n = (m.name || '').toLowerCase();
  if (/indicator|blinker|orange/.test(n)) lampMats.sigL.push(m);
  else if (/brea?k(e)?[_ ]?light|brake/.test(n) && !/caliper|disc|disk|rotor|pad|brakes|hub|tire|tyre/.test(n)) lampMats.brake.push(m);
  else if (/rear.?light|(^|[^a-z])tail|back.?light|red.?light|back_red|red_plastic/.test(n)) lampMats.tail.push(m);
  else if (/front.{0,6}light|head.?light|led.?phare|fog/.test(n)) lampMats.head.push(m);
}

function loadCar(id) {
  const cfg = CARS.find((c) => c.id === id) || CARS[0];
  const token = ++carToken;
  clearCar();
  loader.load(cfg.file, (gltf) => {
    if (token !== carToken) return;
    if (cfg.id === 'tesla') buildTesla(gltf); else buildGeneric(gltf, cfg);
    $('loading').classList.add('done');
  }, (e) => { if (e.total) $('loadmsg').textContent = 'Cargando el coche… ' + Math.min(100, Math.round(100 * e.loaded / e.total)) + '%'; },
  (err) => { $('loadmsg').textContent = 'No se pudo cargar el coche: ' + (err.message || err); });
}


// paint chips
const PAINTS = [['#eeeeee', null], ['#f5f5f5', 0xf2f2f2], ['#111111', 0x0a0a0a], ['#c0121c', 0xc0121c], ['#1f4fc0', 0x1f4fc0], ['#2a9d4b', 0x2a9d4b], ['#a8abb0', 0xa8abb0], ['#6b6f75', 0x6b6f75], ['#e8730c', 0xe8730c]];
const origColors = new Map();
PAINTS.forEach(([css, hex], idx) => {
  const chip = document.createElement('i'); chip.style.background = idx === 0 ? 'conic-gradient(#999,#fff,#999)' : css;
  chip.onclick = () => {
    for (const m of bodyMats) {
      if (!origColors.has(m)) origColors.set(m, m.color.clone());
      if (idx === 0) m.color.copy(origColors.get(m)); else m.color.setHex(hex);
    }
  };
  $('colors').appendChild(chip);
});

// ------------------------------------------------------------------ traffic: four cars of the pack in assets/traffic (a different four each visit)
// The pack's convention: metres, front = +Z, origin on the ground between the axles, wheel_FL/FR/RL/RR pivot at their centres.
const TRAFFIC_LEN = { compact: 3.26, coupe: 4.24, hatchback: 3.96, minivan: 4.61, offroad: 3.94, pickup: 5.18, sedan: 4.36, sport: 3.91, suv: 4.61, wagon: 4.38 };
const TRAFFIC_R = { compact: 0.243, coupe: 0.3, hatchback: 0.3, minivan: 0.318, offroad: 0.358, pickup: 0.376, sedan: 0.304, sport: 0.317, suv: 0.376, wagon: 0.304 };
const trafficCars = [];
let trafficMode = -1, trafficOn = true;
Object.keys(TRAFFIC_LEN).sort(() => Math.random() - 0.5).slice(0, 2).forEach((name, idx) => {
  loader.load('assets/traffic/' + name + '.glb', (g) => {
    const tg = new THREE.Group(); tg.add(g.scene); tg.visible = false; scene.add(tg);
    const wheels = ['wheel_FL', 'wheel_FR', 'wheel_RL', 'wheel_RR'].map((w) => g.scene.getObjectByName(w)).filter(Boolean);
    trafficCars.push({ tg, wheels, r: TRAFFIC_R[name], idx, lat: 0, d: 0, vt: 0, onc: false, wait: 4 + idx * 14, active: false });
    trafficMode = -1;
  }, undefined, () => { /* a missing model just means one car less */ });
});
/**
 * One car now and then. City: an oncoming car in the left lane (all at the same speed, so they never cross).
 * Motorway: one car in our direction in the fast lane, one oncoming on the other carriageway.
 * After a car passes it waits 15-45 s before coming back.
 */
function updateTraffic(dt, v, d0, th0) {
  const mode = hwMode ? 1 : 0;
  if (trafficMode !== mode) {
    trafficMode = mode;
    trafficCars.forEach((t) => { t.active = false; t.wait = 2 + t.idx * 12 + Math.random() * 6; });
  }
  for (const t of trafficCars) {
    if (!t.active) {
      t.tg.visible = false;
      if (!trafficOn) continue;
      t.wait -= dt;
      if (t.wait > 0) continue;
      // spawn: city = oncoming in the left lane; motorway = car 0 behind us in the fast lane, car 1 oncoming on the other carriageway
      if (hwMode && t.idx === 0) { t.lat = -LANE_W; t.d = -12; t.vt = 28; t.onc = false; }
      else if (hwMode) { t.lat = -14.75; t.d = 90; t.vt = -28; t.onc = true; }
      else { t.lat = -LANE_W; t.d = 90; t.vt = -14; t.onc = true; }
      t.active = true;
    }
    t.d += (t.vt - v) * dt;
    const gone = t.onc ? t.d < -25 : (t.d > 95 || t.d < -30);
    if (gone || !trafficOn) { t.active = false; t.wait = 15 + Math.random() * 30; t.tg.visible = false; continue; }
    t.tg.visible = true;
    const p = pose(t.d, t.lat, d0, th0);
    t.tg.position.set(p.x, 0, p.z);
    t.tg.rotation.y = p.yaw + (t.onc ? 0 : Math.PI);
    const spin = Math.abs(t.vt) / t.r * dt;
    for (const w of t.wheels) w.rotation.x += spin;
  }
}

// main-car selector (Ajustes)
{
  const sel = $('carSel');
  for (const c of CARS) { const o = document.createElement('option'); o.value = c.id; o.textContent = c.name; sel.appendChild(o); }
  let saved = 'modelo3'; try { saved = localStorage.getItem('d3d.car') || 'modelo3'; } catch (e) { /* private mode */ }
  if (!CARS.some((c) => c.id === saved)) saved = 'modelo3';
  sel.value = saved;
  sel.onchange = () => { try { localStorage.setItem('d3d.car', sel.value); } catch (e) { /* ignore */ } loadCar(sel.value); };
  window.addEventListener('DOMContentLoaded', () => {});
  setTimeout(() => loadCar(new URLSearchParams(location.search).get('car') || sel.value), 0);
}

// ------------------------------------------------------------------ state
const st = {
  speedKmh: 0, odo: 0, gps: true, gpsOk: false, gpsKmh: 0, gpsStamp: 0,
  headlights: false, hazard: false, follow: true, door: 0, doorTarget: 0,
  prevSpeed: 0, decel: 0, brakeHold: 0, park: 0, wheelAngle: 0,
  cam: { yaw: 0, pitch: 11, dist: 6.8 }, lastTouch: 0,
};

// Lamps work like in the Android app: when lit, the material's base colour AND its emission take the lamp colour (the emissive texture,
// when the model has one, keeps only the real lamp strips bright); when off, the original colour comes back.
const RED = [1, 0.05, 0.03], WHITE = [1, 0.96, 0.8], AMBER = [1, 0.55, 0.02];
function lamp(mats, level, rgb, k) {
  for (const m of mats) {
    if (m.userData.c0 === undefined) { m.userData.c0 = m.color.clone(); if (!m.emissiveMap && m.map) { m.emissiveMap = m.map; m.needsUpdate = true; } }   // the texture decides which parts glow
    if (level > 0) { m.color.setRGB(rgb[0], rgb[1], rgb[2]); m.emissive.setRGB(rgb[0], rgb[1], rgb[2]); m.emissiveIntensity = level * k; }
    else { m.color.copy(m.userData.c0); m.emissiveIntensity = 0; }
  }
}
/** Rear cluster of one side: amber while its turn signal is lit, red tail light otherwise. */
function sideLamp(mats, sig, tailOn) { if (sig) lamp(mats, 1, AMBER, 6); else lamp(mats, tailOn ? 1 : 0, RED, 4); }

// ------------------------------------------------------------------ input: camera, buttons, GPS
const pointers = new Map();
canvas.addEventListener('pointerdown', (e) => { canvas.setPointerCapture(e.pointerId); pointers.set(e.pointerId, { x: e.clientX, y: e.clientY }); st.lastTouch = performance.now(); });
canvas.addEventListener('pointerup', (e) => pointers.delete(e.pointerId));
canvas.addEventListener('pointercancel', (e) => pointers.delete(e.pointerId));
canvas.addEventListener('pointermove', (e) => {
  const p = pointers.get(e.pointerId); if (!p) return;
  const dx = e.clientX - p.x, dy = e.clientY - p.y;
  if (pointers.size === 1) {
    st.cam.yaw -= dx * 0.35 * Math.PI / 180; st.cam.pitch = clamp(st.cam.pitch + dy * 0.25, -5, 80);
  } else if (pointers.size === 2) {
    const [a, b] = [...pointers.values()];
    const before = Math.hypot(a.x - b.x, a.y - b.y);
    p.x = e.clientX; p.y = e.clientY;
    const after = Math.hypot(a.x - b.x, a.y - b.y);
    if (before > 0) st.cam.dist = clamp(st.cam.dist * before / after, 3.2, 20);
  }
  p.x = e.clientX; p.y = e.clientY; st.lastTouch = performance.now();
});
canvas.addEventListener('wheel', (e) => { st.cam.dist = clamp(st.cam.dist * (1 + e.deltaY * 0.001), 3.2, 20); st.lastTouch = performance.now(); }, { passive: true });

const toggle = (id, on) => $(id).classList.toggle('on', on);
let nightMode = 'auto';      // auto (sun position) | day | night
try { nightMode = localStorage.getItem('d3d.night') || 'auto'; } catch (e) { /* private mode */ }
function isNightAt(lat, lon, date) {
  const rad = Math.PI / 180, start = Date.UTC(date.getUTCFullYear(), 0, 0), n = Math.floor((date - start) / 864e5);
  const decl = 23.44 * rad * Math.sin(2 * Math.PI * (284 + n) / 365);
  const solarH = date.getUTCHours() + date.getUTCMinutes() / 60 + lon / 15;
  const ha = (solarH - 12) * 15 * rad;
  const sinAlt = Math.sin(lat * rad) * Math.sin(decl) + Math.cos(lat * rad) * Math.cos(decl) * Math.cos(ha);
  return sinAlt < Math.sin(-3 * rad);      // sun more than 3 degrees below the horizon
}
let inTunnel = false, wx = 'clear', wxMode = 'auto', autoLightsWanted = false;
try { wxMode = localStorage.getItem('d3d.wx') || 'auto'; } catch (e) { /* private mode */ }
/** Headlights on at night, inside tunnels and in rain / snow / fog; they only change when one of those changes (the button still works by hand). */
function syncAutoLights(first) {
  const want = night || inTunnel || wx !== 'clear';
  if (want === autoLightsWanted && !first) return;
  if (first && !want) { autoLightsWanted = want; return; }
  autoLightsWanted = want; st.headlights = want; toggle('bLights', want);
}
function autoNightTick(first) {
  if (nightMode === 'auto') {
    let lat = 43.26, lon = -2.93;
    if (st.pos) { lat = st.pos.lat; lon = st.pos.lon; } else { const lp = store.get('lastpos', null); if (lp) { lat = lp.lat; lon = lp.lon; } }
    const nowNight = isNightAt(lat, lon, new Date());
    if (nowNight !== night) {
      night = nowNight; applyTheme();
      syncAutoLights(first);   // headlights follow day <-> night
    } else if (first) syncAutoLights(true);
  } else if ((nightMode === 'night') !== night) { night = nightMode === 'night'; applyTheme(); }
}
$('bNight').onclick = () => { nightMode = night ? 'day' : 'night'; try { localStorage.setItem('d3d.night', nightMode); } catch (e) { /* ignore */ } $('nightSel').value = nightMode; autoNightTick(false); };
$('bLights').onclick = () => { st.headlights = !st.headlights; toggle('bLights', st.headlights); };
$('bHazard').onclick = () => { st.hazard = !st.hazard; toggle('bHazard', st.hazard); };
$('bTraffic').onclick = () => { trafficOn = !trafficOn; toggle('bTraffic', trafficOn); $('bTraffic').classList.toggle('off', !trafficOn); try { localStorage.setItem('d3d.traffic', trafficOn ? '1' : '0'); } catch (e) { /* private mode */ } };
try { if (localStorage.getItem('d3d.traffic') === '0') trafficOn = false; } catch (e) { /* private mode */ }
toggle('bTraffic', trafficOn); $('bTraffic').classList.toggle('off', !trafficOn);
$('bCam').onclick = () => { st.follow = !st.follow; toggle('bCam', st.follow); };
$('bGps').onclick = () => { st.gps = !st.gps; updateGpsUi(); if (st.gps) startGps(); };
$('bDoors').onclick = () => { st.doorTarget = st.doorTarget ? 0 : 1; toggle('bDoors', !!st.doorTarget); };
const ROAD_LABEL = { auto: 'Vía: automática (ruta y GPS)', city: 'Vía: carretera', hw: 'Vía: autopista 2+2' };
$('bRoad').onclick = () => { roadMode = roadMode === 'auto' ? 'city' : roadMode === 'city' ? 'hw' : 'auto'; $('bRoad').title = ROAD_LABEL[roadMode]; toggle('bRoad', roadMode !== 'auto'); say(ROAD_LABEL[roadMode]); };
$('bInfo').onclick = () => { $('credits').hidden = false; };
$('closeCredits').onclick = () => { $('credits').hidden = true; };
$('manualSlider').oninput = (e) => { st.speedKmh = +e.target.value; $('manualOut').textContent = e.target.value; };
toggle('bCam', true); toggle('bGps', true);

function updateGpsUi() {
  toggle('bGps', st.gps);
  const useGps = st.gps && st.gpsOk;
  $('manual').classList.toggle('hidden', useGps);
  $('gpsstate').textContent = !st.gps ? 'GPS: apagado (velocidad manual)' : st.gpsOk ? 'GPS: activo' : 'GPS: esperando…';
}

let watchId = null;
function startGps() {
  if (!('geolocation' in navigator) || watchId !== null) return;
  watchId = navigator.geolocation.watchPosition((pos) => {
    st.gpsOk = true; st.gpsStamp = performance.now();
    onFix(pos.coords);
    st.gpsKmh = pos.coords.speed != null ? pos.coords.speed * 3.6 : 0;
    updateGpsUi();
  }, (err) => {
    st.gpsOk = false; $('gpsstate').textContent = 'GPS: ' + (err.code === 1 ? 'permiso denegado (velocidad manual)' : 'sin señal (velocidad manual)'); updateGpsUi();
    if (err.code === 1) { st.gps = false; toggle('bGps', false); $('manual').classList.remove('hidden'); }
  }, { enableHighAccuracy: true, maximumAge: 1000, timeout: 15000 });
}
updateGpsUi();
startGps();

// keep the screen on while the page is open
let wake = null;
async function keepAwake() { try { if ('wakeLock' in navigator && !wake) { wake = await navigator.wakeLock.request('screen'); wake.addEventListener('release', () => { wake = null; }); } } catch (e) { /* not available */ } }
document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'visible') keepAwake(); });
window.addEventListener('pointerdown', keepAwake, { once: true });
keepAwake();

setTimeout(() => { $('nightSel').value = nightMode; $('nightSel').onchange = () => { nightMode = $('nightSel').value; try { localStorage.setItem('d3d.night', nightMode); } catch (e) { /* ignore */ } autoNightTick(false); }; autoNightTick(true); setInterval(() => autoNightTick(false), 20000); }, 0);
const FOG_RANGE = { clear: [35, 150], rain: [20, 90], snow: [15, 70], fog: [6, 40] };
const PN = 600, ppos = new Float32Array(PN * 3);
for (let i = 0; i < PN; i++) { ppos[3 * i] = (Math.random() - 0.5) * 18; ppos[3 * i + 1] = Math.random() * 7; ppos[3 * i + 2] = -28 + Math.random() * 33; }
const pgeo = new THREE.BufferGeometry(); pgeo.setAttribute('position', new THREE.BufferAttribute(ppos, 3));
const pmat = new THREE.PointsMaterial({ color: 0xcfe3ff, size: 0.07, transparent: true, opacity: 0.75, depthWrite: false });
const precip = new THREE.Points(pgeo, pmat); precip.frustumCulled = false; precip.visible = false; scene.add(precip);
function updatePrecip(dt) {
  if (!precip.visible) return;
  const fall = wx === 'snow' ? 1.6 : 12;
  for (let i = 0; i < PN; i++) {
    ppos[3 * i + 1] -= fall * dt;
    if (wx === 'snow') ppos[3 * i] += Math.sin(performance.now() / 700 + i) * 0.4 * dt;
    if (ppos[3 * i + 1] < 0) { ppos[3 * i + 1] = 6 + Math.random(); ppos[3 * i] = (Math.random() - 0.5) * 18; ppos[3 * i + 2] = -28 + Math.random() * 33; }
  }
  pgeo.attributes.position.needsUpdate = true;
}
function setWeather(w) {
  if (w === wx) return;
  wx = w; precip.visible = w === 'rain' || w === 'snow';
  pmat.size = w === 'snow' ? 0.14 : 0.11; pmat.color.setHex(w === 'snow' ? 0xffffff : 0xcfe3ff);
  applyTheme(); syncAutoLights();
}
/** Real weather at our position (Open-Meteo, no key). */
async function refreshWeather() {
  if (wxMode !== 'auto') return;
  let lat = 43.26, lon = -2.93;
  if (st.pos) { lat = st.pos.lat; lon = st.pos.lon; } else { const lp = store.get('lastpos', null); if (lp) { lat = lp.lat; lon = lp.lon; } }
  try {
    const j = await (await fetch('https://api.open-meteo.com/v1/forecast?latitude=' + lat + '&longitude=' + lon + '&current=weather_code,visibility')).json();
    const code = j.current.weather_code, vis = j.current.visibility ?? 20000;
    const w = (code >= 71 && code <= 77) || code === 85 || code === 86 ? 'snow'
      : (code >= 51 && code <= 67) || (code >= 80 && code <= 82) || code >= 95 ? 'rain'
      : code === 45 || code === 48 || vis < 1000 ? 'fog' : 'clear';
    if (wxMode === 'auto') setWeather(w);
  } catch (e) { /* offline: keep what we have */ }
}
setTimeout(() => {
  const sel = $('wxSel'); sel.value = wxMode;
  sel.onchange = () => { wxMode = sel.value; try { localStorage.setItem('d3d.wx', wxMode); } catch (e) { /* ignore */ } if (wxMode === 'auto') refreshWeather(); else setWeather(wxMode); };
  const urlWx = new URLSearchParams(location.search).get('wx');
  if (urlWx) { wxMode = urlWx; sel.value = urlWx; }
  if (wxMode === 'auto') { refreshWeather(); setInterval(refreshWeather, 600000); } else setWeather(wxMode);
}, 3000);
let applyTheme = function () {
  const t = night ? NIGHT : DAY;
  scene.background = new THREE.Color(t.bg);
  scene.fog = new THREE.Fog(t.fog, ...(FOG_RANGE[wx] || FOG_RANGE.clear));
  sun.intensity = t.sun; hemi.intensity = t.hemi; scene.environmentIntensity = t.env;
  asphalt.color.setHex(t.asphalt); grassMat.color.setHex(t.grass);
  TINTS.forEach((c, i) => roadMats[i + 1].color.setHex(c).multiplyScalar(night ? 0.45 : 1));
  $('bNight').textContent = night ? '☀️' : '🌙';
};
applyTheme();

// ------------------------------------------------------------------ resize
function resize() {
  const w = window.innerWidth, h = window.innerHeight;
  renderer.setSize(w, h, false);
  camera.aspect = w / h; camera.fov = w / h < 1 ? 58 : 45; camera.updateProjectionMatrix();
}
window.addEventListener('resize', resize); resize();

// ------------------------------------------------------------------ main loop
const ring = $('ringfg'), kmhEl = $('kmh');
let last = performance.now(), frames = 0, fpsT = last;
function frame(now) {
  requestAnimationFrame(frame);
  const dt = Math.min(0.1, (now - last) / 1000); last = now;
  frames++; if (now - fpsT >= 1000) { $('fps').textContent = frames + ' fps'; frames = 0; fpsT = now; }

  // speed: GPS (smoothed) or the manual slider
  if (st.gps && st.gpsOk) {
    const fresh = now - st.gpsStamp < 4000;
    st.speedKmh += ((fresh ? st.gpsKmh : 0) - st.speedKmh) * Math.min(1, dt * 3);
  }
  const v = st.speedKmh / 3.6;
  st.odo += v * dt;
  routeFrame();
  hudFrame();
  kmhEl.textContent = Math.round(st.speedKmh);
  ring.style.strokeDasharray = (209.44 * clamp(st.speedKmh / 240, 0, 1)) + ' 314.16';

  // braking is read from the speed itself; parked look at standstill
  const dv = (st.prevSpeed - st.speedKmh) / Math.max(dt, 0.001);
  st.decel += (dv - st.decel) * Math.min(1, dt * 4); st.prevSpeed = st.speedKmh;
  st.brakeHold = st.speedKmh > 3 && st.decel > 6 ? 0.5 : Math.max(0, st.brakeHold - dt);
  const braking = st.brakeHold > 0;
  st.park += ((st.speedKmh < 1.5 ? 1 : 0) - st.park) * Math.min(1, dt * 1.4);
  const moving = st.speedKmh > 3 ? 1 : 0;
  const pitch = (-Math.min(1, Math.max(0, st.decel) / 18) * 2.6 + Math.min(1, Math.max(0, -st.decel) / 14) * 1.4) * moving;

  hwMode = computeHw();
  updateRoad(st.odo);
  const th0 = thetaFn(st.odo);
  updateProps(st.odo, th0);
  updateTraffic(dt, v, st.odo, th0);
  updatePrecip(dt);
  updateBuildings(st.odo, th0);
  updateSigns(st.odo, th0);

  if (carReady) {
    const kap = kappa(st.odo);
    const steer = clamp(-kap * 450, -9, 9), lean = clamp(kap * v * 25, -4, 4);
    const rad = Math.PI / 180;
    carRoot.rotation.set(pitch * rad, (steer - 8 * st.park) * rad, lean * rad, 'YXZ');
    carRoot.position.set(0.55 * st.park, 0, 0);
    st.wheelAngle += v / 0.34 * dt;
    for (const w of wheelPairs) w.rotation.x = -st.wheelAngle;
    st.door += (((st.doorTarget || st.autoDoor) ? 1 : 0) - st.door) * Math.min(1, dt * 4);
    for (const d of doorPivots) d.pivot.rotation.y = d.sign * 35 * Math.PI / 180 * st.door;

    // automatic aids: turn signal before a turn / exit of the route, hazard lights after hard braking or inside a jam, doors open on arrival
    let autoL = false, autoR = false, autoHaz = false;
    const sNow = s0 + (st.odo - odo0);
    if (course) {
      const nx = course.next(sNow);
      if (nx) {
        const lim = hwMode ? 400 : 150;
        if (nx.dist < lim) { const dir = { L: -1, SL: -1, U: -1, R: 1, SR: 1, X: 1 }[nx.step.kind] || 0; if (dir) { st.sigHold = dir; st.sigUntil = nx.step.offset + 25; } }
      }
      if (st.sigHold && sNow < st.sigUntil) { autoL = st.sigHold < 0; autoR = st.sigHold > 0; } else st.sigHold = 0;
      if (course.total - sNow < 25 && st.speedKmh < 3) st.autoDoor = true;
    } else st.sigHold = 0;
    if (!course || st.speedKmh > 8) st.autoDoor = false;
    if (st.speedKmh > 30 && st.decel > 25) st.hazUntil = now + 4000;
    autoHaz = now < (st.hazUntil || 0) || (!!course && rt.traffic && rt.traffic.level === 3 && rt.traffic.dist <= 30 && st.speedKmh < 25);
    const blink = Math.floor(now / 625) % 2 === 0;
    if (!stripRig) lamp(lampMats.head, st.headlights ? 1 : 0, WHITE, 6);
    if (!stripRig) lamp(lampMats.tail, (st.headlights || braking) ? 1 : 0, RED, 4);
    if (!stripRig) lamp(lampMats.brake, braking ? 1.5 : (st.headlights ? 0.5 : 0), RED, 4);
    const tailOn = st.headlights || braking, sigLon = (st.hazard || autoHaz || autoL) && blink, sigRon = (st.hazard || autoHaz || autoR) && blink;
    if (stripRig) {
      for (const [side, on] of [['left', sigLon], ['right', sigRon]]) {
        const m = stripRig.rear[side]; if (!m) continue;
        const active = side === 'left' ? (st.hazard || autoHaz || autoL) : (st.hazard || autoHaz || autoR);
        if (active) { m.visible = on; m.material.color.setRGB(1, 0.6, 0.05); }
        else { m.visible = tailOn; m.material.color.setRGB(1, braking ? 0.15 : 0.1, 0.06); }
      }
      for (const m of stripRig.front) m.visible = st.headlights;
    } else { sideLamp(lampMats.tailL, sigLon, tailOn); sideLamp(lampMats.tailR, sigRon, tailOn); }
    if (!stripRig) lamp(lampMats.sigL, sigLon ? 1 : 0, AMBER, 4);
    if (!stripRig) lamp(lampMats.sigR, sigRon ? 1 : 0, AMBER, 4);
    for (const sp of headSpots) sp.intensity = st.headlights ? (night ? 250 : 60) : 0;
  }

  // camera: orbit around the car, easing back behind it after a moment when "follow" is on
  const c = st.cam;
  if (st.follow && now - st.lastTouch > 1500) {
    const k = Math.min(1, dt * 2.5);
    const dYaw = ((((0 - c.yaw) % (2 * Math.PI)) + 3 * Math.PI) % (2 * Math.PI)) - Math.PI;
    c.yaw += dYaw * k; c.pitch += (11 - c.pitch) * k; c.dist += ((6.8 + st.speedKmh / 200 * 2.8) - c.dist) * k;
  }
  const pr = c.pitch * Math.PI / 180;
  camera.position.set(Math.sin(c.yaw) * Math.cos(pr) * c.dist + carRoot.position.x, 0.7 + Math.sin(pr) * c.dist, Math.cos(c.yaw) * Math.cos(pr) * c.dist);
  camera.lookAt(carRoot.position.x, 0.7, 0);

  renderer.render(scene, camera);
}
requestAnimationFrame(frame);


// ------------------------------------------------------------------ phase 2: places, routes, mini map
const store = {
  get(k, d) { try { return JSON.parse(localStorage.getItem('d3d.' + k)) ?? d; } catch (e) { return d; } },
  set(k, v) { try { localStorage.setItem('d3d.' + k, JSON.stringify(v)); } catch (e) { /* private mode */ } },
};
let ttKey = store.get('tomtom', '');
const favs = store.get('favs', []), recents = store.get('recents', []);
let home = store.get('home', null);
const rt = { dest: null, demo: false, lastS: 0, off: 0, offCount: 0, lastReroute: 0, rerouting: false, drawnS: -1e9, mapLat: 0, mapLon: 0, mapBrg: 0, mapValid: false,
  limit: null, alts: [], traffic: null, infoAt: 0, infoLat: 0, infoLon: 0, infoBusy: false };
const q = new URLSearchParams(location.search);
if (q.get('lat')) st.pos = { lat: +q.get('lat'), lon: +q.get('lon'), heading: 0 };   // for testing without GPS: ?lat=..&lon=..
const samePlace = (a, b) => Math.abs(a.lat - b.lat) < 0.0002 && Math.abs(a.lon - b.lon) < 0.0002;
const isFav = (p) => favs.some((f) => samePlace(f, p));
const say = (txt) => { const el = $('routeStatus'); el.textContent = txt; el.hidden = !txt; if (txt) { clearTimeout(say.t); say.t = setTimeout(() => { el.hidden = true; }, 5000); } };
onNote((m) => { $('keyNote').textContent = m; });

function onFix(co) {
  st.pos = { lat: co.latitude, lon: co.longitude, heading: co.heading };
  store.set('lastpos', { lat: co.latitude, lon: co.longitude });
  if (!course || rt.demo) return;
  const p = course.project(co.latitude, co.longitude, rt.lastS);
  rt.lastS = p[0]; rt.off = p[1]; s0 = p[0]; odo0 = st.odo;
  rt.offCount = rt.off > 50 ? rt.offCount + 1 : 0;
  if (rt.offCount >= 4 && (co.speed || 0) > 2 && performance.now() - rt.lastReroute > 20000 && rt.dest && !rt.rerouting) reroute();
}

async function reroute() {
  rt.rerouting = true; rt.lastReroute = performance.now(); rt.offCount = 0; say('↻ Recalculando ruta…');
  try { const rs = await routesTo(st.pos, rt.dest, ttKey); setRoute(rs[0], rt.dest, false, rs.slice(1)); say('Ruta recalculada'); } catch (e) { say('No se pudo recalcular la ruta'); }
  rt.rerouting = false;
}

function setRoute(r, dest, demo, alts = []) {
  course = r; rt.dest = dest; rt.demo = !!demo; rt.lastS = 0; rt.off = 0; rt.offCount = 0; s0 = 0; odo0 = st.odo; rt.drawnS = -1e9;
  rt.alts = alts; voice.reset();
  $('bCancel').hidden = false; $('maneuver').hidden = false; $('bShare').hidden = !dest;
  // route from a free service: colour it with TomTom's traffic flow if the key allows it
  if (!demo && ttKey && r.travelSec === 0) {
    r.viaFlow = true;
    flowTraffic(r, 0, ttKey).then((tr) => { if (course === r) { r.traffic = tr; rt.drawnS = -1e9; } });
  }
}
/** Stop the selected route and go back to free driving (procedural road, no manoeuvres). */
function clearRoute() {
  course = null; rt.dest = null; rt.demo = false; rt.rerouting = false; rt.drawnS = -1e9; rt.alts = []; rt.traffic = null; voice.reset(); $('trafficCard').hidden = true;
  $('bCancel').hidden = true; $('maneuver').hidden = true; $('tripbar').hidden = true; $('bShare').hidden = true;
  drawRoute(0);
  if (st.pos) { rt.mapLat = st.pos.lat; rt.mapLon = st.pos.lon; rt.mapBrg = st.pos.heading || 0; rt.mapValid = true; } else rt.mapValid = false;
  say('Ruta cancelada');
}
$('bCancel').onclick = clearRoute;
$('mStop').onclick = clearRoute;

function routeFrame() {
  if (!course) return;
  const sN = s0 + (st.odo - odo0);
  const p = course.pose(sN);
  rt.mapLat = p[0]; rt.mapLon = p[1]; rt.mapBrg = p[2]; rt.mapValid = true;
  const nx = course.next(sN), hw = course.isMotorway(sN + 40);
  const man = $('maneuver');
  man.classList.toggle('hw', hw);
  if (nx) {
    $('mArrow').innerHTML = icon(nx.step.kind);
    $('mText').textContent = stepLabel(nx.step, hw);
    const d = Math.round(nx.dist / 10) * 10;
    $('mDist').textContent = d >= 1000 ? (d / 1000).toFixed(1) + ' km' : d + ' m';
  } else { $('mArrow').innerHTML = icon('ARRIVE'); $('mText').textContent = 'Destino'; $('mDist').textContent = ''; }
  // traffic ahead (card + voice) and the alternative route, if there is a faster one
  const nt = course.nextTraffic(sN);
  const traffic = nt ? { level: nt[2], dist: Math.max(0, Math.round((nt[0] - sN) / 10) * 10), len: Math.round((nt[1] - Math.max(nt[0], sN)) / 10) * 10, delayMin: Math.round(nt[3] / 60) } : null;
  rt.traffic = traffic;
  const alt = bestAlt();
  showTraffic(traffic, alt);
  voice.update({ route: course, sN, highway: hw, now: performance.now(), traffic, alt: alt ? { saveMin: alt.save } : null });
  if (Math.abs(sN - rt.drawnS) > 5) { rt.drawnS = sN; drawRoute(sN); }
  // trip bar: km left, arrival time, time left
  const remM = Math.max(0, course.total - sN);
  const remSec = course.travelSec > 0 ? course.travelSec * remM / course.total : remM / (hw ? 25 : 12);
  const mins = Math.round(remSec / 60), eta = new Date(Date.now() + remSec * 1000);
  $('tbKm').textContent = remM >= 10000 ? Math.round(remM / 1000) + ' km' : (remM / 1000).toFixed(1) + ' km';
  $('tbEta').textContent = String(eta.getHours()).padStart(2, '0') + ':' + String(eta.getMinutes()).padStart(2, '0');
  $('tbMin').textContent = mins >= 60 ? Math.floor(mins / 60) + ' h ' + String(mins % 60).padStart(2, '0') + ' min' : mins + ' min';
  $('tripbar').hidden = false;
}

// --- mini map (MapLibre GL JS, OpenFreeMap tiles)
const STYLE_DAY = 'https://tiles.openfreemap.org/styles/positron', STYLE_NIGHT = 'https://tiles.openfreemap.org/styles/dark';
let map = null, mapStyleReady = false;
function setupMap() {
  if (!window.maplibregl) return;
  map = new maplibregl.Map({ container: 'minimap', style: night ? STYLE_NIGHT : STYLE_DAY, center: [-2.935, 43.263], zoom: 15, pitch: 48,
    interactive: false, attributionControl: false, fadeDuration: 0 });
  map.on('style.load', () => {
    map.addSource('route-done', { type: 'geojson', data: empty() });
    map.addLayer({ id: 'route-done-line', type: 'line', source: 'route-done', layout: { 'line-cap': 'round', 'line-join': 'round' }, paint: { 'line-color': '#9aa0a6', 'line-width': 8 } });
    map.addSource('route', { type: 'geojson', data: empty() });
    map.addLayer({ id: 'route-casing', type: 'line', source: 'route', layout: { 'line-cap': 'round', 'line-join': 'round' }, paint: { 'line-color': '#0b3d91', 'line-width': 14 } });
    map.addLayer({ id: 'route-line', type: 'line', source: 'route', layout: { 'line-cap': 'round', 'line-join': 'round' }, paint: { 'line-color': ['match', ['to-number', ['get', 'lvl']], 1, '#f5d000', 2, '#ff8a00', 3, '#e53935', '#29b6f6'], 'line-width': 9 } });
    map.addSource('dest', { type: 'geojson', data: empty() });
    map.addLayer({ id: 'dest-dot', type: 'circle', source: 'dest', paint: { 'circle-radius': 8, 'circle-color': '#e53935', 'circle-stroke-color': '#fff', 'circle-stroke-width': 2.5 } });
    mapStyleReady = true; rt.drawnS = -1e9;
  });
  setInterval(() => {
    if (!rt.mapValid) return;
    const pad = $('minimap').clientHeight * 0.42;
    map.jumpTo({ center: [rt.mapLon, rt.mapLat], bearing: rt.mapBrg, zoom: 15.6, pitch: 48, padding: { top: pad, bottom: 0, left: 0, right: 0 } });
  }, 450);
}
const empty = () => ({ type: 'FeatureCollection', features: [] });
function drawRoute(sN) {
  if (!map || !mapStyleReady) return;
  const done = map.getSource('route-done'), todo = map.getSource('route'), dest = map.getSource('dest');
  if (!done || !todo || !dest) return;
  if (!course) { done.setData(empty()); todo.setData(empty()); dest.setData(empty()); return; }
  const line = (pts) => ({ type: 'Feature', geometry: { type: 'LineString', coordinates: pts } });
  const s = Math.min(Math.max(sN, 0), course.total);
  done.setData(s > 2 ? line(course.line(0, s)) : empty());
  // ahead of the car: one feature per stretch, coloured by traffic level
  const feats = [];
  let cur = s;
  const add = (x, y, lvl) => { if (y - x >= 1) feats.push({ type: 'Feature', properties: { lvl }, geometry: { type: 'LineString', coordinates: course.line(x, y) } }); };
  for (const r of [...course.traffic].sort((p, q) => p[0] - q[0])) {
    const x = Math.max(r[0], cur), y = Math.min(r[1], course.total);
    if (y <= x) continue;
    if (x > cur) add(cur, x, 0);
    add(x, y, r[2]); cur = y;
  }
  if (cur < course.total) add(cur, course.total, 0);
  todo.setData({ type: 'FeatureCollection', features: feats });
  const end = course.pose(course.total);
  dest.setData({ type: 'Feature', geometry: { type: 'Point', coordinates: [end[1], end[0]] } });
}
const baseApplyTheme = applyTheme;
applyTheme = function () { baseApplyTheme(); if (map) { mapStyleReady = false; map.setStyle(night ? STYLE_NIGHT : STYLE_DAY, { diff: false }); } };
setupMap();

// --- places search: recents and favourites that match what you type, plus live suggestions; drag down = home
function origin() {
  if (st.pos) return st.pos;
  const last = store.get('lastpos', null);
  return last ? { lat: last.lat, lon: last.lon } : null;
}
async function startRoute(p) {
  const o = origin();
  if (!o) { say('Sin posición GPS: activa la ubicación (o usa «Ruta de prueba» en ⚙️)'); return; }
  say('Calculando ruta…');
  addRecent(p);
  try { const rs = await routesTo(o, p, ttKey), r = rs[0]; setRoute(r, p, false, rs.slice(1)); say('Ruta a ' + p.name.split(',')[0] + ' · ' + (r.total / 1000).toFixed(1) + ' km'); }
  catch (e) { say('No se pudo calcular la ruta (¿hay internet?)'); }
}
function addRecent(p) {
  const i = recents.findIndex((r) => samePlace(r, p)); if (i >= 0) recents.splice(i, 1);
  recents.unshift({ name: p.name, lat: p.lat, lon: p.lon }); recents.length = Math.min(recents.length, 12); store.set('recents', recents);
}
function toggleFav(p) {
  const i = favs.findIndex((f) => samePlace(f, p));
  if (i >= 0) { if (home && samePlace(home, p)) { home = null; store.set('home', null); } favs.splice(i, 1); } else favs.unshift({ name: p.name, lat: p.lat, lon: p.lon });
  store.set('favs', favs);
}
function setHome(p) { if (!isFav(p)) toggleFav(p); home = { name: p.name, lat: p.lat, lon: p.lon }; store.set('home', home); }

let live = [], liveTimer = null, liveFor = '';
function renderResults() {
  const text = $('q').value.trim().toLowerCase();
  const local = [...favs.map((p) => [p, '⭐']), ...recents.filter((r) => !isFav(r)).map((p) => [p, '🕘'])]
    .filter(([p]) => !text || p.name.toLowerCase().includes(text));
  const extra = live.filter((l) => !local.some(([p]) => p.name === l.name)).map((p) => [p, '📍']);
  const box = $('results'); box.innerHTML = '';
  for (const [p, icon] of [...local, ...extra]) {
    const row = document.createElement('div'); row.className = 'it';
    row.innerHTML = '<span>' + icon + '</span><span class="n"></span>';
    row.querySelector('.n').textContent = p.name;
    const shb = document.createElement('button'); shb.innerHTML = icon('share'); shb.style.color = '#7ce0ff'; shb.onclick = (e) => { e.stopPropagation(); closeSearch(); shareDialog(p); }; row.appendChild(shb);
    if (isFav(p)) { const eb = document.createElement('button'); eb.textContent = '✏️'; eb.onclick = (e) => { e.stopPropagation(); editFav(p); }; row.appendChild(eb); }
    if (isFav(p)) { const hb = document.createElement('button'); hb.textContent = home && samePlace(home, p) ? '🏠' : '🏡'; hb.onclick = (e) => { e.stopPropagation(); setHome(p); renderResults(); }; row.appendChild(hb); }
    const sb = document.createElement('button'); sb.textContent = isFav(p) ? '★' : '☆'; sb.onclick = (e) => { e.stopPropagation(); toggleFav(p); renderResults(); }; row.appendChild(sb);
    row.onclick = () => { closeSearch(); Link.publish(p); startRoute(p); };
    box.appendChild(row);
  }
  if (!box.children.length) { const e = document.createElement('div'); e.className = 'empty'; e.textContent = text.length < 3 ? 'Escribe al menos 3 letras' : (liveFor === text ? 'Sin resultados' : 'Buscando…'); box.appendChild(e); }
}
$('q').addEventListener('input', () => {
  clearTimeout(liveTimer); live = []; renderResults();
  const text = $('q').value.trim();
  if (text.length < 3) return;
  const sp = parseShared(text);                     // a pasted Google Maps link or "lat, lon" is a result by itself
  if (sp.lat !== undefined) { live = [{ name: sp.name || sp.lat.toFixed(5) + ', ' + sp.lon.toFixed(5), lat: sp.lat, lon: sp.lon }]; liveFor = text.toLowerCase(); renderResults(); return; }
  liveTimer = setTimeout(async () => { const res = await search(text, origin(), ttKey); if ($('q').value.trim() === text) { live = res; liveFor = text.toLowerCase(); renderResults(); } }, 350);
});
// --- places that arrive from / go to other apps (Google Maps share, Tesla app, Waze)
for (const id of ['mStop', 'closeSearch', 'xSettings', 'pdX']) $(id).innerHTML = icon('x');
$('bShare').innerHTML = icon('share');
function placeDialog({ title, place, buttons, editable = true }) {
  const box = $('placeDialog'), nameEl = $('pdName');
  $('pdTitle').textContent = title; nameEl.value = place.name; nameEl.parentElement.hidden = !editable;
  $('pdCoords').textContent = place.lat.toFixed(5) + ', ' + place.lon.toFixed(5);
  const holder = $('pdBtns'); holder.innerHTML = '';
  for (const [label, fn] of buttons) {
    const b = document.createElement('button'); b.textContent = label;
    b.onclick = () => { const np = { name: nameEl.value.trim() || place.name, lat: place.lat, lon: place.lon }; box.hidden = true; fn(np); };
    holder.appendChild(b);
  }
  box.hidden = false;
}
$('pdX').onclick = () => { $('placeDialog').hidden = true; };
function sharedDialog(p) {
  placeDialog({ title: 'Ubicación compartida', place: p, buttons: [
    ['Ir ahora', (np) => { addRecent(np); Link.publish(np); startRoute(np); }],
    ['⭐ Guardar en favoritos', (np) => { if (!isFav(np)) { favs.unshift({ name: np.name, lat: np.lat, lon: np.lon }); store.set('favs', favs); } say('Guardado en favoritos'); }],
  ] });
}
function shareDialog(p) {
  placeDialog({ title: 'Enviar destino', place: p, editable: false, buttons: [
    ['Compartir… (app de Tesla, WhatsApp…)', async (np) => { const m = await sharePlace(np); if (m) say(m); }],
    ['Abrir en Google Maps', (np) => window.open(googleMapsNav(np), '_blank')],
    ['Abrir en Waze', (np) => window.open(wazeLink(np), '_blank')],
  ] });
}
function editFav(p) {
  const n = prompt('Nombre del favorito', p.name);
  if (!n || !n.trim()) return;
  for (const list of [favs, recents]) for (const f of list) if (samePlace(f, p)) f.name = n.trim();
  if (home && samePlace(home, p)) { home.name = n.trim(); store.set('home', home); }
  store.set('favs', favs); store.set('recents', recents); renderResults();
}
async function receiveShared(raw) {
  const info = parseShared(raw); let p = null;
  if (info.lat !== undefined) p = { name: info.name || info.lat.toFixed(5) + ', ' + info.lon.toFixed(5), lat: info.lat, lon: info.lon };
  else if (info.name) { say('Buscando «' + info.name + '»…'); try { p = (await search(info.name, origin(), ttKey))[0] || null; } catch (e) { p = null; } }
  if (p) sharedDialog(p); else say('No pude leer la ubicación compartida');
}
$('bShare').onclick = () => { $('settings').hidden = true; if (rt.dest) shareDialog(rt.dest); };
{ // opened from the Android share sheet: ?title=..&text=..&url=..
  const qs = new URLSearchParams(location.search), shared = [qs.get('title'), qs.get('text'), qs.get('url')].filter(Boolean).join('\n');
  if (shared) { try { history.replaceState(null, '', location.pathname); } catch (e) { /* ignore */ } setTimeout(() => receiveShared(shared), 1500); }
}
function openSearch() { $('searchbar').hidden = false; $('hint').className = ''; $('hint').textContent = 'Mantén pulsada la lupa para ir a casa' + (home ? '' : ' (marca 🏠 en un favorito)'); $('q').value = ''; live = []; renderResults(); setTimeout(() => $('q').focus(), 50); }
function closeSearch() { $('searchbar').hidden = true; $('q').blur(); }
// tap the magnifier = search; hold it = go straight home
{
  const lupa = $('bSearch'); let lpTimer = null, lpFired = false;
  lupa.addEventListener('pointerdown', () => {
    lpFired = false; clearTimeout(lpTimer);
    lpTimer = setTimeout(() => {
      lpFired = true; if (navigator.vibrate) navigator.vibrate(30);
      if (home) startRoute(home);
      else { openSearch(); $('hint').className = 'warn'; $('hint').textContent = 'No hay casa guardada: marca 🏠 en un favorito'; }
    }, 650);
  });
  for (const ev of ['pointerup', 'pointerleave', 'pointercancel']) lupa.addEventListener(ev, () => clearTimeout(lpTimer));
  lupa.addEventListener('contextmenu', (e) => e.preventDefault());
  lupa.onclick = () => { if (lpFired) { lpFired = false; return; } openSearch(); };
}
$('closeSearch').onclick = closeSearch;

// --- settings (TomTom key stays in this browser only) and the test route
$('ttKey').value = ttKey;
$('linkCode').value = Link.code;
$('linkGen').onclick = () => { $('linkCode').value = Link.randomCode(); };
Link.start((p) => { say('Destino recibido del otro dispositivo: ' + p.name.split(',')[0]); startRoute(p); });
$('bSettings').onclick = () => { $('settings').hidden = false; };
$('xSettings').onclick = () => { $('settings').hidden = true; };
$('closeSettings').onclick = () => { if ($('linkCode').value.trim() !== Link.code) Link.setCode($('linkCode').value); ttKey = $('ttKey').value.trim(); store.set('tomtom', ttKey); $('settings').hidden = true; };
window.d3d = { testRoute: () => { const o = origin() || { lat: 43.263, lon: -2.935 }; const r = testRoute(o.lat, o.lon); setRoute(r, null, true, [testAlternative(r)]); rt.limit = 90; say('Ruta de prueba'); }, clearRoute, voice: null,
  testSigns: (k = 1) => { fakeItems = true; itemsAt = { odo: st.odo, list: [{ kind: 'p', dist: 60 * k, max: 0, key: 'a' }, { kind: 's', dist: 90 * k, max: 0, key: 'b' }, { kind: 'c', dist: 130 * k, max: 0, key: 'c' }, { kind: 't', dist: 170 * k, max: 0, key: 'd' }, { kind: 'r', dist: 300 * k, max: 80, key: 'e' }] }; } };
$('bTest').onclick = () => { $('settings').hidden = true; window.d3d.testRoute(); };
$('bClear').onclick = () => { $('settings').hidden = true; clearRoute(); };


// ------------------------------------------------------------------ phase 3: direction / limit signs, traffic card, voice, speed limit
const voice = new VoiceGuide();
window.d3d.voice = voice;
$('bVoice').onclick = () => { voice.enabled = !voice.enabled; toggle('bVoice', voice.enabled); if (!voice.enabled && voice.ok) speechSynthesis.cancel(); };
window.addEventListener('pointerdown', () => voice.unlock(), { once: true });

function makeSign(w, h, poleH, plateY) {
  const g = new THREE.Group();
  const pole = new THREE.Mesh(new THREE.CylinderGeometry(0.05, 0.05, poleH, 8), new THREE.MeshStandardMaterial({ color: 0x808890, metalness: 0.5, roughness: 0.5 }));
  pole.position.y = poleH / 2;
  const plate = new THREE.Mesh(new THREE.PlaneGeometry(w, h), new THREE.MeshBasicMaterial({ transparent: true, side: THREE.DoubleSide }));
  plate.position.set(0, plateY, 0.07);
  g.add(pole, plate); g.visible = false; scene.add(g);
  return { g, plate };
}
const dirSign = makeSign(1.9, 1.0, 3.0, 3.05), limSign = makeSign(0.9, 0.9, 2.6, 2.6);
function setMap(sign, tex) { const m = sign.plate.material; if (m.map !== tex) { m.map = tex; m.needsUpdate = true; } }

/** Direction plate ~70 m before each manoeuvre (blue on motorways, white elsewhere) and a limit sign every 450 m. */
function updateSigns(d0, th0) {
  const sN = s0 + (d0 - odo0);
  const nx = course ? course.next(sN) : null;
  const dd = nx ? nx.dist - 70 : 999;
  dirSign.g.visible = !!nx && dd < 230 && dd > -20;
  if (dirSign.g.visible) {
    const hw = course.isMotorway(sN + 40), p = pose(dd, 3.55, d0, th0);
    dirSign.g.position.set(p.x, 0, p.z); dirSign.g.rotation.y = p.yaw;
    setMap(dirSign, directionTexture(hw, nx.step.kind, stepLabel(nx.step, hw), nx.step.exitNo));
  }
  const ld = Math.ceil((d0 - 10) / 450) * 450 - d0;
  limSign.g.visible = rt.limit != null && ld < 190;
  if (limSign.g.visible) {
    const p = pose(ld, 3.15, d0, th0);
    limSign.g.position.set(p.x, 0, p.z); limSign.g.rotation.y = p.yaw;
    setMap(limSign, limitTexture(rt.limit));
  }
  updateRoadItems(d0, th0);
}

// --- stop / give way / traffic lights / crossings and speed cameras from senales.json and radares.json
RoadData.load().then((ok) => { if (!ok) console.info('senales.json / radares.json no encontrados: sin señales ni radares'); });
const ITEM_SLOTS = [];
{
  const zc = document.createElement('canvas'); zc.width = 256; zc.height = 128;
  const zg = zc.getContext('2d'); zg.fillStyle = '#f2f2f2';
  for (let i = 0; i < 8; i++) zg.fillRect(i * 32 + 4, 0, 18, 128);
  const zebraTex = new THREE.CanvasTexture(zc); zebraTex.colorSpace = THREE.SRGBColorSpace;
  const zebraMat = new THREE.MeshBasicMaterial({ map: zebraTex, transparent: true, depthWrite: false });
  const metal = new THREE.MeshStandardMaterial({ color: 0x40464f, metalness: 0.6, roughness: 0.5 });
  for (let i = 0; i < 10; i++) {
    const sign = makeSign(0.9, 0.9, 2.4, 2.4);
    const zebra = new THREE.Mesh(new THREE.PlaneGeometry(7.4, 3), zebraMat); zebra.rotation.x = -Math.PI / 2; zebra.visible = false; scene.add(zebra);
    const lights = new THREE.Group(); lights.visible = false; scene.add(lights);
    const pole = new THREE.Mesh(new THREE.CylinderGeometry(0.07, 0.08, 4.2, 8), metal); pole.position.y = 2.1;
    const head = new THREE.Mesh(new THREE.BoxGeometry(0.42, 1.15, 0.3), new THREE.MeshStandardMaterial({ color: 0x15181c })); head.position.y = 4.4;
    lights.add(pole, head);
    const lamps = [0xff2a20, 0xffb000, 0x30e060].map((c, k) => {
      const m = new THREE.Mesh(new THREE.SphereGeometry(0.13, 12, 8), new THREE.MeshBasicMaterial({ color: c })); m.position.set(0, 4.75 - k * 0.35, 0.18); lights.add(m); return m;
    });
    ITEM_SLOTS.push({ sign, zebra, lights, lamps });
  }
}
const SIGN_TEX = { s: stopTexture, c: yieldTexture, p: crossingTexture };
let itemsAt = { odo: 0, list: [] }, fakeItems = false;
const spokenRadars = new Set();
function pickPosition() {
  if (rt.mapValid) return { lat: rt.mapLat, lon: rt.mapLon, brg: rt.mapBrg };
  if (st.pos && st.pos.heading != null && !Number.isNaN(st.pos.heading)) return { lat: st.pos.lat, lon: st.pos.lon, brg: st.pos.heading };
  return null;
}
setInterval(() => {
  if (fakeItems) return;
  const p = pickPosition();
  itemsAt = { odo: st.odo, list: p ? RoadData.ahead(p.lat, p.lon, p.brg) : [] };
}, 250);
function updateRoadItems(d0, th0) {
  const list = itemsAt.list, moved = d0 - itemsAt.odo;
  let radar = null;
  ITEM_SLOTS.forEach((sl, i) => {
    const it = list[i], d = it ? it.dist - moved : -1;
    sl.sign.g.visible = sl.zebra.visible = sl.lights.visible = false;
    if (!it || !(d > -2 && d < 450)) return;
    if (it.kind === 'p') {
      const z = pose(d, -1.85, d0, th0); sl.zebra.visible = true; sl.zebra.position.set(z.x, 0.012, z.z); sl.zebra.rotation.z = -z.yaw;
    }
    if (it.kind === 't') {
      const p = pose(d, 3.0, d0, th0); sl.lights.visible = true; sl.lights.position.set(p.x, 0, p.z); sl.lights.rotation.y = p.yaw;
      const ph = ((performance.now() / 1000) + it.key.length * 3.7) % 18, c = ph < 9 ? 2 : ph < 11 ? 1 : 0;
      sl.lamps.forEach((m, k) => { m.visible = k === c; });
    } else if (it.kind === 'r') {
      const p = pose(d, 3.15, d0, th0); sl.sign.g.visible = true; sl.sign.g.position.set(p.x, 0, p.z); sl.sign.g.rotation.y = p.yaw;
      sl.sign.plate.scale.set(1.15, 1.45, 1); setMap(sl.sign, radarTexture(it.max));
      if (d > 0 && (!radar || d < radar.d)) radar = { d, max: it.max, key: it.key };
    } else {
      const p = pose(d, 3.0, d0, th0); sl.sign.g.visible = true; sl.sign.g.position.set(p.x, 0, p.z); sl.sign.g.rotation.y = p.yaw;
      sl.sign.plate.scale.set(1, 1, 1); setMap(sl.sign, SIGN_TEX[it.kind]());
    }
  });
  // speed-camera sign in the HUD (bottom centre, blinking) + one spoken warning per camera
  const rd = $('radar');
  if (radar && radar.d < 350) {
    rd.hidden = false; $('radMax').textContent = radar.max > 0 ? radar.max : '!'; $('radMax').classList.toggle('big3', radar.max >= 100);
    $('radDist').textContent = '📷 ' + Math.max(0, Math.round(radar.d / 10) * 10) + ' m';
    if (radar.d < 300 && !spokenRadars.has(radar.key)) {
      if (spokenRadars.size > 200) spokenRadars.clear();
      spokenRadars.add(radar.key);
      voice.say('Radar a ' + Math.max(50, Math.round(radar.d / 50) * 50) + ' metros' + (radar.max > 0 ? ', límite ' + radar.max : ''));
    }
  } else rd.hidden = true;
  rt.radarMax = radar && radar.max > 0 ? radar.max : null;
  if (radar) $('tripbar').hidden = true;
}

// --- speed limit in the HUD, and the speedometer ring turns red when over it
let shownLimit = undefined;
function hudFrame() {
  if (shownLimit !== rt.limit) { shownLimit = rt.limit; $('limit').hidden = rt.limit == null; $('limit').textContent = rt.limit ?? ''; }
  const over = (rt.limit != null && st.speedKmh > rt.limit + 3) || (rt.radarMax != null && st.speedKmh > rt.radarMax + 3);
  ring.style.stroke = over ? '#ff5252' : '';
  $('halo').classList.toggle('on', rt.radarMax != null && st.speedKmh > rt.radarMax + 3);
}

// --- traffic card (colour = level) with the alternative-route button
const TC = ['', '#f5d000', '#ff8a00', '#e53935'];
function bestAlt() {
  if (!course || !(course.travelSec > 0)) return null;
  let best = null;
  for (const a of rt.alts) { const save = Math.round((course.travelSec - a.travelSec) / 60); if (save >= 1 && (!best || save > best.save)) best = { a, save }; }
  return best;
}
let shownCard = '';
function showTraffic(t, alt) {
  const card = $('trafficCard');
  if (!t) { if (!card.hidden) card.hidden = true; shownCard = ''; return; }
  const key = t.level + '|' + t.dist + '|' + t.len + '|' + t.delayMin + '|' + (alt ? alt.save : 0);
  if (key === shownCard) return;
  shownCard = key; card.hidden = false; card.style.setProperty('--tc', TC[t.level]);
  const title = t.level === 1 ? 'Tráfico' : t.level === 2 ? 'Tráfico denso' : 'Caravana o corte';
  $('tcTitle').textContent = title + (t.dist > 0 ? ' en ' + (t.dist >= 1000 ? (t.dist / 1000).toFixed(1) + ' km' : t.dist + ' m') : ' ahora');
  $('tcDetail').textContent = (t.len > 0 ? t.len + ' m' : '') + (t.delayMin > 0 ? ' · +' + t.delayMin + ' min' : '');
  const b = $('tcAlt'); b.hidden = !alt; if (alt) b.textContent = 'Alternativa: -' + alt.save + ' min';
}
$('tcAlt').onclick = () => { const alt = bestAlt(); if (alt) { setRoute(alt.a, rt.dest, rt.demo, []); say('Ruta alternativa · ' + (alt.a.total / 1000).toFixed(1) + ' km'); } };

// --- speed limit of the road under the car (OpenStreetMap), every ~60 m
setInterval(async () => {
  if (rt.demo || rt.infoBusy) return;
  const lat = rt.mapValid ? rt.mapLat : st.pos && st.pos.lat, lon = rt.mapValid ? rt.mapLon : st.pos && st.pos.lon;
  if (lat == null) return;
  const moved = Math.abs(lat - rt.infoLat) * 110540 + Math.abs(lon - rt.infoLon) * 111320 * Math.cos(lat * Math.PI / 180), now = performance.now();
  if (moved < 60 && now - rt.infoAt < 45000) return;
  rt.infoBusy = true; rt.infoLat = lat; rt.infoLon = lon; rt.infoAt = now;
  try { const info = await roadInfo(lat, lon); if (info) { rt.limit = info.limit; rt.limitApprox = info.approx; rt.motorway = info.motorway; if (!!info.tunnel !== inTunnel) { inTunnel = !!info.tunnel; syncAutoLights(); } } } catch (e) { /* offline */ } finally { rt.infoBusy = false; }
}, 3000);

// --- every 4 minutes: fresh traffic (same road -> new colours; a faster different road -> offered as alternative)
setInterval(async () => {
  if (!course || rt.demo || !rt.dest || !ttKey || rt.rerouting) return;
  const cur = course, sNow = s0 + (st.odo - odo0);
  try {
    if (cur.viaFlow) { const tr = await flowTraffic(cur, sNow, ttKey); if (course === cur) { cur.traffic = tr; rt.drawnS = -1e9; } return; }
    const from = origin(); if (!from) return;
    const rs = await routesTo(from, rt.dest, ttKey);
    if (course !== cur) return;
    const same = rs.find((r) => r.similarTo(cur));
    if (same) { cur.traffic = same.traffic; cur.travelSec = same.travelSec; rt.alts = rs.filter((r) => r !== same); rt.drawnS = -1e9; }
    else rt.alts = rs.filter((r) => r.travelSec < cur.travelSec * (1 - sNow / cur.total) - 60);
  } catch (e) { /* keep what we have */ }
}, 240000);

// ------------------------------------------------------------------ phase 4: road type, buildings
function computeHw() {
  if (roadMode === 'hw') return true;
  if (roadMode === 'city') return false;
  if (course) return course.isMotorway(s0 + (st.odo - odo0) + 40);
  return !!rt.motorway;
}

// Buildings (CC BY models, compressed for the web), recycled along the road like the lamps and trees.
const BUILD = { cats: [], loaded: false };
function fitBuilding(root, height) {
  root.updateMatrixWorld(true);
  const box = new THREE.Box3().setFromObject(root), size = box.getSize(new THREE.Vector3()), c = box.getCenter(new THREE.Vector3());
  const sc = height / Math.max(size.y, 1e-3), wrap = new THREE.Group(), inner = new THREE.Group();
  inner.add(root); inner.scale.setScalar(sc); inner.position.set(-c.x * sc, -box.min.y * sc, -c.z * sc); wrap.add(inner);
  wrap.userData.radius = Math.max(size.x, size.z) / 2 * sc;
  return wrap;
}
async function loadBuildings() {
  const get = (f) => new Promise((res, rej) => loader.load('assets/' + f, (g) => res(g.scene), undefined, rej));
  const [e3, e5, brick, tower] = await Promise.all(['edificio_3.glb', 'edificio_5.glb', 'edificio_brick.glb', 'edificio_corficolombiana_colombian_skyscraper.glb'].map(get));
  const protos = [fitBuilding(e3, 16), fitBuilding(e5, 26), fitBuilding(brick, 20)], tow = fitBuilding(tower, 120);
  const mkCat = (spacing, ahead, back, edge, jitter, pick) => {
    const cat = { spacing, ahead, back, edge, jitter, n: Math.ceil((ahead + back) / spacing) + 2, slots: [] };
    for (const side of [-1, 1]) for (let s = 0; s < cat.n; s++) {
      const proto = pick(side, s), obj = proto.clone(true); obj.visible = false; scene.add(obj);
      cat.slots.push({ obj, side, s, radius: proto.userData.radius });
    }
    return cat;
  };
  // the heavy brick building is used only once; the others alternate
  BUILD.cats.push(mkCat(48, 110, 22, 12, 10, (side, s) => (side > 0 && s === 2 ? protos[2] : protos[(s + (side > 0 ? 1 : 0)) % 2])));
  BUILD.cats.push(mkCat(520, 330, 40, 60, 25, () => tow));
  BUILD.loaded = true;
}
loadBuildings().catch((e) => console.warn('buildings', e));

function updateBuildings(d0, th0) {
  if (!BUILD.loaded) return;
  for (const c of BUILD.cats) {
    for (const sl of c.slots) {
      const nStart = Math.floor((d0 - c.back) / c.spacing);
      const k = nStart + ((((sl.s - nStart) % c.n) + c.n) % c.n);
      const d = k * c.spacing + (sl.side < 0 ? c.spacing / 2 : 0) - d0;
      const vis = !hwMode && d >= -c.back && d <= c.ahead;
      sl.obj.visible = vis;
      if (!vis) continue;
      const off = c.edge + c.jitter * hash(k, sl.side + 3) + sl.radius;
      const p = pose(d, sl.side > 0 ? 1.85 + off : -5.55 - off, d0, th0);
      sl.obj.position.set(p.x, 0, p.z);
      sl.obj.rotation.y = p.yaw + sl.side * Math.PI / 2;
    }
  }
}

setInterval(() => { $('diag').textContent = $('gpsstate').textContent + ' · ' + $('fps').textContent; }, 1000);
if (q.get('test')) window.d3d.testRoute();

if ('serviceWorker' in navigator) navigator.serviceWorker.register('sw.js').catch(() => {});
