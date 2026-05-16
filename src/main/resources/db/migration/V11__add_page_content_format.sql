alter table html_pages
    add column content_format text not null default 'HTML',
    add constraint html_pages_content_format_check check (content_format in ('HTML', 'MARKDOWN'));
