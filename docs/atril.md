# Sesiones que llegan de Atril

Atril (el lector de EPUBs, repo `ebook-reader`) registra cada sesión de lectura y escucha.
Desde el 2026-11-09 esas sesiones entran solas a Bitácora (fase 7 del ecosistema, diseño
elegido por el usuario: **el libro suma a su materia**).

## El contrato: la bandeja `USER_KEY::atril`

Una fila más de la tabla `buckets`. **La escribe solo Atril** (su Edge Function `bitacora`,
cada 15 minutos y solo si algo cambió); Bitácora solo la lee. Las bases siguen separadas: si
Atril se cae, Bitácora anda igual, y al revés.

```json
{
  "v": 1,
  "profile": "default",
  "books": [{ "id": "uuid", "title": "…", "author": "…" | null, "subject": "Sistemas de Comunicaciones" | null }],
  "sessions": [{ "id": "uuid", "bookId": "uuid", "kind": "read" | "listen",
                 "start": 1762700000000, "seconds": 600, "updatedAt": 1762700600000 }]
}
```

- `profile`: el perfil de Bitácora que las recibe. Otro perfil la ignora.
- `sessions`: los últimos 60 días. Las más viejas salen de la bandeja pero quedan en
  Bitácora. `seconds` es tiempo activo (Atril descuenta las pausas).
- `subject`: la materia, para los libros del resumidor (`a_atril.py` la carga).
- Un `v` distinto de 1 se ignora: un cambio de formato sube la versión en los dos lados.

## Cómo entra (`AtrilImport.kt` y su espejo `AtrilImport` en `web/index.html`)

En cada sync del perfil se baja la bandeja (si falla, se sigue sin ella) y, después de
fusionar el remoto, se convierte en entradas normales que pasan por `Merge.list`:

- **Sesión**: id `atril-<id de Atril>`, desde `start` hasta `start + seconds`. Menos de un
  minuto no cuenta.
- **Actividad del libro**: 1) la que se llama como la materia; 2) si no, la que se llama
  como el libro; 3) si no, una nueva `atril-book-<id>` llamada "Título (Autor)" en la
  categoría Libro (`updatedAt: 1`, así cualquier cambio tuyo le gana). Los nombres se
  comparan sin tildes, sin mayúsculas, sin lo que va entre paréntesis y sin palabras de
  una o dos letras: "Bóvedas de acero" encuentra "Bovédas de Acero (Isaac Asimov)".
- **Superposición**: si una sesión cargada a mano en la misma actividad se cruza con una de
  Atril, la de Atril queda borrada (ese rato ya lo contó el cronómetro). Su `updatedAt`
  sigue al de esas sesiones a mano, así que borrar la de mano la hace volver.
- Mientras el cronómetro corre, la sesión de Atril se ve; al pararlo, la regla anterior la
  borra.

Todo es determinístico (mismos ids y mismos `updatedAt` en cualquier dispositivo), por eso
no se duplica ni genera escrituras en bucle: una bandeja sin cambios no produce subida.
Los casos `atril` de `tests/fixtures/shared.json` corren contra la app y la web.

## Lo que no hace

- No avisa por push: la bandeja tiene sufijo `atril`, que no es un perfil, y la función
  `push-on-change` no encuentra dispositivos. Las sesiones aparecen en el próximo sync
  (abrir la app, el ciclo de la web o cualquier otro cambio).
- Una sesión borrada en Atril no se borra acá.
- Editar una importada (cambiarle la actividad o borrarla) gana mientras Atril no la
  actualice; solo la actualiza mientras esa sesión sigue en curso.
