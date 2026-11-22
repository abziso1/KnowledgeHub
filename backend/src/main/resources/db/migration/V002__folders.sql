create table folders (
    id uuid primary key,
    library_id uuid not null references libraries (id),
    parent_id uuid,
    name varchar(120) not null,
    updated_at timestamptz not null default current_timestamp,
    trash_batch_id uuid,
    unique (library_id, id),
    foreign key (library_id, parent_id) references folders (library_id, id)
);

create unique index folders_active_sibling_name
    on folders (library_id, parent_id, name) nulls not distinct
    where trash_batch_id is null;
