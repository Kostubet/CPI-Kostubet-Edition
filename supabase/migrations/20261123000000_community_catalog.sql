begin;

create schema cpi_community;
revoke all on schema cpi_community from public, anon, authenticated;

create table cpi_community.profiles (
    user_id uuid primary key references auth.users(id) on delete cascade,
    nickname text not null check (nickname ~ '^[A-Za-z0-9_]{3,24}$')
);
create unique index cpi_nickname on cpi_community.profiles (lower(nickname));

create table cpi_community.configs (
    id uuid primary key default gen_random_uuid(),
    author_id uuid not null references cpi_community.profiles(user_id) on delete cascade,
    title text not null check (char_length(title) between 1 and 80),
    description text not null check (char_length(description) <= 600),
    body jsonb not null check (octet_length(body::text) <= 65536),
    created_at timestamptz not null default now()
);
create index cpi_configs_recent on cpi_community.configs (created_at desc, id desc);
create index cpi_configs_by_author on cpi_community.configs (author_id, created_at desc);

alter table cpi_community.profiles enable row level security;
alter table cpi_community.configs enable row level security;
revoke all on all tables in schema cpi_community from public, anon, authenticated;

create function public.cpi_find_configs(
    search_text text, own_only boolean, after_time timestamptz, after_id uuid
)
returns table (id uuid, title text, description text, author text, created_at timestamptz)
language sql stable security definer set search_path = ''
as $$
    select c.id, c.title, c.description, p.nickname, c.created_at
    from cpi_community.configs c
    join cpi_community.profiles p on p.user_id = c.author_id
    where (own_only is not true or c.author_id = (select auth.uid()))
      and (after_time is null or (c.created_at, c.id) < (after_time, after_id))
      and (
          coalesce(search_text, '') = ''
          or strpos(lower(c.title), lower(left(search_text, 80))) > 0
          or strpos(lower(c.description), lower(left(search_text, 80))) > 0
          or strpos(lower(p.nickname), lower(left(search_text, 80))) > 0
      )
    order by c.created_at desc, c.id desc
    limit 24;
$$;

create function public.cpi_get_config(config_id uuid)
returns jsonb
language sql stable security definer set search_path = ''
as $$
    select c.body || jsonb_build_object('metadata', jsonb_build_object(
        'title', c.title, 'description', c.description, 'author', p.nickname
    ))
    from cpi_community.configs c
    join cpi_community.profiles p on p.user_id = c.author_id
    where c.id = config_id;
$$;

create function public.cpi_profile_name()
returns text
language sql stable security definer set search_path = ''
as $$
    select p.nickname from cpi_community.profiles p where p.user_id = (select auth.uid());
$$;

create function public.cpi_set_profile_name(nickname text)
returns void
language plpgsql security definer set search_path = ''
as $$
declare
    caller uuid := auth.uid();
begin
    if caller is null then
        raise exception using errcode = '42501', message = 'Sign in first';
    end if;
    if nickname is null or nickname !~ '^[A-Za-z0-9_]{3,24}$' then
        raise exception using errcode = '22023', message = 'Invalid nickname';
    end if;
    insert into cpi_community.profiles (user_id, nickname) values (caller, nickname)
    on conflict (user_id) do update set nickname = excluded.nickname;
end;
$$;

create function public.cpi_publish_config(config_title text, config_description text, document jsonb)
returns uuid
language plpgsql security definer set search_path = ''
as $$
declare
    caller uuid := auth.uid();
    settings jsonb := document -> 'settings';
    result uuid;
begin
    if caller is null or not exists (select 1 from cpi_community.profiles where user_id = caller) then
        raise exception using errcode = '42501', message = 'Create a public profile first';
    end if;

    if config_title is null or char_length(btrim(config_title)) not between 1 and 80
        or config_description is null or char_length(config_description) > 600
        or document is null or octet_length(document::text) > 65536
        or document ->> 'format' is distinct from 'cherrypick-icons-settings'
        or document -> 'version' is distinct from '1'::jsonb
        or jsonb_typeof(settings) is distinct from 'object'
        or settings = '{}'::jsonb
    then
        raise exception using errcode = '22023', message = 'Invalid CPI document';
    end if;
    if exists (
        select 1 from jsonb_each(settings) as setting(name, value)
        where setting.name !~ '^[a-z0-9_]{1,120}$'
           or not (jsonb_typeof(setting.value) = 'boolean' or (
                jsonb_typeof(setting.value) = 'number'
                and setting.value::text ~ '^-?[0-9]{1,10}$'
                and (setting.value::text)::numeric between -2147483648 and 2147483647
            ))
    ) then
        raise exception using errcode = '22023', message = 'Invalid CPI setting';
    end if;

    perform pg_advisory_xact_lock(hashtextextended(caller::text, 0));
    if (select count(*) from cpi_community.configs where author_id = caller) >= 50
        or (select count(*) from cpi_community.configs
            where author_id = caller and created_at > now() - interval '1 minute') >= 5 then
        raise exception using errcode = 'PT429', message = 'Publication limit reached';
    end if;
    insert into cpi_community.configs (author_id, title, description, body)
    values (caller, btrim(config_title), btrim(config_description),
        jsonb_build_object('format', 'cherrypick-icons-settings', 'version', 1, 'settings', settings))
    returning id into result;
    return result;
end;
$$;

create function public.cpi_remove_config(config_id uuid)
returns boolean
language sql volatile security definer set search_path = ''
as $$
    with removed as (
        delete from cpi_community.configs where id = config_id and author_id = (select auth.uid()) returning 1
    )
    select exists (select 1 from removed);
$$;

revoke all on function public.cpi_find_configs(text, boolean, timestamptz, uuid),
    public.cpi_get_config(uuid), public.cpi_profile_name(), public.cpi_set_profile_name(text),
    public.cpi_publish_config(text, text, jsonb), public.cpi_remove_config(uuid)
    from public, anon, authenticated;
grant execute on function public.cpi_find_configs(text, boolean, timestamptz, uuid),
    public.cpi_get_config(uuid) to anon, authenticated;
grant execute on function public.cpi_profile_name(), public.cpi_set_profile_name(text),
    public.cpi_publish_config(text, text, jsonb), public.cpi_remove_config(uuid) to authenticated;

commit;
