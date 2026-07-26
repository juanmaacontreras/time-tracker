-- Trigger que llama a la Edge Function cuando cambia cualquier bucket.
--
-- Correr esto UNA VEZ en el SQL Editor del dashboard de Supabase, reemplazando antes
-- el valor de PUSH_SECRET por el mismo string que se cargó como secret de la función.
--
-- net.http_post es asincrónico (fire and forget): el INSERT/UPDATE no espera a que el
-- push salga, así que sincronizar desde la app no se vuelve más lento por esto.

create extension if not exists pg_net with schema extensions;

create or replace function public.notify_bucket_change()
returns trigger
language plpgsql
security definer
as $$
begin
  perform net.http_post(
    url := 'https://rjjrudenrxixofwhsknk.supabase.co/functions/v1/push-on-change',
    headers := jsonb_build_object(
      'Content-Type', 'application/json',
      -- CAMBIAR: mismo valor que el secret PUSH_SECRET de la Edge Function.
      'x-push-secret', 'PONER_ACA_EL_PUSH_SECRET'
    ),
    body := jsonb_build_object('user_key', new.user_key)
  );
  return new;
end;
$$;

drop trigger if exists buckets_push on public.buckets;

create trigger buckets_push
after insert or update on public.buckets
for each row execute function public.notify_bucket_change();
