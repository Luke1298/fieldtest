-- fieldtest records in Postgres. Exposures are the source of truth for which
-- arm a unit got; feedback joins to them through feedback_exposures.

create table if not exists exposures (
  exposure_id text primary key,
  experiment  text not null,
  revision    integer not null,
  arm         text not null,
  sticky_key  text,
  context     jsonb not null default '{}',
  created_at  timestamptz not null
);

create index if not exists exposures_experiment on exposures (experiment, revision);

create table if not exists feedback (
  id          bigserial primary key,
  metric      text not null,
  value       double precision not null,
  value_unit  text not null,
  outcome     text not null,
  attrs       jsonb not null default '{}',
  recorded_at timestamptz not null
);

create table if not exists feedback_exposures (
  feedback_id bigint not null references feedback (id) on delete cascade,
  exposure_id text not null,
  primary key (feedback_id, exposure_id)
);

create index if not exists feedback_exposures_exposure on feedback_exposures (exposure_id);
