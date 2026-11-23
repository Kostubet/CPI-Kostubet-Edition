-- Run on an isolated development database with both catalog migrations applied.
begin;

insert into auth.users (id, email) values
    ('0281adc6-502a-4182-908a-33a19faf3697', 'catalog-owner@example.test'),
    ('9ce3ae79-7673-4f03-be36-35c487fb2504', 'catalog-reader@example.test');
insert into auth.identities (provider, provider_id, user_id, identity_data)
values ('github', '336900904', '0281adc6-502a-4182-908a-33a19faf3697', '{"sub":"336900904"}');

set local role authenticated;
select set_config('request.jwt.claim.sub', '0281adc6-502a-4182-908a-33a19faf3697', true);
select public.cpi_set_profile_name('fixture_owner');
select set_config('cpi.test_pinned_id', public.cpi_publish_config('Pin fixture', '',
    '{"format":"cherrypick-icons-settings","version":1,"settings":{"color_mode":0}}')::text, true);
select set_config('cpi.test_regular_id', public.cpi_publish_config('Regular fixture', '',
    '{"format":"cherrypick-icons-settings","version":1,"settings":{"color_mode":0}}')::text, true);
select public.cpi_set_config_pin(current_setting('cpi.test_pinned_id')::uuid, true);

select set_config('request.jwt.claim.sub', '9ce3ae79-7673-4f03-be36-35c487fb2504', true);
select public.cpi_set_profile_name('Kostubet');
do $$
declare
    target uuid := current_setting('cpi.test_pinned_id')::uuid;
    other uuid := current_setting('cpi.test_regular_id')::uuid;
    added record;
    pinned_time timestamptz;
begin
    if public.cpi_can_pin_configs() then
        raise exception 'Nickname granted administrator access';
    end if;
    begin
        perform public.cpi_set_config_pin(target, false);
        raise exception 'Non-admin unpinned a config';
    exception when insufficient_privilege then null;
    end;
    perform public.cpi_set_config_reaction(target, 'like', true);
    perform public.cpi_set_config_reaction(target, 'like', true);
    select * into strict added from public.cpi_set_config_reaction(target, 'favorite', true);
    if added.likes <> 1 or added.favorites <> 1 or not (added.liked and added.favorited) then
        raise exception 'Retry created a duplicate reaction or did not return current state';
    end if;
    if (select count(*) from public.cpi_find_configs_v2('fixture', false, true, null, null, null)) <> 1 then
        raise exception 'Favorites filter did not isolate the selected publication';
    end if;
    select c.created_at into strict pinned_time from public.cpi_find_configs_v2('fixture', false, false, null, null, null) c
        where c.id = target;
    if (select id from public.cpi_find_configs_v2('fixture', false, false, null, null, null) limit 1) <> target
        or (select id from public.cpi_find_configs_v2('fixture', false, false, true, pinned_time, target) limit 1) <> other then
        raise exception 'Pinned catalog ordering or cursor is wrong';
    end if;
end;
$$;

set local role anon;
select set_config('request.jwt.claim.sub', '', true);
do $$
declare
    target uuid := current_setting('cpi.test_pinned_id')::uuid;
    found_entry record;
begin
    select * into strict found_entry from public.cpi_find_configs_v2('Pin fixture', false, false, null, null, null)
        where id = target;
    if not found_entry.pinned or found_entry.likes <> 1 or found_entry.favorites <> 1
        or found_entry.liked or found_entry.favorited then
        raise exception 'Public counts or personal reaction privacy are wrong';
    end if;
    begin
        perform public.cpi_set_config_reaction(target, 'like', false);
        raise exception 'Anonymous mutation was allowed';
    exception when insufficient_privilege then null;
    end;
end;
$$;

set local role authenticated;
select set_config('request.jwt.claim.sub', '9ce3ae79-7673-4f03-be36-35c487fb2504', true);
select public.cpi_set_config_reaction(current_setting('cpi.test_pinned_id')::uuid, 'favorite', false);
do $$
begin
    if exists (select 1 from public.cpi_find_configs_v2('fixture', false, true, null, null, null)) then
        raise exception 'Removing a favorite did not update the list';
    end if;
end;
$$;

rollback;
