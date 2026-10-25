// Routes, place search and the link between a route and the 3D road (same logic as the Android app).

const RAD = Math.PI / 180;
const STEP_M = 4;

/** kind: L R SL SR S U (turns), X exit, RB roundabout, ARRIVE. */
export const ARROWS = { L: '←', R: '→', SL: '↖', SR: '↗', X: '↗', U: '↩', ARRIVE: '🏁', S: '↑', RB: '↻' };

export class Route {
  /** pts: [[lat, lon], ...]; steps: [{offset, kind, text, ref, exitNo}]; motorway: [[startS, endS], ...] */
  constructor(pts, steps, name = '', motorway = [], traffic = [], travelSec = 0) {
    this.traffic = traffic;         // [[startS, endS, level 1 some / 2 dense / 3 jam or closure, delaySec]]
    this.travelSec = travelSec; this.viaFlow = false;
    this.n = pts.length;
    this.lat = pts.map((p) => p[0]); this.lon = pts.map((p) => p[1]);
    this.steps = steps; this.name = name; this.motorway = motorway;
    this.mx = 111320 * Math.cos(this.lat[0] * RAD); this.my = 110540;
    this.x = this.lon.map((v) => (v - this.lon[0]) * this.mx);
    this.y = this.lat.map((v) => (v - this.lat[0]) * this.my);
    this.cum = new Float64Array(this.n);
    for (let i = 1; i < this.n; i++) this.cum[i] = this.cum[i - 1] + Math.hypot(this.x[i] - this.x[i - 1], this.y[i] - this.y[i - 1]);
    this.total = this.cum[this.n - 1];
    // bearing every STEP_M metres, unwrapped and smoothed (triangular window of +-24 m)
    const m = Math.max(2, Math.floor(this.total / STEP_M) + 2);
    const raw = new Float64Array(m);
    let seg = 0;
    for (let k = 0; k < m; k++) {
      const s = k * STEP_M;
      while (seg < this.n - 2 && this.cum[seg + 1] < s) seg++;
      raw[k] = Math.atan2(this.x[seg + 1] - this.x[seg], this.y[seg + 1] - this.y[seg]);
    }
    for (let k = 1; k < m; k++) { let d = raw[k] - raw[k - 1]; while (d > Math.PI) d -= 2 * Math.PI; while (d < -Math.PI) d += 2 * Math.PI; raw[k] = raw[k - 1] + d; }
    this.hdg = new Float64Array(m);
    const w = 6;
    for (let k = 0; k < m; k++) {
      let sum = 0, wt = 0;
      for (let j = -w; j <= w; j++) { const q = Math.min(m - 1, Math.max(0, k + j)); const c = w + 1 - Math.abs(j); sum += raw[q] * c; wt += c; }
      this.hdg[k] = sum / wt;
    }
  }

  theta(s) {
    const f = Math.min(this.hdg.length - 1, Math.max(0, s / STEP_M));
    const i = Math.min(this.hdg.length - 2, Math.floor(f));
    return this.hdg[i] + (this.hdg[i + 1] - this.hdg[i]) * (f - i);
  }

  /** [lat, lon, bearingDeg] at distance s along the route. */
  pose(s) {
    const ss = Math.min(this.total, Math.max(0, s));
    let i = 0;
    while (i < this.n - 2 && this.cum[i + 1] < ss) i++;
    const seg = this.cum[i + 1] - this.cum[i];
    const t = seg > 0 ? (ss - this.cum[i]) / seg : 0;
    return [this.lat[i] + (this.lat[i + 1] - this.lat[i]) * t, this.lon[i] + (this.lon[i + 1] - this.lon[i]) * t, this.theta(ss) / RAD];
  }

  /** Nearest point of the route to (lat, lon), searched around `hint`: [s, metresOffRoute]. */
  project(la, lo, hint) {
    const px = (lo - this.lon[0]) * this.mx, py = (la - this.lat[0]) * this.my;
    let best = Infinity, bestS = hint;
    for (let pass = 0; pass < 2; pass++) {
      const from = pass === 0 ? hint - 80 : 0, to = pass === 0 ? hint + 500 : this.total;
      for (let i = 0; i < this.n - 1; i++) {
        if (this.cum[i + 1] < from || this.cum[i] > to) continue;
        const dx = this.x[i + 1] - this.x[i], dy = this.y[i + 1] - this.y[i], l2 = dx * dx + dy * dy;
        const t = l2 > 0 ? Math.min(1, Math.max(0, ((px - this.x[i]) * dx + (py - this.y[i]) * dy) / l2)) : 0;
        const d = Math.hypot(px - (this.x[i] + dx * t), py - (this.y[i] + dy * t));
        if (d < best) { best = d; bestS = this.cum[i] + t * Math.sqrt(l2); }
      }
      if (best < 120) break;
    }
    return [bestS, best];
  }

