alter table comment_threads add column deleted_at timestamptz;
alter table comments add column deleted_at timestamptz;
