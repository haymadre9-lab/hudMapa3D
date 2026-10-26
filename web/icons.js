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

export const icon = (name) => ICONS[name] || ICONS.S;
