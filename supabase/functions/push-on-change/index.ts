// Edge Function: avisa por push a los dispositivos cuando cambia el bucket de un perfil.
//
// La dispara un trigger de Postgres sobre la tabla `buckets` (ver supabase/trigger.sql).
// Lo único que manda es un "timbre": un mensaje de datos SIN contenido, que solo dice
// qué perfil cambió para que la app vaya a buscarlo por su cuenta. Los datos reales
// nunca pasan por Firebase.
//
// Secrets que necesita (Dashboard → Edge Functions → Secrets):
//   FCM_PROJECT_ID    id del proyecto de Firebase (ej. bitacora-7ddfa)
//   FCM_CLIENT_EMAIL  campo client_email del service account
//   FCM_PRIVATE_KEY   campo private_key del service account
//   PUSH_SECRET       string al azar, compartido con el trigger
// SUPABASE_URL y SUPABASE_SERVICE_ROLE_KEY las inyecta Supabase sola.

const FCM_PROJECT_ID = Deno.env.get("FCM_PROJECT_ID") ?? "";
const FCM_CLIENT_EMAIL = Deno.env.get("FCM_CLIENT_EMAIL") ?? "";
const PUSH_SECRET = Deno.env.get("PUSH_SECRET") ?? "";
const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
const SERVICE_ROLE = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";

// La private key del service account trae saltos de línea. Según cómo se pegue en el
// dashboard puede quedar con "\n" literales o con saltos reales: se contemplan los dos
// casos para que no dependa de cómo se copió.
const FCM_PRIVATE_KEY = (Deno.env.get("FCM_PRIVATE_KEY") ?? "").replace(/\\n/g, "\n");

// ---------------------------------------------------------------- utilidades

function b64url(data: Uint8Array | string): string {
  const bytes = typeof data === "string" ? new TextEncoder().encode(data) : data;
  let bin = "";
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function pemToArrayBuffer(pem: string): ArrayBuffer {
  const b64 = pem
    .replace(/-----BEGIN PRIVATE KEY-----/, "")
    .replace(/-----END PRIVATE KEY-----/, "")
    .replace(/\s+/g, "");
  const bin = atob(b64);
  const buf = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) buf[i] = bin.charCodeAt(i);
  return buf.buffer;
}

// El access token de Google dura una hora. Se cachea en memoria: mientras la instancia
// siga viva se reusa, en vez de firmar un JWT nuevo en cada push.
let cachedToken: { token: string; exp: number } | null = null;

async function getAccessToken(): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  if (cachedToken && cachedToken.exp > now + 60) return cachedToken.token;

  const header = { alg: "RS256", typ: "JWT" };
  const claim = {
    iss: FCM_CLIENT_EMAIL,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token",
    iat: now,
    exp: now + 3600,
  };
  const unsigned = `${b64url(JSON.stringify(header))}.${b64url(JSON.stringify(claim))}`;

  const key = await crypto.subtle.importKey(
    "pkcs8",
    pemToArrayBuffer(FCM_PRIVATE_KEY),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const sig = new Uint8Array(
    await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(unsigned)),
  );
  const jwt = `${unsigned}.${b64url(sig)}`;

  const res = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion: jwt,
    }),
  });
  if (!res.ok) throw new Error(`oauth ${res.status}: ${await res.text()}`);

  const json = await res.json();
  cachedToken = { token: json.access_token, exp: now + (json.expires_in ?? 3600) };
  return cachedToken.token;
}

// ------------------------------------------------------------------ handler

