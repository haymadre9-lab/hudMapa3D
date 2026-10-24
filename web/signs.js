// Road signs drawn with canvas (same designs as the Android app): bent arrow (B), roundabout with exits (R2),
// motorway block arrow and exit number (M2), speed-limit disc.
import * as THREE from 'three';

const cache = new Map();
function texture(key, w, h, draw) {
  let t = cache.get(key);
  if (!t) {
    const c = document.createElement('canvas'); c.width = w; c.height = h;
    const g = c.getContext('2d'); draw(g, w, h);
    t = new THREE.CanvasTexture(c); t.colorSpace = THREE.SRGBColorSpace; t.anisotropy = 4;
    cache.set(key, t);
  }
  return t;
}

function rr(g, x, y, w, h, r) { g.beginPath(); g.roundRect(x, y, w, h, r); }
function tri(g, x1, y1, x2, y2, x3, y3) { g.beginPath(); g.moveTo(x1, y1); g.lineTo(x2, y2); g.lineTo(x3, y3); g.closePath(); g.fill(); }
function text(g, s, cx, cy, size, color, weight = 'bold') {
  g.fillStyle = color; g.font = weight + ' ' + size + 'px system-ui, sans-serif'; g.textAlign = 'center'; g.textBaseline = 'middle'; g.fillText(s, cx, cy);
}
function fitText(g, s, cx, cy, maxW, size0, color) {
  let size = size0;
  g.font = 'bold ' + size + 'px system-ui, sans-serif';
  while (g.measureText(s).width > maxW && size > 26) { size -= 3; g.font = 'bold ' + size + 'px system-ui, sans-serif'; }
  let t = s;
  while (g.measureText(t).width > maxW && t.length > 3) t = t.slice(0, -2);
  if (t !== s) t = t.trimEnd() + '…';
  text(g, t, cx, cy, size, color);
}

function plainArrow(g, cx, cy, u, deg, color) {
  g.save(); g.translate(cx, cy); g.rotate(deg * Math.PI / 180); g.fillStyle = color;
  g.beginPath();
  g.moveTo(-55 * u, -12 * u); g.lineTo(8 * u, -12 * u); g.lineTo(8 * u, -34 * u); g.lineTo(55 * u, 0);
  g.lineTo(8 * u, 34 * u); g.lineTo(8 * u, 12 * u); g.lineTo(-55 * u, 12 * u); g.closePath(); g.fill(); g.restore();
}

/** Style B: thick bent arrow for turns, plus straight / slight / U-turn variants. */
function turnArrow(g, cx, cy, u, kind, color) {
  g.save(); g.translate(cx, cy); g.fillStyle = color; g.strokeStyle = color; g.lineWidth = 17 * u; g.lineJoin = 'miter'; g.lineCap = 'butt';
  if (kind === 'R' || kind === 'L') {
    if (kind === 'L') g.scale(-1, 1);
    g.beginPath(); g.moveTo(-30 * u, 58 * u); g.lineTo(-30 * u, -8 * u); g.lineTo(16 * u, -8 * u); g.stroke();
    tri(g, 12 * u, -36 * u, 56 * u, -8 * u, 12 * u, 20 * u);
  } else if (kind === 'U') {
    g.beginPath(); g.moveTo(-24 * u, 58 * u); g.lineTo(-24 * u, -6 * u); g.arc(0, -6 * u, 24 * u, Math.PI, 2 * Math.PI); g.lineTo(24 * u, 22 * u); g.stroke();
    tri(g, -2 * u, 18 * u, 50 * u, 18 * u, 24 * u, 54 * u);
  } else {
    g.rotate((kind === 'SR' || kind === 'X' ? 32 : kind === 'SL' ? -32 : 0) * Math.PI / 180);
    g.beginPath(); g.moveTo(0, 60 * u); g.lineTo(0, -18 * u); g.stroke();
    tri(g, -28 * u, -16 * u, 0, -60 * u, 28 * u, -16 * u);
  }
  g.restore();
}

/** Roundabout R2: ring + exit stubs; the exit to take is thick with an arrowhead. */
function roundabout(g, cx, cy, u, exitNo, color) {
  const chosen = exitNo === 1 ? 0 : exitNo === 3 ? 2 : 1, ang = [0, -90, 180];
  g.lineCap = 'butt'; g.strokeStyle = color; g.lineWidth = 9 * u;
  g.beginPath(); g.arc(cx, cy, 30 * u, 0, 2 * Math.PI); g.stroke();
  ang.forEach((a, i) => {
    const r = a * Math.PI / 180, dx = Math.cos(r), dy = Math.sin(r);
    if (i === chosen) {
      g.strokeStyle = color; g.lineWidth = 14 * u; g.beginPath(); g.moveTo(cx + dx * 30 * u, cy + dy * 30 * u); g.lineTo(cx + dx * 56 * u, cy + dy * 56 * u); g.stroke();
      g.save(); g.translate(cx + dx * 66 * u, cy + dy * 66 * u); g.rotate(r); g.fillStyle = color; tri(g, 0, 0, -20 * u, -16 * u, -20 * u, 16 * u); g.restore();
    } else { g.strokeStyle = '#aaaaaa'; g.lineWidth = 9 * u; g.beginPath(); g.moveTo(cx + dx * 30 * u, cy + dy * 30 * u); g.lineTo(cx + dx * 66 * u, cy + dy * 66 * u); g.stroke(); }
  });
  g.strokeStyle = color; g.lineWidth = 11 * u; g.beginPath(); g.moveTo(cx, cy + 30 * u); g.lineTo(cx, cy + 66 * u); g.stroke();
}