  /** Next manoeuvre ahead of s: {step, dist} or null. */
  next(s) {
    for (const st of this.steps) if (st.offset > s + 12) return { step: st, dist: st.offset - s };
    return null;
  }

  trafficLevelAt(s) { let l = 0; for (const r of this.traffic) if (s >= r[0] && s <= r[1] && r[2] > l) l = r[2]; return l; }

  /** First traffic section still ahead (or under us) within 6 km: [start, end, level, delay] or null. */
  nextTraffic(s) {
    let best = null;
    for (const r of this.traffic) if (r[1] > s && r[0] < s + 6000 && (!best || r[0] < best[0])) best = r;
    return best;
  }

  /** Roughly the same road as the other route? (five sample points within 60 m) */
  similarTo(o) {
    for (let i = 1; i <= 5; i++) { const p = this.pose(this.total * i / 6); if (o.project(p[0], p[1], 0)[1] > 60) return false; }
    return true;
  }

  isMotorway(s) { return this.motorway.some((r) => s >= r[0] && s <= r[1]); }

  /** Points [lon, lat] between distances a and b. */
  line(a, b) {
    const out = [];
    const pa = this.pose(a); out.push([pa[1], pa[0]]);
    for (let i = 0; i < this.n; i++) if (this.cum[i] > a && this.cum[i] < b) out.push([this.lon[i], this.lat[i]]);
    const pb = this.pose(b); out.push([pb[1], pb[0]]);
    return out;
  }
}

export function stepLabel(st, motorway) {
  const name = st.text || st.ref || '';
  const full = motorway && st.ref && st.text && !st.text.includes(st.ref) ? st.ref + ' · ' + st.text : name;
  return full || (st.kind === 'ARRIVE' ? 'Destino' : 'Continúe');
}

// ------------------------------------------------------------------ network
let noteSink = () => {};
export const onNote = (fn) => { noteSink = fn; };

async function getJson(url, tomtom = false) {
  const res = await fetch(url);
  if (!res.ok) {
    if (tomtom && res.status === 403) {
      noteSink('Tu clave de TomTom no tiene activado ' + (url.includes('/search/') ? 'Search' : url.includes('/routing/') ? 'Routing' : 'Traffic') + ' (error 403). Uso servicios libres.');
    }
    throw new Error('HTTP ' + res.status);
  }
  return res.json();
}

async function photon(q, bias) {
  const b = bias ? `&lat=${bias.lat}&lon=${bias.lon}` : '';
  const j = await getJson(`https://photon.komoot.io/api/?limit=6${b}&q=${encodeURIComponent(q)}`);
  return (j.features || []).map((f) => {
    const p = f.properties || {};
    const name = [p.name, p.street, p.city, p.country].filter(Boolean).filter((v, i, a) => a.indexOf(v) === i).join(', ');
    return name ? { name, lat: f.geometry.coordinates[1], lon: f.geometry.coordinates[0] } : null;
  }).filter(Boolean);
}

export async function search(q, bias, key) {
  try {
    if (key) {
      try {
        const b = bias ? `&lat=${bias.lat}&lon=${bias.lon}` : '';
        const j = await getJson(`https://api.tomtom.com/search/2/search/${encodeURIComponent(q)}.json?key=${key}&limit=6&language=es-ES${b}`, true);
        const out = (j.results || []).map((o) => {
          const where = o.address?.freeformAddress || '';
          const nm = o.poi?.name || where;
          return nm && o.position ? { name: where && where !== nm ? nm + ', ' + where : nm, lat: o.position.lat, lon: o.position.lon } : null;
        }).filter(Boolean);
        if (out.length) return out;
      } catch (e) { /* fall back to the free service */ }
    }
    return await photon(q, bias);
  } catch (e) { return []; }
}

