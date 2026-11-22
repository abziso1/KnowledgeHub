create table notes (
    id uuid primary key,
    library_id uuid not null references libraries (id),
    folder_id uuid,
    title varchar(120) not null,
    markdown text not null,
    version bigint not null default 0 check (version >= 0),
    updated_at timestamptz not null default current_timestamp,
    trash_batch_id uuid,
    unique (library_id, id),
    foreign key (library_id, folder_id) references folders (library_id, id)
);

create index notes_active_folder_order on notes (library_id, folder_id, updated_at desc, id)
    where trash_batch_id is null;