const mwAngle = (k) => ({ R: 0, L: 180, SR: -35, X: -35, SL: -145, U: 180 }[k] ?? -90);

/** Roadside direction plate: road = white / black (B + R2), motorway = blue / white (block arrow, M2 exit number). */
export function directionTexture(motorway, kind, label, exitNo) {
  return texture(`dir|${motorway}|${kind}|${label}|${exitNo}`, 760, 400, (g, w, h) => {
    const bg = motorway ? '#0a4fa3' : '#ffffff', fg = motorway ? '#ffffff' : '#111111', cy = h / 2;
    g.fillStyle = bg; rr(g, 4, 4, w - 8, h - 8, 30); g.fill();
    g.strokeStyle = fg; g.lineWidth = 8; rr(g, 18, 18, w - 36, h - 36, 22); g.stroke();
    if (kind === 'ARRIVE') { text(g, '🏁', 150, cy, 150, fg); fitText(g, label, 500, cy, 410, 80, fg); }
    else if (motorway && kind === 'X' && exitNo > 0) {
      text(g, String(exitNo), 150, 120, 110, fg); plainArrow(g, 150, 290, 1.2, -35, fg);
      fitText(g, 'Salida ' + exitNo, 500, 130, 410, 84, fg); fitText(g, label, 500, 270, 410, 62, '#cfe3ff');
    } else if (motorway) { plainArrow(g, 150, cy, 1.9, mwAngle(kind), fg); fitText(g, label, 500, cy, 410, 80, fg); }
    else if (kind === 'RB') { roundabout(g, 150, cy - 4, 1.7, exitNo, fg); fitText(g, label || 'Rotonda', 500, cy, 410, 80, fg); }
    else { turnArrow(g, 150, cy - 4, 1.9, kind, fg); fitText(g, label, 500, cy, 410, 80, fg); }
  });
}

export function limitTexture(n) {
  return texture('lim|' + n, 256, 256, (g, w) => {
    const r = w / 2;
    g.fillStyle = '#fff'; g.beginPath(); g.arc(r, r, r - 4, 0, 2 * Math.PI); g.fill();
    g.strokeStyle = '#d6191e'; g.lineWidth = 34; g.beginPath(); g.arc(r, r, r - 4 - 17, 0, 2 * Math.PI); g.stroke();
    text(g, String(n), r, r + 4, n >= 100 ? 92 : 110, '#111');
  });
}

/** Red octagon STOP. */
export function stopTexture() {
  return texture('stop', 256, 256, (g, w) => {
    const c = w / 2, r = c - 6;
    g.beginPath(); for (let i = 0; i < 8; i++) { const a = Math.PI / 8 + i * Math.PI / 4; g.lineTo(c + r * Math.cos(a), c + r * Math.sin(a)); } g.closePath();
    g.fillStyle = '#d6191e'; g.fill(); g.lineWidth = 10; g.strokeStyle = '#fff'; g.stroke();
    text(g, 'STOP', c, c + 4, 78, '#fff');
  });
}
/** Give-way triangle (point down). */
export function yieldTexture() {
  return texture('yield', 256, 256, (g, w) => {
    g.fillStyle = '#fff'; tri(g, 10, 30, w - 10, 30, w / 2, w - 20);
    g.fillStyle = '#d6191e'; tri(g, 10, 30, w - 10, 30, w / 2, w - 20);
    g.fillStyle = '#fff'; tri(g, 52, 62, w - 52, 62, w / 2, w - 78);
  });
}
/** Pedestrian crossing: blue square with a walking person. */
export function crossingTexture() {
  return texture('cross', 256, 256, (g, w) => {
    g.fillStyle = '#0a4fa3'; rr(g, 6, 6, w - 12, w - 12, 24); g.fill();
    g.strokeStyle = '#fff'; g.lineWidth = 8; rr(g, 16, 16, w - 32, w - 32, 16); g.stroke();
    text(g, '🚶', w / 2, w / 2 + 6, 130, '#fff', 'normal');
  });
}
/** Speed-camera plate: limit disc with a camera icon strip. */
export function radarTexture(n) {
  return texture('rad|' + n, 256, 330, (g, w) => {
    g.fillStyle = '#0a4fa3'; rr(g, 4, 4, w - 8, 322, 22); g.fill();
    g.fillStyle = '#fff'; g.beginPath(); g.arc(w / 2, 118, 96, 0, 2 * Math.PI); g.fill();
    g.strokeStyle = '#d6191e'; g.lineWidth = 22; g.beginPath(); g.arc(w / 2, 118, 85, 0, 2 * Math.PI); g.stroke();
    text(g, n > 0 ? String(n) : '!', w / 2, 122, n >= 100 ? 84 : 100, '#111');
    text(g, '📷 RADAR', w / 2, 278, 52, '#fff');
  });
}
