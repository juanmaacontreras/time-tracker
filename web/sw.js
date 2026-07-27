/* Service worker de Bitácora.
 *
 * Hace dos cosas: que la app se pueda instalar y funcione sin conexión, y que avise
 * cuando hay una versión nueva.
 *
 * VERSION la inyecta el workflow de GitHub Pages con la cantidad de commits (igual que
 * el versionCode de la APK). Eso es lo que hace que el navegador detecte que este
 * archivo cambió y dispare el aviso de actualización. Si quedara fijo, se podría
 * publicar una versión nueva del HTML sin que nadie se entere.
 */
const VERSION = "__VERSION__";
const CACHE = `bitacora-${VERSION}`;

const SHELL = [
  "./",
  "./manifest.json",
  "./icons/icon-192.png",
  "./icons/icon-512.png",
  "./icons/icon-transparent-32.png",
  "./icons/icon-transparent-192.png",
  "./icons/icon-transparent-512.png",
];

self.addEventListener("install", (e) => {
  // Cada recurso se cachea por separado: si uno fallara, no tira abajo toda la
  // instalación del service worker.
  e.waitUntil((async () => {
    const c = await caches.open(CACHE);
    await Promise.all(SHELL.map((u) => c.add(u).catch(() => {})));
  })());
  // A propósito SIN skipWaiting acá: el service worker nuevo se queda esperando hasta
  // que la página lo autorice (ver el mensaje SKIP_WAITING). Activarlo solo cambiaría
  // el código por debajo de una pestaña abierta y en uso.
});

self.addEventListener("activate", (e) => {
  e.waitUntil((async () => {
    // Fuera los caches de versiones anteriores.
    const keys = await caches.keys();
    await Promise.all(
      keys.filter((k) => k.startsWith("bitacora-") && k !== CACHE).map((k) => caches.delete(k))
    );
    await self.clients.claim();
  })());
});

// La página avisa que el usuario aceptó actualizar.
self.addEventListener("message", (e) => {
  if (e.data === "SKIP_WAITING") self.skipWaiting();
});

self.addEventListener("fetch", (e) => {
  const req = e.request;
  if (req.method !== "GET") return;

  const url = new URL(req.url);

  // CRÍTICO: todo lo que no sea de este origen pasa derecho, sin tocar. Acá caen las
  // llamadas a Supabase. Si se cachearan, el sync devolvería datos viejos y la app
  // mostraría un estado que ya no existe — el peor bug posible en este proyecto.
  if (url.origin !== location.origin) return;

  // El propio service worker nunca se cachea: el navegador maneja su actualización por
  // su cuenta, y cachearlo sería justamente impedir que se detecten versiones nuevas.
  if (url.pathname.endsWith("/sw.js")) return;

  // El HTML va contra la red primero, así estando online SIEMPRE se ve la última
  // versión. El cache queda solo como respaldo para cuando no hay conexión.
  const esDocumento = req.mode === "navigate" || url.pathname.endsWith("/") ||
    url.pathname.endsWith("/index.html");
  e.respondWith(esDocumento ? redPrimero(req) : cachePrimero(req));
});

async function redPrimero(req) {
  const c = await caches.open(CACHE);
  try {
    const res = await fetch(req);
    if (res && res.ok) c.put(req, res.clone());
    return res;
  } catch (err) {
    // Sin conexión: lo último que se haya visto, o el shell.
    return (await c.match(req)) || (await c.match("./")) || Response.error();
  }
}

async function cachePrimero(req) {
  const c = await caches.open(CACHE);
  const hit = await c.match(req);
  if (hit) return hit;
  const res = await fetch(req);
  if (res && res.ok) c.put(req, res.clone());
  return res;
}