Deno.serve(async (req) => {
  // Secreto compartido con el trigger: evita que cualquiera que descubra la URL pueda
  // disparar pushes. No protege datos (el mensaje no lleva ninguno), sino la batería
  // de los dispositivos frente a spam.
  if (!PUSH_SECRET || req.headers.get("x-push-secret") !== PUSH_SECRET) {
    return new Response("unauthorized", { status: 401 });
  }

  let userKey = "";
  try {
    userKey = (await req.json())?.user_key ?? "";
  } catch {
    return new Response("bad body", { status: 400 });
  }
  if (!userKey) return new Response("missing user_key", { status: 400 });

  // Estructura de las claves: "BASE" es el perfil por defecto y "BASE::<algo>" el resto.
  // Se parte por el primer "::" para no depender de cuál sea el USER_KEY concreto.
  const sep = userKey.indexOf("::");
  const base = sep === -1 ? userKey : userKey.slice(0, sep);
  const suffix = sep === -1 ? "" : userKey.slice(sep + 2);

  // Estos dos buckets no son datos de un perfil y no tienen que despertar a nadie.
  // Saltear "devices" además corta de raíz cualquier realimentación: registrar un token
  // escribe ese bucket, y sin esta guarda ese registro dispararía pushes.
  if (suffix === "index" || suffix === "devices") {
    return new Response(JSON.stringify({ skipped: suffix }), { status: 200 });
  }
  const profileId = suffix === "" ? "default" : suffix;

  // Qué dispositivos están parados en ese perfil ahora mismo.
  const devicesKey = `${base}::devices`;
  const devRes = await fetch(
    `${SUPABASE_URL}/rest/v1/buckets?user_key=eq.${encodeURIComponent(devicesKey)}&select=data`,
    { headers: { apikey: SERVICE_ROLE, Authorization: `Bearer ${SERVICE_ROLE}` } },
  );
  if (!devRes.ok) {
    return new Response(`devices read failed: ${devRes.status}`, { status: 500 });
  }
  const rows = await devRes.json();
  const devices = rows?.[0]?.data?.devices ?? [];

  const targets = devices
    .filter((d: Record<string, unknown>) =>
      !d.deleted && d.token && d.profileId === profileId
    )
    .map((d: Record<string, unknown>) => String(d.token));

  if (targets.length === 0) {
    return new Response(JSON.stringify({ profileId, sent: 0 }), { status: 200 });
  }

  const accessToken = await getAccessToken();

  // Mensaje SOLO de datos (sin bloque "notification"): Android no dibuja nada por su
  // cuenta, se lo entrega a PushService y la app decide. priority HIGH es lo que hace
  // que el equipo se despierte aunque esté en Doze.
  const dead: string[] = [];
  const results = await Promise.all(
    targets.map(async (token: string) => {
      const r = await fetch(
        `https://fcm.googleapis.com/v1/projects/${FCM_PROJECT_ID}/messages:send`,
        {
          method: "POST",
          headers: {
            Authorization: `Bearer ${accessToken}`,
            "Content-Type": "application/json",
          },
          body: JSON.stringify({
            message: {
              token,
              data: { profileId },
              android: { priority: "HIGH" },
            },
          }),
        },
      );
      if (!r.ok) {
        const body = await r.text();
        console.error(`fcm ${r.status} para ${token.slice(0, 12)}…: ${body}`);
        // Token muerto: la instalación a la que pertenecía ya no existe (se desinstaló
        // la app, se borraron sus datos, o cambió la firma del APK). Se marca borrado
        // para que la app lo note y se vuelva a registrar sola en su próxima apertura.
        // Sin esto el push queda roto en silencio y para siempre.
        if (r.status === 404 || body.includes("UNREGISTERED") || body.includes("INVALID_ARGUMENT")) {
          dead.push(token);
        }
      }
      return r.ok;
    }),
  );

  if (dead.length > 0) {
    const limpio = devices.map((d: Record<string, unknown>) =>
      dead.includes(String(d.token)) ? { ...d, deleted: true, updatedAt: Date.now() } : d
    );
    // Escribir este bucket vuelve a disparar el trigger, pero el sufijo "devices" está
    // salteado más arriba, así que no hay realimentación.
    await fetch(`${SUPABASE_URL}/rest/v1/buckets?on_conflict=user_key`, {
      method: "POST",
      headers: {
        apikey: SERVICE_ROLE,
        Authorization: `Bearer ${SERVICE_ROLE}`,
        "Content-Type": "application/json",
        Prefer: "resolution=merge-duplicates",
      },
      body: JSON.stringify([
        { user_key: devicesKey, data: { devices: limpio }, updated_at: Date.now() },
      ]),
    });
    console.log(`marcados ${dead.length} token(s) muertos para re-registro`);
  }

  const sent = results.filter(Boolean).length;
  console.log(`perfil ${profileId}: ${sent}/${targets.length} enviados`);
  return new Response(JSON.stringify({ profileId, sent, total: targets.length, dead: dead.length }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
});
