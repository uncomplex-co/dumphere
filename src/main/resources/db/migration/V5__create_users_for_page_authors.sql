create table users (
    id bigint generated always as identity primary key,
    subject text not null unique,
    email text,
    display_name text,
    created_at timestamptz not null,
    updated_at timestamptz not null
);

insert into users (subject, email, display_name, created_at, updated_at)
select distinct author, author, null, now(), now()
from (
    select created_by as author from html_pages where created_by is not null
    union
    select updated_by as author from html_pages where updated_by is not null
    union
    select created_by as author from html_page_versions where created_by is not null
) authors;

alter table html_pages
    add column created_by_user_id bigint references users(id),
    add column updated_by_user_id bigint references users(id);

alter table html_page_versions
    add column created_by_user_id bigint references users(id);

alter table html_page_versions
    drop constraint html_page_versions_pkey,
    add column id bigint generated always as identity primary key,
    add constraint html_page_versions_page_version_key unique (page_id, version);

update html_pages p
set created_by_user_id = u.id
from users u
where p.created_by = u.subject;

update html_pages p
set updated_by_user_id = u.id
from users u
where p.updated_by = u.subject;

update html_page_versions v
set created_by_user_id = u.id
from users u
where v.created_by = u.subject;
