alter table html_pages add column custom_metadata jsonb not null default '{}'::jsonb;
alter table html_pages add constraint html_pages_custom_metadata_object check (jsonb_typeof(custom_metadata) = 'object');
