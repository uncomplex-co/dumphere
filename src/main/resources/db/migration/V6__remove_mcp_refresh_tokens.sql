update oauth2_registered_client
set authorization_grant_types = 'authorization_code'
where authorization_grant_types like '%refresh_token%'
  and redirect_uris like '%/mcp/oauth/callback%';

delete from oauth2_authorization
where authorization_grant_type = 'refresh_token'
   or attributes like '%/mcp/oauth/callback%';

delete from oauth2_authorization_consent
where registered_client_id in (
    select id from oauth2_registered_client
    where redirect_uris like '%/mcp/oauth/callback%'
);
