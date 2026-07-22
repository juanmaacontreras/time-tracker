-- Pegá esto en Supabase → SQL Editor → New query → Run.

create table if not exists public.buckets (
    user_key   text primary key,
    data       jsonb not null default '{}'::jsonb,
    updated_at bigint not null default 0
);

-- Para uso personal simple, desactivamos RLS así la app (con la anon key)
-- puede leer y escribir tu fila. Ver nota de seguridad en SETUP.md.
alter table public.buckets disable row level security;
