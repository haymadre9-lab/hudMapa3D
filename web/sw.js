// Offline support. App files: network first (so updates always arrive), cache as fallback when offline.
// The 3D library from the CDN is cache first.
const CACHE = 'd3d-clone-v23';
const SHELL = ['./', 'index.html', 'style.css', 'app.js', 'nav.js', 'roaddata.js', 'link.js', 'sharedplaces.js', 'vendor/three/build/three.module.js', 'vendor/three/examples/jsm/loaders/GLTFLoader.js', 'vendor/three/examples/jsm/libs/meshopt_decoder.module.js', 'vendor/three/examples/jsm/environments/RoomEnvironment.js', 'vendor/three/examples/jsm/utils/BufferGeometryUtils.js', 'vendor/maplibre/maplibre-gl.js', 'vendor/maplibre/maplibre-gl.css', 'signs.js', 'voice.js', 'manifest.webmanifest', 'icon.svg', 'assets/tesla_model3.glb', 'assets/edificio_3.glb', 'assets/edificio_5.glb', 'assets/edificio_brick.glb', 'assets/edificio_corficolombiana_colombian_skyscraper.glb'];

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting()));
});
self.addEventListener('activate', (e) => {
  e.waitUntil(caches.keys().then((ks) => Promise.all(ks.filter((k) => k !== CACHE).map((k) => caches.delete(k)))).then(() => self.clients.claim()));
});
self.addEventListener('fetch', (e) => {
  if (e.request.method !== 'GET') return;
  const url = new URL(e.request.url);
  const sameOrigin = url.origin === location.origin;
  const cdn = url.hostname === 'cdn.jsdelivr.net';
  if (!sameOrigin && !cdn) return;                      // maps, routes, etc. always go to the network
  const store = (res) => { if (res.ok) { const copy = res.clone(); caches.open(CACHE).then((c) => c.put(e.request, copy)); } return res; };
  if (sameOrigin) {
    e.respondWith(fetch(e.request, { cache: 'no-cache' }).then(store).catch(() => caches.match(e.request)));
  } else {
    e.respondWith(caches.match(e.request).then((hit) => hit || fetch(e.request).then(store)));
  }
});
