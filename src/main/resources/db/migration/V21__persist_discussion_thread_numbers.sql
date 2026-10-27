alter table comment_threads add column number bigint;

with numbered as (
    select id, row_number() over (partition by artifact_id order by created_at, id) as number
    from comment_threads
)
update comment_threads t set number = numbered.number from numbered where t.id = numbered.id;

alter table comment_threads alter column number set not null;
alter table comment_threads add constraint comment_threads_number_positive check (number > 0);
alter table comment_threads add constraint comment_threads_artifact_number_unique unique (artifact_id, number);

alter table html_pages add column last_discussion_thread_number bigint not null default 0;

update html_pages p set last_discussion_thread_number = counts.last_number
from (select artifact_id, max(number) as last_number from comment_threads group by artifact_id) counts
where p.id = counts.artifact_id;
