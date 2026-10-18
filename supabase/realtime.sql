-- Prende Supabase Realtime para la tabla de buckets: la web (Live, en web/index.html)
-- se suscribe a los cambios del bucket del perfil activo y se entera al instante, en
-- vez de esperar el sondeo de 10 s.
--
-- Correr esto UNA VEZ en el SQL Editor del dashboard de Supabase. Es idempotente:
-- correrlo de nuevo no rompe nada.
--
-- Realtime respeta los permisos de lectura de la tabla para la clave pública: como la
-- app ya lee los buckets con esa clave, no hace falta tocar nada más.

do $$
begin
  if not exists (
    select 1 from pg_publication_tables
    where pubname = 'supabase_realtime' and schemaname = 'public' and tablename = 'buckets'
  ) then
    alter publication supabase_realtime add table public.buckets;
  end if;
end $$;
