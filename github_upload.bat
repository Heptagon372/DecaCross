@echo off
setlocal
REM ※ 이 파일은 반드시 CP949(ANSI) 인코딩 + CRLF 줄바꿈으로 저장해야 합니다. (UTF-8/LF 로 저장하면 cmd 가 깨짐)
cd /d "%~dp0"
title DecaCross - GitHub 업로드

set "REMOTE_URL=https://github.com/Heptagon372/DecaCross.git"
set "BRANCH=main"

echo ==========================================================
echo   DecaCross  ^>  %REMOTE_URL%
echo ==========================================================
echo.

where git >nul 2>&1
if errorlevel 1 (
  echo [오류] git 을 찾을 수 없습니다.
  echo        https://git-scm.com/download/win 에서 설치한 뒤 다시 실행하세요.
  pause
  exit /b 1
)

REM ---- 1. 저장소 준비 -------------------------------------------------
if exist ".git" (
  echo [1/5] 기존 git 저장소 사용
) else (
  echo [1/5] git 저장소 초기화
  git init >nul
)

git remote get-url origin >nul 2>&1
if errorlevel 1 (
  git remote add origin "%REMOTE_URL%"
) else (
  git remote set-url origin "%REMOTE_URL%"
)

REM 커밋 작성자 정보가 없으면 이 저장소에만 기본값을 넣는다 (전역 설정은 건드리지 않음)
git config user.name  >nul 2>&1 || git config user.name  "Heptagon372"
git config user.email >nul 2>&1 || git config user.email "heptagon372@gmail.com"

REM ---- 2. 변경 사항 확인 ----------------------------------------------
echo.
echo [2/5] 변경 사항
git add -A
git status --short
echo.

git diff --cached --quiet
if not errorlevel 1 goto :nochanges

REM ---- 3. 커밋 ---------------------------------------------------------
set "MSG="
set /p "MSG=커밋 메시지 입력 (그냥 Enter = 자동 메시지): "
if "%MSG%"=="" set "MSG=update %date% %time:~0,8%"
echo.
echo [3/5] 커밋: "%MSG%"
git commit -q -m "%MSG%"
if errorlevel 1 (
  echo [오류] 커밋에 실패했습니다. 위 메시지를 확인하세요.
  pause
  exit /b 1
)
goto :push

:nochanges
echo [3/5] 변경 사항이 없습니다. 커밋은 건너뛰고 푸시만 합니다.

REM ---- 4. 푸시 ---------------------------------------------------------
:push
git branch -M %BRANCH% >nul 2>&1

echo.
echo [4/5] 푸시 중... (첫 실행이면 GitHub 로그인 창이 뜰 수 있습니다)
git push -u origin %BRANCH%
if not errorlevel 1 goto :done

echo.
echo [알림] 원격 저장소에 이미 다른 커밋이 있는 것 같습니다. 원격 변경을 먼저 합칩니다...
git pull --rebase --allow-unrelated-histories origin %BRANCH%
if errorlevel 1 (
  echo [오류] 자동 병합에 실패했습니다. 충돌을 해결한 뒤 다시 실행하세요.
  pause
  exit /b 1
)
git push -u origin %BRANCH%
if errorlevel 1 (
  echo [오류] 푸시에 실패했습니다. 위 메시지를 확인하세요.
  pause
  exit /b 1
)

:done
echo.
echo [5/5] 완료!  %REMOTE_URL%
echo.
pause
