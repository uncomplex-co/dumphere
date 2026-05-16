update oauth2_registered_client
set client_settings = '{"@class":"java.util.Collections$UnmodifiableMap","settings.client.require-proof-key":true,"settings.client.require-authorization-consent":false}'
where redirect_uris like '%/mcp/oauth/callback%';

delete from oauth2_authorization_consent
where registered_client_id in (
    select id from oauth2_registered_client
    where redirect_uris like '%/mcp/oauth/callback%'
);
