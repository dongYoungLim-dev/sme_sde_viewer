#!/bin/sh
# ─────────────────────────────────────────────────────────────────────────────
# 백업 복원. ⚠️ **복원을 해본 적 없는 백업은 백업이 아니다** — 그래서 이 파일이 있다.
#
#   연습(안전):  docker exec sdeboard-backup /backup/restore.sh /backups/sdeboard-YYYYMMDD-HHMMSS.sql.gz --dry-run
#   진짜 복원:   docker exec sdeboard-backup /backup/restore.sh /backups/sdeboard-YYYYMMDD-HHMMSS.sql.gz --yes
#
# --dry-run 은 `sdeboard_restore_test` 라는 **딴 DB** 에 넣어 표·행 수만 보여준다.
#            운영 데이터는 건드리지 않는다. 평소 점검은 이걸로 한다.
# --yes     는 운영 DB(sdeboard)를 **그 시점으로 되돌린다.** 그 뒤 들어온 변경은 사라진다.
#            ⚠️ 실행 전에 백엔드를 멈춰라: docker compose stop backend
# ─────────────────────────────────────────────────────────────────────────────
set -eu

FILE="${1:-}"
MODE="${2:---dry-run}"
DB_HOST="${DB_HOST:-mariadb}"
DB_NAME="${DB_NAME:-sdeboard}"
DB_USER="${DB_USER:-sdeboard}"
DB_PASS="${DB_PASS:-sdeboard}"
TEST_DB="${TEST_DB:-sdeboard_restore_test}"
# 연습 복원은 **딴 DB 를 만들었다 지우므로** 앱 계정 권한으로는 안 된다(GRANT 가 sdeboard 한 곳뿐이다).
# 그래서 이 경로에서만 root 를 쓴다. ⚠️ 진짜 복원(--yes)은 앱 계정 그대로다 —
# 백업 컨테이너는 어차피 DB 전체를 읽으니 새로 넓어지는 범위는 없다.
DB_ROOT_PASS="${DB_ROOT_PASS:-}"

[ -n "$FILE" ] && [ -f "$FILE" ] || { echo "쓸 백업 파일을 지정하세요. 목록:"; ls -1t /backups/sdeboard-*.sql.gz 2>/dev/null | head; exit 1; }
gzip -t "$FILE" || { echo "❌ 압축이 깨진 파일입니다: $FILE"; exit 1; }

run_sql()      { mariadb -h "$DB_HOST" -u "$DB_USER" -p"$DB_PASS" --default-character-set=utf8mb4 "$@"; }
run_sql_root() { mariadb -h "$DB_HOST" -u root -p"$DB_ROOT_PASS" --default-character-set=utf8mb4 "$@"; }

if [ "$MODE" = "--yes" ]; then
  echo "⚠️  운영 DB($DB_NAME)를 $(basename "$FILE") 시점으로 되돌립니다."
  gzip -dc "$FILE" | run_sql "$DB_NAME"
  echo "✅ 복원 완료 — 백엔드를 다시 올리세요: docker compose start backend"
  exit 0
fi

# ── 연습: 딴 DB 에 넣어보고 표·행 수를 보여준다. 운영은 그대로다.
[ -n "$DB_ROOT_PASS" ] || { echo "❌ 연습 복원에는 DB_ROOT_PASS 가 필요합니다(docker-compose 의 backup 서비스에 있습니다)."; exit 1; }

echo "🧪 연습 복원 → $TEST_DB (운영 DB 는 건드리지 않습니다)"
run_sql_root -e "DROP DATABASE IF EXISTS \`$TEST_DB\`; CREATE DATABASE \`$TEST_DB\` DEFAULT CHARSET utf8mb4;"
gzip -dc "$FILE" | run_sql_root "$TEST_DB"

echo "── 복원된 표와 행 수"
for t in app_user sde_assignment team_corp request_note request_note_revision note_read \
         itsm_request request_owner request_status_history attachment_ref sync_log; do
  n=$(run_sql_root -N -B -e "SELECT COUNT(*) FROM \`$t\`" "$TEST_DB" 2>/dev/null || echo "-")
  printf '  %-24s %s\n' "$t" "$n"
done

run_sql_root -e "DROP DATABASE \`$TEST_DB\`;"
echo "✅ 연습 복원 성공 — 이 백업은 되살아난다(연습 DB 는 지웠습니다)."