function kindOf(m) {
  const u = (m || '').toUpperCase();
  if (u.includes('ARRIVE')) return 'ARRIVE';
  if (u.includes('ROUNDABOUT') || u === 'RB') return 'RB';
  if (u.includes('EXIT') || u.includes('RAMP')) return 'X';
  if (u.includes('UTURN') || u.includes('U_TURN') || u.includes('U-TURN')) return 'U';
  if ((u.includes('BEAR') || u.includes('KEEP') || u.includes('SLIGHT')) && u.includes('LEFT')) return 'SL';
  if ((u.includes('BEAR') || u.includes('KEEP') || u.includes('SLIGHT')) && u.includes('RIGHT')) return 'SR';
  if (u.includes('LEFT')) return 'L';
  if (u.includes('RIGHT')) return 'R';
  return 'S';
}

function parseTomTom(r, to) {
  const pts = [];
  for (const leg of r.legs) for (const p of leg.points) pts.push([p.latitude, p.longitude]);
  const steps = (r.guidance?.instructions || []).filter((o) => !String(o.maneuver).startsWith('DEPART')).map((o) => ({
    offset: o.routeOffsetInMeters || 0, kind: kindOf(o.maneuver), ref: (o.roadNumbers && o.roadNumbers[0]) || null,
    text: o.signpostText || o.street || o.message || '', exitNo: Math.max(o.roundaboutExitNumber || 0, parseInt(o.exitNumber) || 0),
  }));
  const base = new Route(pts, steps, to.name);
  const at = (i) => base.cum[Math.min(base.n - 1, Math.max(0, i))];
  const mw = [], tr = [];
  for (const s of r.sections || []) {
    if (s.sectionType === 'MOTORWAY') mw.push([at(s.startPointIndex), at(s.endPointIndex)]);
    else if (s.sectionType === 'TRAFFIC') {
      const mag = s.magnitudeOfDelay || 0, cat = s.simpleCategory;
      const lvl = cat === 'ROAD_CLOSURE' || mag >= 3 ? 3 : mag === 2 ? 2 : (mag === 1 || cat === 'JAM' || cat === 'ROAD_WORKS') ? 1 : 0;
      if (lvl) tr.push([at(s.startPointIndex), at(s.endPointIndex), lvl, s.delayInSeconds || 0]);
    }
  }
  return new Route(pts, steps, to.name, mw, tr, r.summary?.travelTimeInSeconds || 0);
}

async function viaTomTom(from, to, key) {
  const j = await getJson(`https://api.tomtom.com/routing/1/calculateRoute/${from.lat},${from.lon}:${to.lat},${to.lon}/json?key=${key}` +
    '&instructionsType=text&language=es-ES&routeType=fastest&traffic=true&maxAlternatives=2&sectionType=motorway&sectionType=traffic', true);
  return j.routes.map((r) => parseTomTom(r, to));
}

async function viaOsrm(from, to) {
  const j = await getJson(`https://router.project-osrm.org/route/v1/driving/${from.lon},${from.lat};${to.lon},${to.lat}?overview=full&geometries=geojson&steps=true`);
  const r = j.routes[0];
  const pts = r.geometry.coordinates.map((c) => [c[1], c[0]]);
  const steps = [], mw = [];
  let acc = 0;
  for (const st of r.legs[0].steps) {
    const man = st.maneuver || {};
    if (man.type !== 'depart') {
      const kind = man.type === 'arrive' ? 'ARRIVE' : (/roundabout|rotary/.test(man.type) ? 'RB' : man.type === 'off ramp' ? 'X' : kindOf(man.modifier));
      steps.push({ offset: acc, kind, text: (st.destinations || st.name || st.ref || '').replace(/, /g, ' · '), ref: st.ref || null, exitNo: man.exit || parseInt(st.exits) || 0 });
    }
    const mwy = /^(A-|AP-|E-)/.test(st.ref || '') || (st.intersections || []).some((i) => (i.classes || []).includes('motorway'));
    if (mwy) { const last = mw[mw.length - 1]; if (last && Math.abs(last[1] - acc) < 1) last[1] = acc + st.distance; else mw.push([acc, acc + st.distance]); }
    acc += st.distance;
  }
  return new Route(pts, steps, to.name, mw);
}

