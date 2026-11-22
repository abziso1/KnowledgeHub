create table images (
    id uuid primary key,
    library_id uuid not null references libraries (id),
    blob_key text not null unique,
    content_type text not null,
    state varchar(10) not null check (state in ('TEMPORARY', 'REFERENCED', 'QUEUED')),
    expires_at timestamptz not null,
    unique (library_id, id)
);

create index images_temporary_expiry on images (expires_at, id)
    where state = 'TEMPORARY';

create table note_image_references (
    library_id uuid not null,
    note_id uuid not null,
    image_id uuid not null,
    primary key (library_id, note_id, image_id),
    foreign key (library_id, note_id) references notes (library_id, id) on delete cascade,
    foreign key (library_id, image_id) references images (library_id, id)
);

create index references_by_image on note_image_references (library_id, image_id);

create table image_cleanup_jobs (
    blob_key text primary key,
    attempts int not null default 0 check (attempts >= 0),
    next_attempt_at timestamptz not null,
    leased_until timestamptz
);

create index image_cleanup_jobs_due on image_cleanup_jobs (next_attempt_at, blob_key);
