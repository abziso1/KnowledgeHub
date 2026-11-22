create table accounts (
    id uuid primary key,
    username varchar(80) not null unique,
    password_hash text not null,
    role varchar(5) not null check (role in ('USER', 'ADMIN')),
    enabled boolean not null default true,
    session_version bigint not null default 0,
    created_at timestamptz not null default current_timestamp
);

create table libraries (
    id uuid primary key,
    owner_id uuid not null references accounts (id),
    name varchar(120) not null,
    updated_at timestamptz not null default current_timestamp,
    trashed_at timestamptz
);

create unique index libraries_active_owner_name on libraries (owner_id, name)
    where trashed_at is null;
