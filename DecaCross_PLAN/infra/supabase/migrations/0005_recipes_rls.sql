-- 0005_recipes_rls.sql — recipes + RLS (설계서 §8)
-- ★ append-only. 수정 금지.

create table if not exists recipes (
  id          bigserial primary key,
  owner       uuid references auth.users(id) on delete set null,
  slug        text unique not null check (slug ~ '^[a-z0-9][a-z0-9-]{1,63}$'),
  name        text not null,
  dcx         jsonb not null,                          -- .dcx v1.1 본문 (스키마 검증은 API 계층)
  dcx_version text not null default '1.1',
  visibility  text not null default 'private' check (visibility in ('private','unlisted','public')),
  price       int not null default 0,                  -- 0 = 무료 (마켓은 Phase 4)
  installs    bigint not null default 0,
  forks       bigint not null default 0,
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now()
);
create index if not exists recipes_public_idx on recipes (visibility, created_at desc);

alter table recipes enable row level security;

-- 읽기: public 이거나(unlisted 는 슬러그를 아는 사람이 직접 조회) 본인 소유
create policy recipes_select on recipes for select
  using (visibility in ('public','unlisted') or owner = auth.uid());

-- 쓰기: 본인 소유만
create policy recipes_insert on recipes for insert with check (owner = auth.uid());
create policy recipes_update on recipes for update using (owner = auth.uid());
create policy recipes_delete on recipes for delete using (owner = auth.uid());

-- 설치 카운트는 서비스 롤(API 서버)만 올린다
create or replace function bump_recipe_installs(p_slug text) returns void
language sql security definer as $$
  update recipes set installs = installs + 1, updated_at = now() where slug = p_slug;
$$;
