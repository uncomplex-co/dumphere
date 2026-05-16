create table html_pages (
    id text primary key,
    title text not null,
    url text not null,
    created_at timestamptz not null,
    updated_at timestamptz,
    current_version integer not null check (current_version > 0),
    current_bytes bigint not null check (current_bytes >= 0)
);

create table html_page_versions (
    page_id text not null references html_pages(id) on delete cascade,
    version integer not null check (version > 0),
    html text not null,
    bytes bigint not null check (bytes >= 0),
    created_at timestamptz not null,
    primary key (page_id, version)
);
