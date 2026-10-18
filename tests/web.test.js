// Corre los casos de tests/fixtures/shared.json contra el código REAL de web/index.html
// (se extrae del HTML, no hay copia). La app corre los mismos casos en
// app/src/test/.../SharedFixturesTest.kt. Uso: node tests/web.test.js
"use strict";
const fs = require("fs");
const path = require("path");
const assert = require("assert");

const html = fs.readFileSync(path.join(__dirname, "../web/index.html"), "utf8");
const from = html.indexOf("/* ====================== CONFIG");
const to = html.indexOf("/* ====================== HELPERS DE FECHA");
if (from < 0 || to < 0) throw new Error("no se encontraron los marcadores de sección en web/index.html");
const core = html.slice(from, to);

// Entorno mínimo: localStorage en memoria, sin red ni WebSocket.
const mem = {};
global.localStorage = {
  getItem: k => (k in mem ? mem[k] : null),
  setItem: (k, v) => { mem[k] = String(v); },
  removeItem: k => { delete mem[k]; }
};
global.WebSocket = undefined;
global.document = { getElementById: () => ({}) };
const web = {};
new Function("module", core + "\nmodule.Store = Store; module.ProfileStore = ProfileStore;")(web);
const { Store } = web;

const fixtures = JSON.parse(fs.readFileSync(path.join(__dirname, "fixtures/shared.json"), "utf8"));
let failures = 0;
function check(name, fn) {
  try { fn(); console.log("ok   " + name); }
  catch (e) { failures++; console.log("FAIL " + name + "\n     " + e.message); }
}

// Carga un dataset como si estuviera guardado en este navegador y devuelve el root().
function load(data) {
  localStorage.setItem("bitacora_profile", "default");
  localStorage.setItem(Store.dataKey("default"), JSON.stringify(Object.assign({ categories: [] }, data || {})));
  Store._root = null; Store._rootProfile = null;
  return Store.root();
}

for (const c of fixtures.categoryIds) {
  check("id de categoría " + JSON.stringify(c.name), () => assert.strictEqual(Store._stableCategoryId(c.name), c.id));
}

for (const c of fixtures.merge) {
  check("merge: " + c.name, () => {
    load(c.local);
    const res = Store.merge("default", c.remote);
    const o = Store.root();
    assert.strictEqual(res.changed, c.expect.changed, "changed");
    assert.strictEqual(res.upload !== null, c.expect.behind, "behind");
    for (const [k, n] of Object.entries(c.expect.counts || {})) assert.strictEqual(o[k].length, n, "count " + k);
    for (const kind of ["activities", "sessions", "categories"]) {
      for (const [id, fields] of Object.entries(c.expect[kind] || {})) {
        const e = o[kind].find(x => x.id === id);
        assert.ok(e, "falta " + kind + "/" + id);
        for (const [f, v] of Object.entries(fields)) {
          const got = f === "deleted" ? !!e[f] : e[f];
          assert.deepStrictEqual(got, v, kind + "/" + id + "." + f);
        }
      }
    }
    for (const [f, v] of Object.entries(c.expect.run || {})) assert.deepStrictEqual(o[f], v, f);
  });
}

const realNow = Date.now;
for (const c of fixtures.stats) {
  check("stats: " + c.name, () => {
    load(c.data);
    Date.now = () => c.now;
    try {
      const got = Store.secsByActivity(c.from, c.to === null ? undefined : c.to);
      for (const k of Object.keys(got)) if (!got[k]) delete got[k];
      assert.deepStrictEqual(got, c.expect);
    } finally { Date.now = realNow; }
  });
}

if (failures) { console.log("\n" + failures + " caso(s) fallaron"); process.exit(1); }
console.log("\nTodos los casos compartidos pasan en la web.");
