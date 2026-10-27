create table mcp_refresh_tokens (
    token_hash varchar(43) primary key,
    client_id varchar(100) not null,
    subject text not null,
    email text,
    display_name text,
    scopes text not null,
    expires_at timestamp with time zone not null,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp
);

create index mcp_refresh_tokens_expiry_idx on mcp_refresh_tokens (expires_at);
