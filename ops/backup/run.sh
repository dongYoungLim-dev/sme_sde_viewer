#!/bin/sh
# ─────────────────────────────────────────────────────────────────────────────
# SDE 보드 DB 백업 — 매일 03:00(Asia/Seoul)에 한 번, 14일치 보관.
#
# ⚠️ 왜 필요한가: 이 DB 에는 **ITSM 어디에도 없는 데이터**가 있다.
#    · app_user            가입 정보·역할·팀·법인·정/부  → 잃으면 전원 재가입
#    · sde_assignment      인력풀(법인×차수)             → 리더가 엑셀을 다시 찾아 손으로 넣어야 한다
#    · team_corp           팀별 담당 법인
#    · request_note(+_revision)  SME 분석 노트와 공유 이력
#    · request_schedule    요청 처리 일정과 수정·취소 사유 (2026-09-22)
#    · request_attachment  SME 첨부파일 메타. ⚠️ **실제 파일은 DB 가 아니라 ./uploads 폴더**에 있어서
#                          DB 덤프만으로는 돌아오지 않는다 → 같은 시점에 uploads-*.tar.gz 로 함께 묶는다
#    그리고 **재동기화로도 못 돌아오는 관측 기록**이 있다.
#    · request_status_history    ITSM 목록 API 에 상태 이력이 없다 (itsm.flow 를 고친 유일한 근거)
#    · itsm_request.done_at      완료일 필드가 없어 "우리가 처음 관측한 시각"으로 만든 값
#    · request_owner.first_seen  이게 날아가면 **모든 건이 '신규'로 뜬다**(고장으로 보이지도 않는다)
#
# 되돌리는 법은 restore.sh 에 있다. ⚠️ **복원을 해본 적 없는 백업은 백업이 아니다.**
# ─────────────────────────────────────────────────────────────────────────────
set -eu

DB_HOST="${DB_HOST:-mariadb}"
DB_NAME="${DB_NAME:-sdeboard}"
DB_USER="${DB_USER:-sdeboard}"
DB_PASS="${DB_PASS:-sdeboard}"
OUT_DIR="${OUT_DIR:-/backups}"
UPLOAD_DIR="${UPLOAD_DIR:-/uploads}"
KEEP_DAYS="${KEEP_DAYS:-14}"
AT_HOUR="${AT_HOUR:-03}"          # 매일 이 시각(로컬=Asia/Seoul)에 받는다
MIN_KEEP="${MIN_KEEP:-3}"         # 나이와 무관하게 지킬 최소 개수 (오래 안 켜도 전부 사라지지 않게)

log() { echo "$(date '+%Y-%m-%d %H:%M:%S') [backup] $*"; }

# dump_once [force]
dump_once() {
  day=$(date '+%Y%m%d')
  stamp=$(date '+%Y%m%d-%H%M%S')
  # 하루 한 번이면 충분하다 — 재기동 때마다 받으면 보관 기간의 뜻이 흐려진다.
  # (`--once` 로 부를 때는 건너뛰지 않는다 — PC 끄기 전에 일부러 받는 경우다)
  if [ "${1:-}" != "force" ] && ls "$OUT_DIR/sdeboard-$day-"*.sql.gz >/dev/null 2>&1; then
    log "오늘($day) 백업이 이미 있다 — 건너뜀"
    return 0
  fi

  tmp="$OUT_DIR/.sdeboard-$stamp.sql.gz.part"
  final="$OUT_DIR/sdeboard-$stamp.sql.gz"

  # --single-transaction: InnoDB 를 잠그지 않고 한 시점으로 받는다(서비스 중단 없음)
  if mariadb-dump -h "$DB_HOST" -u "$DB_USER" -p"$DB_PASS" \
        --single-transaction --routines --events --no-tablespaces \
        --default-character-set=utf8mb4 "$DB_NAME" 2>"$OUT_DIR/.last-error" | gzip -9 > "$tmp"; then
    :
  else
    log "❌ 덤프 실패 — $(tail -n 3 "$OUT_DIR/.last-error" 2>/dev/null)"
    rm -f "$tmp"
    return 1
  fi

  # ⚠️ 크기만 보면 안 된다. gzip 이 깨졌거나 빈 덤프여도 파일은 생긴다.
  if ! gzip -t "$tmp" 2>/dev/null; then
    log "❌ 압축이 깨졌다 — 버린다"; rm -f "$tmp"; return 1
  fi
  if ! gzip -dc "$tmp" | grep -q 'CREATE TABLE .app_user.'; then
    log "❌ 덤프에 app_user 가 없다 — 버린다(빈 덤프)"; rm -f "$tmp"; return 1
  fi

  mv "$tmp" "$final"
  log "✅ $(basename "$final") ($(du -h "$final" | cut -f1))"

  # 첨부파일 폴더 — DB 덤프가 성공한 뒤에 같은 스탬프로 묶는다. 비어 있으면 만들지 않는다.
  # ⚠️ 실패해도 DB 백업은 이미 끝났으므로 여기서 멈추지 않는다(로그만 남긴다).
  if [ -d "$UPLOAD_DIR" ] && [ -n "$(ls -A "$UPLOAD_DIR" 2>/dev/null)" ]; then
    up_tmp="$OUT_DIR/.uploads-$stamp.tar.gz.part"
    up_final="$OUT_DIR/uploads-$stamp.tar.gz"
    if tar -czf "$up_tmp" -C "$UPLOAD_DIR" . 2>/dev/null && gzip -t "$up_tmp" 2>/dev/null; then
      mv "$up_tmp" "$up_final"
      log "✅ $(basename "$up_final") ($(du -h "$up_final" | cut -f1))"
    else
      rm -f "$up_tmp"
      log "⚠️ 첨부파일 묶음 실패 — DB 덤프는 정상"
    fi
  fi
}

