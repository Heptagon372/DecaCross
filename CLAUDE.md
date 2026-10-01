# DecaCross — 세션 공통 규칙

프로젝트 규칙 전체: @DecaCross_PLAN/CLAUDE.md

## 모든 세션의 git 흐름 (GitHub: Heptagon372/DecaCross)

작업 결과는 반드시 GitHub 에 커밋으로 남기고, main 에는 PR squash 머지로만 들어간다.

1. **시작할 때 확인** — `git fetch --prune` 후 `git status -sb`, `git worktree list`.
   - 체크아웃이 **다른 브랜치이거나 미커밋 변경이 있으면 다른 세션이 쓰는 중이다.** `git switch`/`stash`/`reset` 하지 말고
     새 워크트리를 만든다: `git worktree add --no-track .claude/worktrees/<이름> -b <scope>/<설명> origin/main`
   - 앱이 만들어 준 워크트리 세션이면 그 브랜치를 그대로 쓴다. base 최신화는 `sync_with_base_branch` 도구로
   - 비어 있는 main 이면 `git switch -c <scope>/<설명>` (main 에 직접 커밋 금지)
2. **작업 중 커밋** — 논리 단위마다 `<scope>: <요약>` 커밋 + `Co-Authored-By` 줄. 커밋 전 손댄 모듈의 테스트·ktlintCheck 를 돌린다
   (Gradle 은 한 번에 하나, 다른 세션이 빌드 중이면 기다린다). 커밋하면 바로 `git push` — 세션이 끊겨도 남게
3. **PR** — 작업 단위(prompts 한 단계, 수정 한 묶음)가 끝나면 `gh pr create`. 제목 `<scope>: <요약>`(= 머지 커밋 메시지),
   본문에 무엇을·왜·테스트 결과. 만든 뒤 앱 PR 바에 연결(get_status / bind_pr)
4. **최신화·충돌** — main 이 앞서가면 `git rebase origin/main`, 충돌은 직접 해결(rerere 켜져 있음),
   `git push --force-with-lease`. 남의 브랜치는 rebase 하지 않는다
5. **머지** — 사용자가 머지하라고 하면 `gh pr merge <n> --squash --delete-branch`. 사용자 승인 없이 머지하지 않는다
6. **정리** — 머지 후 `git worktree remove .claude/worktrees/<이름>`, 로컬 브랜치 삭제, 원래 체크아웃은 `git pull`

금지: `github_upload.bat`(main 직접 push), force-push to main, `--no-verify`, jar·build 산출물·비밀값 커밋.
