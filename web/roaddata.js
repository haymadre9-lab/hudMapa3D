// Stop / give-way / traffic lights / crossings (senales.json) and speed cameras (radares.json), same files the Android app uses.
// They are read from the repository folder app/src/main/assets/data (next to web/), or from web/data/ as a fallback.
const DIRS = ['../app/src/main/assets/data/', 'data/'];

async function fetchJson(name) {
  for (const d of DIRS) {
    try { const r = await fetch(d + name, { cache: 'no-cache' }); if (r.ok) return await r.json(); } catch (e) { /* try next */ }
  }
  return null;
}

export const RoadData = {
  ready: false, sig: [], rad: [],
  async load() {
    const [s, r] = await Promise.all([fetchJson('senales.json'), fetchJson('radares.json')]);
    if (s && s.p) this.sig = s.p;                       // [lat, lon, kind s|c|t|p, extra]
    if (Array.isArray(r)) this.rad = r;                 // {lat, lon, max, t}
    this.ready = this.sig.length > 0 || this.rad.length > 0;
    return this.ready;
  },
  /**
   * The same, but following the ROUTE instead of a straight line from the car: on curvy city roads the straight corridor loses every
   * sign beyond a few tens of metres. A sign counts when it is within a few metres of the route and ahead of us along it.
   */
  aheadRoute(route, sN) {
    if (!this.ready) return [];
    const p = route.pose(sN), lat = p[0], lon = p[1];
    const out = [];
    const near = (la, lo) => Math.abs(la - lat) <= 0.0048 && Math.abs(lo - lon) <= 0.0068;
    const check = (la, lo, maxAlong, maxOff, kind, max, key) => {
      if (!near(la, lo)) return;
      const r = route.nearest(la, lo, sN - 5, sN + maxAlong + 40);
      if (!r) return;
      const along = r[0] - sN;
      if (r[1] <= maxOff && along >= 4 && along <= maxAlong) out.push({ kind, dist: along, cross: 0, max, key });
    };
    for (const e of this.sig) { const k = e[2]; if (k === 's' || k === 'c' || k === 'p' || k === 't') check(e[0], e[1], 450, 13, k, 0, e[0] + ',' + e[1]); }
    for (const e of this.rad) check(e.lat, e.lon, 350, 9, 'r', e.max || 0, e.lat + ',' + e.lon);   // a radar must be ON our route: the other carriageway is further away
    out.sort((a, b) => a.dist - b.dist);
    return out.slice(0, 10);
  },

  /** Things on our road within ~450 m (radars 700 m) ahead, nearest first: {kind s|c|t|p|r, dist, cross, max, key}. */
  ahead(lat, lon, brgDeg) {
    if (!this.ready) return [];
    const b = brgDeg * Math.PI / 180, sb = Math.sin(b), cb = Math.cos(b);
    const mLat = 110540, mLon = 111320 * Math.cos(lat * Math.PI / 180);
    const out = [];
    const test = (la, lo, maxAlong, minCross, maxCross) => {
      if (Math.abs(la - lat) > 0.006 || Math.abs(lo - lon) > 0.009) return null;
      const dy = (la - lat) * mLat, dx = (lo - lon) * mLon;
      const along = dx * sb + dy * cb, cross = dx * cb - dy * sb;
      return along >= 4 && along <= maxAlong && cross >= minCross && cross <= maxCross ? [along, cross] : null;
    };
    for (const e of this.sig) {
      const k = e[2]; if (k !== 's' && k !== 'c' && k !== 'p' && k !== 't') continue;
      const r = test(e[0], e[1], 450, -11, 11); if (r) out.push({ kind: k, dist: r[0], cross: r[1], max: 0, key: e[0] + ',' + e[1] });
    }
    for (const e of this.rad) {
      const r = test(e.lat, e.lon, 350, -6, 12);   // 350 m ahead, on our carriageway (the other direction sits more than 6 m to the left) if (r) out.push({ kind: 'r', dist: r[0], cross: r[1], max: e.max || 0, key: e.lat + ',' + e.lon });
    }
    out.sort((p, q) => p.dist - q.dist);
    return out.slice(0, 10);
  },
};
