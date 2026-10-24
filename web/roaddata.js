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
  /** Things on our road within ~450 m (radars 700 m) ahead, nearest first: {kind s|c|t|p|r, dist, cross, max, key}. */
  ahead(lat, lon, brgDeg) {
    if (!this.ready) return [];
    const b = brgDeg * Math.PI / 180, sb = Math.sin(b), cb = Math.cos(b);
    const mLat = 110540, mLon = 111320 * Math.cos(lat * Math.PI / 180);
    const out = [];
    const test = (la, lo, maxAlong, maxCross) => {
      if (Math.abs(la - lat) > 0.006 || Math.abs(lo - lon) > 0.009) return null;
      const dy = (la - lat) * mLat, dx = (lo - lon) * mLon;
      const along = dx * sb + dy * cb, cross = dx * cb - dy * sb;
      return along >= 4 && along <= maxAlong && Math.abs(cross) <= maxCross ? [along, cross] : null;
    };
    for (const e of this.sig) {
      const k = e[2]; if (k !== 's' && k !== 'c' && k !== 'p' && k !== 't') continue;
      const r = test(e[0], e[1], 450, 11); if (r) out.push({ kind: k, dist: r[0], cross: r[1], max: 0, key: e[0] + ',' + e[1] });
    }
    for (const e of this.rad) {
      const r = test(e.lat, e.lon, 700, 25); if (r) out.push({ kind: 'r', dist: r[0], cross: r[1], max: e.max || 0, key: e.lat + ',' + e.lon });
    }
    out.sort((p, q) => p.dist - q.dist);
    return out.slice(0, 10);
  },
};