/** All routes found (best first). TomTom gives traffic times and alternatives; the free service gives one route. */
export async function routesTo(from, to, key) {
  if (key) { try { const rs = await viaTomTom(from, to, key); if (rs.length) return rs; } catch (e) { /* free service */ } }
  return [await viaOsrm(from, to)];
}
export async function routeTo(from, to, key) { return (await routesTo(from, to, key))[0]; }

/** Traffic colours from TomTom's Flow Segment Data (needs a key with the Traffic product), for routes without traffic data. */
export async function flowTraffic(r, fromS, key) {
  const out = [];
  let a = Math.max(0, fromS), n = 0, curLvl = 0, curStart = a, curDelay = 0;
  while (a < r.total && n < 40) {
    const b = Math.min(a + 400, r.total), mid = r.pose((a + b) / 2);
    let j;
    try { j = await getJson(`https://api.tomtom.com/traffic/services/4/flowSegmentData/absolute/10/json?point=${mid[0]},${mid[1]}&unit=KMPH&key=${key}`, true); } catch (e) { return out; }
    const f = j.flowSegmentData || {};
    const free = f.freeFlowSpeed || 0, cur = f.currentSpeed ?? free, ratio = free > 0 ? cur / free : 1;
    const lvl = f.roadClosure || ratio < 0.35 ? 3 : ratio < 0.6 ? 2 : ratio < 0.85 ? 1 : 0;
    const delay = lvl && cur > 0 && free > 0 ? (b - a) / (cur / 3.6) - (b - a) / (free / 3.6) : 0;
    if (lvl !== curLvl) { if (curLvl) out.push([curStart, a, curLvl, curDelay]); curLvl = lvl; curStart = a; curDelay = 0; }
    curDelay += delay; a = b; n++;
  }
  if (curLvl) out.push([curStart, a, curLvl, curDelay]);
  return out;
}

/** Speed limit and class of the road at a position (OpenStreetMap through Overpass). */
const RANK = ['motorway', 'motorway_link', 'trunk', 'trunk_link', 'primary', 'primary_link', 'secondary', 'secondary_link', 'tertiary', 'tertiary_link', 'unclassified', 'residential', 'living_street', 'service', 'road'];
function parseMax(raw) {
  const s = String(raw || '').trim().toLowerCase();
  if (!s) return null;
  if (/^\d+$/.test(s)) return +s;
  let m = s.match(/^(\d+)\s*km\/h$/); if (m) return +m[1];
  m = s.match(/^(\d+)\s*mph$/); if (m) return Math.round(+m[1] * 1.609);
  return { 'es:urban': 50, 'es:rural': 90, 'es:trunk': 100, 'es:motorway': 120 }[s] ?? null;
}
const CLASS_LIMIT = { motorway: 120, motorway_link: 80, trunk: 100, trunk_link: 70, primary: 90, primary_link: 50, secondary: 90, secondary_link: 50, tertiary: 70, unclassified: 50, residential: 30, living_street: 20, service: 20, service_other: 20, road: 50 };
/** Valhalla map matching on the public OpenStreetMap server: gives the speed limit of the road under us (CORS open, no key). */
async function valhallaInfo(lat, lon) {
  const c = new AbortController(), timer = setTimeout(() => c.abort(), 9000);
  try {
    const r = await fetch('https://valhalla1.openstreetmap.de/trace_attributes', {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, signal: c.signal,
      body: JSON.stringify({ shape: [{ lat, lon }, { lat: lat + 0.00009, lon: lon + 0.00003 }], costing: 'auto', shape_match: 'map_snap',
        filters: { attributes: ['edge.speed_limit', 'edge.road_class', 'edge.names', 'edge.use', 'edge.tunnel'], action: 'include' } }),
    });
    if (!r.ok) return null;
    const e = ((await r.json()).edges || [])[0];
    if (!e) return null;
    const cls = e.road_class || 'road';
    const known = typeof e.speed_limit === 'number' && e.speed_limit > 0 && e.speed_limit < 200;
    const limit = known ? e.speed_limit : (CLASS_LIMIT[cls] ?? null);
    return { limit, approx: !known && limit != null, cls, ref: null, name: (e.names || [])[0] || null, motorway: cls === 'motorway' || cls === 'trunk', tunnel: !!e.tunnel };
  } catch (err) { return null; } finally { clearTimeout(timer); }
}
export async function roadInfo(lat, lon) {
  const v = await valhallaInfo(lat, lon);
  if (v) return v;
  const q = encodeURIComponent(`[out:json][timeout:6];way(around:25,${lat},${lon})[highway];out tags 6;`);
  // ask several public Overpass servers at once (they are often slow) and take the first good answer
  const eps = ['https://overpass-api.de/api/interpreter', 'https://overpass.kumi.systems/api/interpreter', 'https://overpass.private.coffee/api/interpreter', 'https://maps.mail.ru/osm/tools/overpass/api/interpreter'];
  const one = async (ep) => {
    const c = new AbortController(), timer = setTimeout(() => c.abort(), 9000);
    try { const r = await fetch(ep + '?data=' + q, { signal: c.signal }); if (!r.ok) throw new Error('http'); const j = await r.json(); if (!Array.isArray(j.elements)) throw new Error('bad'); return j; } finally { clearTimeout(timer); }
  };
  let j = null;
  try { j = await Promise.any(eps.map(one)); } catch (e) { return null; }
  const tags = (j.elements || []).map((e) => e.tags || {}).filter((g) => RANK.includes(g.highway));
  tags.sort((x, y) => RANK.indexOf(x.highway) - RANK.indexOf(y.highway));
  const best = tags[0];
  if (!best) return null;
  let limit = parseMax(best.maxspeed), approx = false;
  if (limit == null) {   // no maxspeed in OpenStreetMap: use the Spanish default for the road class
    const urban = /urban/i.test(String(best['source:maxspeed'] || best['maxspeed:type'] || '')) || best['zone:maxspeed'] === 'ES:urban';
    const byClass = { motorway: 120, motorway_link: 80, trunk: 100, trunk_link: 70, primary: 90, primary_link: 50, secondary: 90, secondary_link: 50, tertiary: 70, unclassified: 50, residential: 30, living_street: 20, service: 20, road: 50 };
    limit = urban ? (best.highway === 'residential' ? 30 : 50) : (byClass[best.highway] ?? null);
    approx = limit != null;
  }
  return { limit, approx, cls: best.highway, ref: best.ref || null, name: best.name || null, motorway: /^(motorway|trunk)/.test(best.highway), tunnel: ['yes', 'building_passage', 'covered'].includes(best.tunnel) };
}

