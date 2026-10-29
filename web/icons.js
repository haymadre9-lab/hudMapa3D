// Vector icons: some screens (the car's browser, old phones) lack the arrow / cross glyphs and draw empty rectangles instead.
const svg = (inner, rot = 0) => '<svg viewBox="0 0 24 24" width="1em" height="1em" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><g transform="rotate(' + rot + ' 12 12)">' + inner + '</g></svg>';
const UP = '<path d="M12 21V4M5 11l7-7 7 7"/>';

const ICONS = {
  x: svg('<path d="M5 5l14 14M19 5L5 19"/>'),
  S: svg(UP), R: svg(UP, 90), L: svg(UP, -90), SR: svg(UP, 45), X: svg(UP, 45), SL: svg(UP, -45),
  U: svg('<path d="M17 21V9a5 5 0 0 0-10 0v7M3.5 12.5L7 16.5l3.5-4"/>'),
  RB: svg('<path d="M20 12a8 8 0 1 1-2.6-5.9M20 3.5v5h-5"/>'),
  ARRIVE: svg('<path d="M6 21V3.5M6 4.5h12l-2.5 4 2.5 4H6"/>'),
  share: svg(UP, 45),
};

/** Where a roundabout is left, clockwise from straight on: the real angle when the router gave it, else by exit number in quarters (1st right, 2nd straight, 3rd left, the rest = back the way we came). */
export const rbAngle = (step) => (step && step.exitAngle != null) ? step.exitAngle : ({ 1: 90, 2: 0, 3: 270 }[step && step.exitNo] ?? (step && step.exitNo ? 180 : 0));

/** Turning roundabout: the ring spins, the exit arrow sits where the exit is and the exit number is inside in black. */
function roundabout(step) {
  const ang = rbAngle(step), ad = Math.abs(ang - 180) < 25 ? 150 : ang;       // a U-turn is drawn beside the way in
  const pt = (r, deg) => [24 + r * Math.sin(deg * Math.PI / 180), 24 - r * Math.cos(deg * Math.PI / 180)].map((v) => v.toFixed(1));
  const [x0, y0] = pt(10, ad), [x1, y1] = pt(21, ad), [hx1, hy1] = pt(21 - 0, ad);
  const [ax, ay] = pt(15, ad + 28), [bx, by] = pt(15, ad - 28), [cx, cy] = pt(22.5, ad);
  const num = step && step.exitNo > 0 ? '<text x="24" y="29.4" text-anchor="middle" font-size="14" font-weight="800" font-family="system-ui,Arial,sans-serif" fill="#000" stroke="none">' + step.exitNo + '</text>' : '';
  return '<svg class="rb" viewBox="0 0 48 48" fill="none" stroke="currentColor" stroke-width="3.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">'
    + '<circle cx="24" cy="24" r="12" stroke-dasharray="19 6.1" stroke-width="3.2"><animate attributeName="stroke-dashoffset" from="0" to="75.4" dur="3.2s" repeatCount="indefinite"/></circle>'
    + '<circle cx="24" cy="24" r="9" fill="#fff" stroke="none"/>' + num
    + '<path d="M24 46V36"/>'
    + '<path d="M' + x0 + ' ' + y0 + 'L' + cx + ' ' + cy + '"/><path d="M' + ax + ' ' + ay + 'L' + cx + ' ' + cy + 'L' + bx + ' ' + by + '"/></svg>';
}

export const icon = (name, step) => (name === 'RB' && step) ? roundabout(step) : (ICONS[name] || ICONS.S);
