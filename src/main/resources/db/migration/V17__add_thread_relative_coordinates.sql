alter table comment_threads
    add column relative_x double precision,
    add column relative_y double precision,
    add constraint comment_threads_relative_coordinates_check check (
        (relative_x is null and relative_y is null)
        or (
            xpath is not null
            and relative_x is not null and relative_y is not null
            and relative_x between 0 and 1
            and relative_y between 0 and 1
        )
    );
