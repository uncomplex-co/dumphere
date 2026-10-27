create table mcp_signing_keys (
    key_name varchar(20) primary key check (key_name = 'active'),
    private_jwk text not null,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp
);
