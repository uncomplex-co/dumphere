alter table html_pages
    add column created_by text,
    add column updated_by text;

alter table html_page_versions
    add column created_by text;