prune() {
  # ⚠️ **최근 MIN_KEEP 개는 나이와 상관없이 지킨다.**
  #    노트북이라 몇 주 안 켤 수 있다. 그때 나이만 보고 지우면 **가진 백업이 전부 사라진다** —
  #    하필 그날 덤프까지 실패하면 남는 게 하나도 없다. '오래됐다'는 '필요 없다'가 아니다.
  keep=$(ls -1t "$OUT_DIR"/sdeboard-*.sql.gz 2>/dev/null | head -n "$MIN_KEEP")
  n=0
  # -mtime +N 은 'N일보다 오래된' 이므로 14일치를 남기려면 +13 이다
  for f in $(find "$OUT_DIR" -maxdepth 1 -name 'sdeboard-*.sql.gz' -mtime "+$((KEEP_DAYS - 1))" 2>/dev/null); do
    if echo "$keep" | grep -qxF "$f"; then continue; fi
    rm -f "$f" && n=$((n + 1))
  done
  [ "$n" -gt 0 ] && log "🧹 ${KEEP_DAYS}일 지난 백업 ${n}건 삭제(최근 ${MIN_KEEP}개는 보존)" || true
  # 첨부파일 묶음도 같은 규칙으로 정리한다
  up_keep=$(ls -1t "$OUT_DIR"/uploads-*.tar.gz 2>/dev/null | head -n "$MIN_KEEP")
  for f in $(find "$OUT_DIR" -maxdepth 1 -name 'uploads-*.tar.gz' -mtime "+$((KEEP_DAYS - 1))" 2>/dev/null); do
    if echo "$up_keep" | grep -qxF "$f"; then continue; fi
    rm -f "$f"
  done
  rm -f "$OUT_DIR"/.sdeboard-*.sql.gz.part "$OUT_DIR"/.uploads-*.tar.gz.part 2>/dev/null || true      # 중단된 조각 청소
}

mkdir -p "$OUT_DIR"

# `--once` : 지금 당장 하나 받고 끝낸다 (PC 를 끄기 전에 쓰는 용도)
if [ "${1:-}" = "--once" ]; then
  dump_once force
  prune
  exit 0
fi

log "시작 — ${AT_HOUR}:00 이후 하루 1회, ${KEEP_DAYS}일 보관(최소 ${MIN_KEEP}개), 저장 위치 $OUT_DIR"
# ⚠️ **기동 직후 한 번은 시각을 안 따진다.** 노트북은 새벽에 꺼져 있어서, 사실상 이게 그날의 백업이다.
dump_once || true
prune

# ⚠️ 정확한 시각까지 한 번에 sleep 하지 않는다.
#    · 호스트가 잠들면(맥북 덮개) 타이머가 통째로 밀려 그날을 건너뛴다
#    · 시각 계산에 앞의 0(`08`)이 끼면 8진수로 읽혀 죽는데, `restart: unless-stopped` 탓에
#      **조용히 재기동만 반복**한다(실제로 겪었다)
#    그래서 매시간 깨어나 "오늘 것이 없으면 받는다" 로 단순하게 간다.
while true; do
  sleep 3600
  day=$(date '+%Y%m%d')
  # 앞의 0 을 뗀다 — `08` 을 그대로 비교에 넣으면 8진수로 읽혀 죽는다(`00` → 빈 문자열 주의)
  now_h=$(date '+%H'); now_h=${now_h#0}; [ -z "$now_h" ] && now_h=0
  at_h=${AT_HOUR#0}; [ -z "$at_h" ] && at_h=0
  if [ "$now_h" -ge "$at_h" ] && ! ls "$OUT_DIR/sdeboard-$day-"*.sql.gz >/dev/null 2>&1; then
    dump_once || true      # 하루 실패해도 루프를 멈추지 않는다 — 한 시간 뒤 다시 해본다
    prune
  fi
done
