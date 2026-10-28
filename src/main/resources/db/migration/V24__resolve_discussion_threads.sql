alter table comment_threads add column resolved_at timestamptz;
alter table comment_threads add column resolved_by_user_id bigint references users(id);
