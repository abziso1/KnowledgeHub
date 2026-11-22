create table content_trash_batches (
    id uuid primary key,
    library_id uuid not null references libraries (id),
    kind varchar(6) not null check (kind in ('NOTE', 'FOLDER')),
    target_id uuid not null,
    name varchar(120) not null,
    trashed_at timestamptz not null default clock_timestamp(),
    unique (library_id, id)
);

alter table folders add foreign key (library_id, trash_batch_id) references content_trash_batches (library_id, id);
alter table notes add foreign key (library_id, trash_batch_id) references content_trash_batches (library_id, id);

create index content_trash_order on content_trash_batches (library_id, trashed_at desc, id);
create index folder_trash_batch on folders (library_id, trash_batch_id) where trash_batch_id is not null;
create index note_trash_batch on notes (library_id, trash_batch_id) where trash_batch_id is not null;
