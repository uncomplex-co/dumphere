alter table mcp_refresh_tokens add column user_id bigint;

update mcp_refresh_tokens tokens
set user_id = users.id
from users
where tokens.subject = users.subject;

-- Orphaned legacy credentials must be revoked, not used to invent system users.
delete from mcp_refresh_tokens where user_id is null;

alter table mcp_refresh_tokens
    alter column user_id set not null,
    add constraint mcp_refresh_tokens_user_fk
        foreign key (user_id) references users(id) on delete cascade,
    drop column subject,
    drop column email,
    drop column display_name;

create index mcp_refresh_tokens_user_idx on mcp_refresh_tokens (user_id);
