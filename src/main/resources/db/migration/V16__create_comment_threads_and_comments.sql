create table comment_threads (
    id bigint generated always as identity primary key,
    artifact_id text not null references html_pages(id) on delete cascade,
    created_in_version_id bigint not null references html_page_versions(id),
    created_by_user_id bigint references users(id),
    created_at timestamptz not null,
    xpath text
);

create index comment_threads_artifact_id_idx on comment_threads (artifact_id, created_at, id);

create table comments (
    id bigint generated always as identity primary key,
    thread_id bigint not null references comment_threads(id) on delete cascade,
    created_in_version_id bigint not null references html_page_versions(id),
    author_user_id bigint references users(id),
    body text not null,
    created_at timestamptz not null
);

create index comments_thread_id_idx on comments (thread_id, created_at, id);
