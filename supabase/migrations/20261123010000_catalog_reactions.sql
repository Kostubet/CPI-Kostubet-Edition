begin;

alter table cpi_community.configs add column pinned boolean not null default false;
create index cpi_configs_catalog_order on cpi_community.configs (pinned desc, created_at desc, id desc);

create table cpi_community.reactions (
    config_id uuid not null references cpi_community.configs(id) on delete cascade,
    user_id uuid not null references auth.users(id) on delete cascade,
    kind text not null check (kind in ('like', 'favorite')),
    primary key (config_id, user_id, kind)
);
create index cpi_reactions_by_user on cpi_community.reactions (user_id, kind, config_id);
alter table cpi_community.reactions enable row level security;
revoke all on cpi_community.reactions from public, anon, authenticated;

-- Public GitHub account ID of Kostubet. CPI nicknames and editable profile data grant no rights.
create function public.cpi_can_pin_configs()
returns boolean
language sql stable security definer set search_path = ''
as $$
    select exists (
        select 1 from auth.identities i
        where i.user_id = (select auth.uid()) and i.provider = 'github'
          and i.provider_id = '336900904'
    );
$$;

-- Preserve the original RPC for older APKs.
create function public.cpi_find_configs_v2(
    search_text text, own_only boolean, favorites_only boolean,
    after_pinned boolean, after_time timestamptz, after_id uuid
)
returns table (id uuid, title text, description text, author text, created_at timestamptz,
    pinned boolean, likes bigint, favorites bigint, liked boolean, favorited boolean)
language sql stable security definer set search_path = ''
as $$
    with matches as (
        select c.id, c.title, c.description, p.nickname, c.created_at, c.pinned
        from cpi_community.configs c
        join cpi_community.profiles p on p.user_id = c.author_id
        where (own_only is not true or c.author_id = (select auth.uid()))
          and (favorites_only is not true or exists (
              select 1 from cpi_community.reactions r
              where r.config_id = c.id and r.user_id = (select auth.uid()) and r.kind = 'favorite'
          ))
          and (after_time is null or (c.pinned, c.created_at, c.id) < (after_pinned, after_time, after_id))
          and (
              coalesce(search_text, '') = ''
              or strpos(lower(c.title), lower(left(search_text, 80))) > 0
              or strpos(lower(c.description), lower(left(search_text, 80))) > 0
              or strpos(lower(p.nickname), lower(left(search_text, 80))) > 0
          )
        order by c.pinned desc, c.created_at desc, c.id desc
        limit 24
    )
    select m.id, m.title, m.description, m.nickname, m.created_at, m.pinned,
        reactions.likes, reactions.favorites, reactions.liked, reactions.favorited
    from matches m
    cross join lateral (
        select count(*) filter (where r.kind = 'like') as likes,
            count(*) filter (where r.kind = 'favorite') as favorites,
            count(*) filter (where r.kind = 'like' and r.user_id = (select auth.uid())) > 0 as liked,
            count(*) filter (where r.kind = 'favorite' and r.user_id = (select auth.uid())) > 0 as favorited
        from cpi_community.reactions r where r.config_id = m.id
    ) reactions
    order by m.pinned desc, m.created_at desc, m.id desc;
$$;

create function public.cpi_set_config_reaction(config_id uuid, reaction text, selected boolean)
returns table (likes bigint, favorites bigint, liked boolean, favorited boolean)
language plpgsql volatile security definer set search_path = ''
as $$
declare
    caller uuid := auth.uid();
begin
    if caller is null then
        raise exception using errcode = '42501', message = 'Sign in first';
    end if;
    if reaction is null or reaction not in ('like', 'favorite') or selected is null then
        raise exception using errcode = '22023', message = 'Invalid reaction';
    end if;
    perform 1 from cpi_community.configs c where c.id = $1 for key share;
    if not found then
        raise exception using errcode = 'PT404', message = 'Config not found';
    end if;
    if selected then
        insert into cpi_community.reactions values ($1, caller, reaction)
        on conflict on constraint reactions_pkey do nothing;
    else
        delete from cpi_community.reactions r where r.config_id = $1 and r.user_id = caller and r.kind = reaction;
    end if;
    return query select
        count(*) filter (where r.kind = 'like'),
        count(*) filter (where r.kind = 'favorite'),
        count(*) filter (where r.kind = 'like' and r.user_id = caller) > 0,
        count(*) filter (where r.kind = 'favorite' and r.user_id = caller) > 0
    from cpi_community.reactions r where r.config_id = $1;
end;
$$;

create function public.cpi_set_config_pin(config_id uuid, pinned boolean)
returns void
language plpgsql volatile security definer set search_path = ''
as $$
begin
    if not public.cpi_can_pin_configs() then
        raise exception using errcode = '42501', message = 'Administrator access required';
    end if;
    if pinned is null then
        raise exception using errcode = '22023', message = 'Invalid pin state';
    end if;
    update cpi_community.configs c set pinned = $2 where c.id = $1;
    if not found then
        raise exception using errcode = 'PT404', message = 'Config not found';
    end if;
end;
$$;

revoke all on function public.cpi_find_configs_v2(text, boolean, boolean, boolean, timestamptz, uuid),
    public.cpi_can_pin_configs(), public.cpi_set_config_reaction(uuid, text, boolean),
    public.cpi_set_config_pin(uuid, boolean) from public, anon, authenticated;
grant execute on function public.cpi_find_configs_v2(text, boolean, boolean, boolean, timestamptz, uuid)
    to anon, authenticated;
grant execute on function public.cpi_can_pin_configs(), public.cpi_set_config_reaction(uuid, text, boolean),
    public.cpi_set_config_pin(uuid, boolean) to authenticated;

commit;