/** A made-up route (no network) with bends, turns and a motorway stretch, for trying the scene. */
export function testRoute(lat0, lon0) {
  const pts = [[lat0, lon0]];
  const mx = 111320 * Math.cos(lat0 * RAD), my = 110540;
  let x = 0, y = 0, h = 0;
  const legs = [[150, 0], [70, 90], [220, 0], [110, -75], [180, 0], [160, 40], [160, -40], [400, 0], [220, 35], [300, 0], [120, -85], [500, 0]];
  for (const [len, turn] of legs) {
    const n = Math.max(1, Math.floor(len / 5));
    for (let i = 0; i < n; i++) { h += turn * RAD / n; x += Math.sin(h) * 5; y += Math.cos(h) * 5; pts.push([lat0 + y / my, lon0 + x / mx]); }
  }
  const steps = [
    { offset: 150, kind: 'R', text: 'Calle Mayor', ref: null, exitNo: 0 }, { offset: 550, kind: 'L', text: 'Avenida de la Libertad', ref: null, exitNo: 0 },
    { offset: 1280, kind: 'X', text: 'Bilbao centro', ref: 'A-8', exitNo: 12 }, { offset: 1700, kind: 'RB', text: 'Avenida del Puerto', ref: null, exitNo: 2 },
    { offset: 1900, kind: 'L', text: 'Calle Mayor', ref: null, exitNo: 0 }, { offset: 2400, kind: 'ARRIVE', text: 'Destino de prueba', ref: null, exitNo: 0 },
  ];
  return new Route(pts, steps, 'Ruta de prueba', [[1000, 1450]], [[380, 560, 1, 40], [700, 900, 2, 150], [1560, 1760, 3, 420]], 780);
}

/** A faster, traffic-free copy of a route, to try the alternative-route option. */
export function testAlternative(r) { return new Route(r.lat.map((v, i) => [v, r.lon[i]]), r.steps, 'Alternativa de prueba', r.motorway, [], 540); }
