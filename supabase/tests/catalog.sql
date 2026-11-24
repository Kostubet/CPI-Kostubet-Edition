-- Run against a development database after applying the migration.
begin;

insert into auth.users (id, email) values
    ('b7d337ae-6393-4b74-9a38-01166ac41852', 'catalog-author@example.test'),
    ('88ea056e-7a46-4803-bc82-2580406202fd', 'catalog-reader@example.test');

set local role authenticated;
select set_config('request.jwt.claim.sub', 'b7d337ae-6393-4b74-9a38-01166ac41852', true);
select public.cpi_set_profile_name('catalog_fixture');
select set_config('cpi.test_config_id', public.cpi_publish_config('Catalog % fixture', 'Portable settings',
    '{"format":"cherrypick-icons-settings","version":1,"settings":{"color_mode":0},"metadata":{"author":"fake"}}'
)::text, true);

do $$
begin
    begin
        perform public.cpi_publish_config('Invalid', '', '{"format":"cherrypick-icons-settings","version":1,"settings":{"color_mode":"invalid"}}');
        raise exception 'Invalid setting was accepted';
    exception when invalid_parameter_value then null;
    end;
    if not exists (select 1 from public.cpi_find_configs('', true, null, null)
        where id = current_setting('cpi.test_config_id')::uuid) then
        raise exception 'Author cannot see their publication';
    end if;
end;
$$;

select set_config('request.jwt.claim.sub', '88ea056e-7a46-4803-bc82-2580406202fd', true);

do $$
begin
    begin
        perform public.cpi_set_profile_name('Catalog_Fixture');
        raise exception 'Case-insensitive duplicate nickname was accepted';
    exception when unique_violation then null;
    end;
    if public.cpi_remove_config(current_setting('cpi.test_config_id')::uuid) then
        raise exception 'Another account removed the publication';
    end if;
    if exists (select 1 from public.cpi_find_configs('', true, null, null)
        where id = current_setting('cpi.test_config_id')::uuid) then
        raise exception 'Another account saw the publication under its own account';
    end if;
end;
$$;

set local role anon;
select set_config('request.jwt.claim.sub', '', true);

do $$
declare
    config_id uuid := current_setting('cpi.test_config_id')::uuid;
    document jsonb;
begin
    if not exists (select 1 from public.cpi_find_configs('CATALOG %', false, null, null) where id = config_id) then
        raise exception 'Public search failed';
    end if;
    document := public.cpi_get_config(config_id);
    if document #>> '{metadata,author}' is distinct from 'catalog_fixture' then
        raise exception 'Authenticated author metadata was not used';
    end if;
    if document #>> '{settings,color_mode}' is distinct from '0' then
        raise exception 'Portable settings did not round-trip';
    end if;
    if exists (select 1 from public.cpi_find_configs('', false, now(), config_id) where id = config_id) then
        raise exception 'Cursor repeated the previous entry';
    end if;
    begin
        perform public.cpi_remove_config(config_id);
        raise exception 'Anonymous deletion was allowed';
    exception when insufficient_privilege then null;
    end;
    begin
        perform public.cpi_publish_config('Anonymous', '', document);
        raise exception 'Anonymous publication was allowed';
    exception when insufficient_privilege then null;
    end;
    begin
        perform 1 from cpi_community.profiles;
        raise exception 'Anonymous access to private profiles was allowed';
    exception when insufficient_privilege then null;
    end;
end;
$$;

set local role authenticated;
select set_config('request.jwt.claim.sub', 'b7d337ae-6393-4b74-9a38-01166ac41852', true);

do $$
declare
    config_id uuid := current_setting('cpi.test_config_id')::uuid;
begin
    if not public.cpi_remove_config(config_id) then
        raise exception 'Author could not remove their publication';
    end if;
    if public.cpi_get_config(config_id) is not null then
        raise exception 'Deleted config is still downloadable';
    end if;
end;
$$;

rollback;
