// Link between the phone HUD and the car screen: a place accepted after a search on one device shows up on the other.
// It uses a public ntfy.sh topic named after a pairing code that both devices share (no account, no server of our own).
// Only the searched place (name + coordinates) is sent; favourites and settings never leave the device.
const BASE = 'https://ntfy.sh/';

function read(k, d) { try { const v = localStorage.getItem(k); return v === null ? d : v; } catch (e) { return d; } }
function write(k, v) { try { localStorage.setItem(k, v); } catch (e) { /* private mode */ } }

export const Link = {
  code: read('d3d.linkcode', ''),
  device: read('d3d.linkdev', '') || (() => { const d = Math.random().toString(36).slice(2, 10); write('d3d.linkdev', d); return d; })(),
  es: null, gen: 0, onPlace: null,
  topic() { return 'd3dclone-' + this.code.toLowerCase().replace(/[^a-z0-9]/g, ''); },
  valid() { return this.code.replace(/[^a-zA-Z0-9]/g, '').length >= 6; },
  randomCode() { const a = 'abcdefghjkmnpqrstuvwxyz23456789'; let s = ''; for (let i = 0; i < 10; i++) s += a[Math.floor(Math.random() * a.length)]; return s; },
  setCode(c) { this.code = (c || '').trim(); write('d3d.linkcode', this.code); this.start(this.onPlace); },
  /** Tell the other device about an accepted search result. */
  publish(p) {
    if (!this.valid()) return;
    const body = JSON.stringify({ d: this.device, n: p.name, la: p.lat, lo: p.lon });
    fetch(BASE + this.topic(), { method: 'POST', body, headers: { 'Content-Type': 'text/plain' } }).catch(() => { /* offline: the link is best effort */ });
  },
  /** Listen (live and for what was sent in the last 3 hours, so the last search appears when the app opens). */
  start(onPlace) {
    this.onPlace = onPlace;
    if (this.es) { this.es.close(); this.es = null; }
    const gen = ++this.gen;
    if (!this.valid()) return;
    const es = new EventSource(BASE + this.topic() + '/sse?since=3h');
    this.es = es;
    es.onmessage = (ev) => {
      if (gen !== this.gen) return;
      try {
        const m = JSON.parse(ev.data);
        if (m.event !== 'message') return;
        const t = +m.time || 0, last = +read('d3d.linklast', '0');
        if (t <= last) return;
        const j = JSON.parse(m.message);
        if (j.d === this.device) { write('d3d.linklast', String(t)); return; }   // our own message
        write('d3d.linklast', String(t));
        if (typeof j.la === 'number' && typeof j.lo === 'number' && this.onPlace) this.onPlace({ name: String(j.n || 'Destino'), lat: j.la, lon: j.lo });
      } catch (e) { /* ignore foreign / malformed messages */ }
    };
  },
};
