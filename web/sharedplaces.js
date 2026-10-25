// Places that come from (or go to) other apps: a location shared from Google Maps and sharing ours to the Tesla app, Google Maps or Waze.

const NUM = '(-?\\d{1,3}\\.\\d+)';

/** Coordinates written in a Google Maps style URL (or geo: URI, or plain "lat, lon"), if any. */
export function coordsIn(text) {
  let u = text;
  try { u = decodeURIComponent(text); } catch (e) { /* keep as is */ }
  for (const re of ['!3d' + NUM + '!4d' + NUM, '@' + NUM + ',' + NUM, '[?&](?:q|query|ll|center|destination|daddr)=' + NUM + ',\\s*' + NUM, 'geo:' + NUM + ',' + NUM, '^\\s*' + NUM + '\\s*[,; ]\\s*' + NUM + '\\s*$']) {
    const m = new RegExp(re).exec(u);
    if (!m) continue;
    const la = parseFloat(m[1]), lo = parseFloat(m[2]);
    if (Math.abs(la) <= 90 && Math.abs(lo) <= 180) return [la, lo];
  }
  return null;
}

/** Name of the place: Google Maps puts it on the first line before the link, or it is in /place/Name/ of the URL. */
function nameFrom(text, url) {
  if (url && /^geo:/i.test(url)) { let g = url; try { g = decodeURIComponent(url); } catch (e) { /* as is */ } const m = /\(([^)]+)\)/.exec(g); if (m) return m[1]; }
  const first = text.split(/\r?\n/).map((l) => l.trim()).find((l) => l && !/^https?:/i.test(l) && !/^geo:/i.test(l));
  if (first) return first;
  const m = url && /\/place\/([^/@?]+)/.exec(url);
  if (m) { try { return decodeURIComponent(m[1].replace(/\+/g, ' ')); } catch (e) { return m[1]; } }
  return null;
}

/** What we can tell without the network: { url, name, lat?, lon? }. A short link (maps.app.goo.gl) has no coordinates in it. */
export function parseShared(raw) {
  const text = (raw || '').trim();
  const url = (/(https?:\/\/\S+|geo:\S+)/i.exec(text) || [])[0] || null;
  const c = coordsIn(url || text);
  const name = nameFrom(text, url);
  return { url, name, lat: c ? c[0] : undefined, lon: c ? c[1] : undefined, short: !!url && /goo\.gl|maps\.app/i.test(url) };
}

export const mapsLink = (p) => 'https://www.google.com/maps/search/?api=1&query=' + p.lat + ',' + p.lon;
export const googleMapsNav = (p) => 'https://www.google.com/maps/dir/?api=1&destination=' + p.lat + ',' + p.lon;
export const wazeLink = (p) => 'https://waze.com/ul?ll=' + p.lat + ',' + p.lon + '&navigate=yes';

/** Share sheet (Tesla app, WhatsApp...) when the browser has one; otherwise copy the link. Returns a short message for the user. */
export async function sharePlace(p) {
  const data = { title: p.name, text: p.name, url: mapsLink(p) };
  if (navigator.share) { try { await navigator.share(data); return ''; } catch (e) { return ''; } }
  try { await navigator.clipboard.writeText(p.name + '\n' + mapsLink(p)); return 'Enlace copiado'; } catch (e) { return 'No se pudo compartir'; }
}
