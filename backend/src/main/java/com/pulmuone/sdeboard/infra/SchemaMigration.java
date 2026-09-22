package com.pulmuone.sdeboard.infra;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 기동 시 1회 — **이미 돌고 있는 DB 에 새 컬럼을 더한다.**
 *
 * <p>스키마는 `db/schema.sql` 로만 관리하고(`ddl-auto: none`), 그 파일은 `DB_INIT=always` 일 때만 실행된다.
 * 그런데 그 모드는 <b>DROP → CREATE</b> 라 데이터가 전부 사라진다. 컬럼 하나 늘리자고 미러를 비울 수는 없으니,
 * 운영 중인 DB 를 위한 **덧붙이기 전용(additive)** 경로가 따로 필요하다.
 *
 * <p>여기 있는 문장은 전부 <b>여러 번 실행해도 안전해야 한다</b>(MariaDB 의 {@code IF NOT EXISTS}).
 * 컬럼 삭제·타입 변경처럼 되돌릴 수 없는 것은 절대 넣지 않는다 — 그런 변경은 schema.sql 과 재생성으로 간다.
 */
@Component
@Order(0)                       // StatusBackfill(Order 1) 보다 먼저 — 백필이 새 컬럼을 쓴다
@RequiredArgsConstructor
@Slf4j
public class SchemaMigration implements ApplicationRunner {

    private final JdbcTemplate jdbc;

    /** 덧붙이기 전용 DDL. 순서대로, 몇 번을 돌려도 같은 결과여야 한다. */
    private static final List<String> ADDITIVE = List.of(
            // 2026-09-08 · 작업 완료 목록 페이지 — 완료로 처음 관측한 시각
            "ALTER TABLE itsm_request ADD COLUMN IF NOT EXISTS done_at DATETIME NULL",
            "ALTER TABLE itsm_request ADD INDEX IF NOT EXISTS idx_req_done_at (done_at)",

            // 2026-09-09 · SDE 리더 정/부 구분 (UR-260909-2)
            // ⚠️ role 을 쪼개지 않고 별도 컬럼으로 둔다 — 권한 차이가 없는 값이라 권한 축에 올리면
            //    role 문자열 비교(15곳)가 조용히 어긋난다.
            "ALTER TABLE app_user ADD COLUMN IF NOT EXISTS leader_rank VARCHAR(10) NULL",

            // 2026-09-08 · SDE 인력풀 — ITSM 에 없는 데이터라 우리가 원본을 갖는 첫 테이블
            """
            CREATE TABLE IF NOT EXISTS sde_assignment (
              id         BIGINT PRIMARY KEY AUTO_INCREMENT,
              user_id    BIGINT       NOT NULL,
              corp_nm    VARCHAR(100) NOT NULL,
              tier       INT          NOT NULL,
              updated_by BIGINT,
              updated_at DATETIME     NOT NULL,
              created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
              KEY idx_sa_user (user_id),
              KEY idx_sa_corp (corp_nm, tier)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,

            // 2026-09-09 · 팀별 담당 법인 (UR-260909-1)
            // 관측 법인을 리더 화면에 전부 자동으로 세우면 관련 없는 법인이 빈 줄로 자리만 차지한다.
            // corp_nm 전역 UNIQUE = "한 법인은 한 팀" — 이 전제가 틀리면 추가 시점에 거부로 드러난다.
            """
            CREATE TABLE IF NOT EXISTS team_corp (
              id         BIGINT PRIMARY KEY AUTO_INCREMENT,
              team       VARCHAR(100) NOT NULL,
              corp_nm    VARCHAR(100) NOT NULL,
              added_by   BIGINT,
              added_at   DATETIME     NOT NULL,
              created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
              UNIQUE KEY uk_tc_corp (corp_nm),
              KEY idx_tc_team (team)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,

            // 2026-09-10 · 담당 차수를 법인 → **시스템** 단위로 (UR-260910-1)
            // ⚠️ NULL 허용이 핵심이다. 기존 행이 전부 `system_nm IS NULL` = 법인담당SDE 이 되어
            //    백필도, 표를 비웠다 다시 채우는 일도 없다. 이 표는 ITSM 에 없어 지우면 복구할 곳이 없다.
            "ALTER TABLE sde_assignment ADD COLUMN IF NOT EXISTS system_nm VARCHAR(100) NULL",

            // 2026-09-10 · 법인담당SDE 는 **차수가 없다**(사용자 2026-09-10). 시스템 줄만 1~N차를 갖는다.
            // ⚠️ 여기 유일한 타입 변경이다. NOT NULL → NULL 은 **넓히는 쪽**이라 기존 값이 그대로 남고
            //    여러 번 돌려도 결과가 같다. 좁히는 변경(NULL → NOT NULL, 길이 축소)은 여전히 넣지 않는다.
            "ALTER TABLE sde_assignment MODIFY COLUMN tier INT NULL",

            // 2026-09-10 · 법인 아래 담당 시스템 줄 (UR-260910-1)
            // team 컬럼을 두지 않는다 — 팀 소유권은 team_corp 하나가 갖는다(두 곳에 적으면 어긋난다).
            """
            CREATE TABLE IF NOT EXISTS team_system (
              id         BIGINT PRIMARY KEY AUTO_INCREMENT,
              corp_nm    VARCHAR(100) NOT NULL,
              system_nm  VARCHAR(100) NOT NULL,
              added_by   BIGINT,
              added_at   DATETIME     NOT NULL,
              created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
              UNIQUE KEY uk_ts_corp_sys (corp_nm, system_nm),
              KEY idx_ts_corp (corp_nm)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,

            // 2026-09-08 · 요청 분석 노트(요구사항 정의서). HTML 이 아니라 Quill Delta(JSON) 를 담는다
            """
            CREATE TABLE IF NOT EXISTS request_note (
              id         BIGINT PRIMARY KEY AUTO_INCREMENT,
              req_no     VARCHAR(32) NOT NULL,
              body_delta MEDIUMTEXT,
              body_text  MEDIUMTEXT,
              author_id  BIGINT      NOT NULL,
              updated_at DATETIME    NOT NULL,
              created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
              UNIQUE KEY uk_note_req (req_no)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,

            // 2026-09-09 · 노트 임시저장 (UR-260909-6)
            // ⚠️ 본문을 **두 벌**로 둔다. 한 행에 하나뿐이면 공유한 노트를 고치다 임시저장하는 순간
            //    SDE 가 보던 내용이 사라진다. body_* = 공유본(읽는 쪽은 이것만 본다), draft_* = 작업본.
            "ALTER TABLE request_note ADD COLUMN IF NOT EXISTS draft_delta MEDIUMTEXT NULL",
            "ALTER TABLE request_note ADD COLUMN IF NOT EXISTS draft_updated_at DATETIME NULL",
            // 읽는 쪽에 보여줄 시각. updated_at 을 쓰면 초안을 저장할 때마다 SDE 화면의 시각이 바뀐다
            "ALTER TABLE request_note ADD COLUMN IF NOT EXISTS published_at DATETIME NULL",

            // 2026-09-09 · 노트 공유 이력 (UR-260909-6) — **공유할 때만** 한 행. 임시저장은 안 남긴다
            """
            CREATE TABLE IF NOT EXISTS request_note_revision (
              id           BIGINT PRIMARY KEY AUTO_INCREMENT,
              req_no       VARCHAR(32) NOT NULL,
              seq          INT         NOT NULL,
              body_delta   MEDIUMTEXT,
              body_text    MEDIUMTEXT,
              publish_memo VARCHAR(300),
              author_id    BIGINT      NOT NULL,
              published_at DATETIME    NOT NULL,
              created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
              UNIQUE KEY uk_rev_req_seq (req_no, seq)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,

            // 2026-09-09 · 노트 읽음 시각 (UR-260909-6) — 목록의 `노트 갱신` 배지가 이것 하나로 산다.
            // "갱신됨" 은 보는 사람마다 다른 사실이라, 시간 근사치로는 방금 읽은 사람에게도 배지가 뜬다.
            """
            CREATE TABLE IF NOT EXISTS note_read (
              id      BIGINT PRIMARY KEY AUTO_INCREMENT,
              req_no  VARCHAR(32) NOT NULL,
              user_id BIGINT      NOT NULL,
              read_at DATETIME    NOT NULL,
              UNIQUE KEY uk_nr_req_user (req_no, user_id),
              KEY idx_nr_user (user_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,

            // 2026-09-11 · 요청건 코멘트 — SME↔리더 / SME↔SDE 두 축.
            // append-only 다(수정·삭제 없음). 그래서 초안·이력·동시편집 표가 따로 필요 없다.
            // ⚠️ ITSM 에 없는 데이터라 이 보드가 원본이다 → 백업 대상.
            """
            CREATE TABLE IF NOT EXISTS request_comment (
              id          BIGINT PRIMARY KEY AUTO_INCREMENT,
              req_no      VARCHAR(32)   NOT NULL,
              channel     VARCHAR(16)   NOT NULL,
              author_id   BIGINT        NOT NULL,
              author_role VARCHAR(20)   NOT NULL,
              body        VARCHAR(2000) NOT NULL,
              created_at  DATETIME      NOT NULL,
              KEY idx_rc_req_ch (req_no, channel, created_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,

            // 2026-09-11 · 코멘트 읽음 시각 — 목록의 `새 댓글` 배지.
            // ⚠️ 채널까지 키에 넣는다. 요청 단위로만 찍으면 한쪽 탭을 열었을 때 다른 쪽 배지도 같이 꺼진다.
            """
            CREATE TABLE IF NOT EXISTS comment_read (
              id      BIGINT PRIMARY KEY AUTO_INCREMENT,
              req_no  VARCHAR(32) NOT NULL,
              user_id BIGINT      NOT NULL,
              channel VARCHAR(16) NOT NULL,
              read_at DATETIME    NOT NULL,
              UNIQUE KEY uk_cr_req_user_ch (req_no, user_id, channel),
              KEY idx_cr_user (user_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,

            // 2026-09-22 · 스케줄 기반 4단계 상태 (UR-260922-1)
            // `UNTRACKED → DONE` 을 사람이 확정한 기록. 자동 완료면 둘 다 NULL.
            "ALTER TABLE itsm_request ADD COLUMN IF NOT EXISTS completed_manually_by BIGINT NULL",
            "ALTER TABLE itsm_request ADD COLUMN IF NOT EXISTS completed_manually_at DATETIME NULL",

            // 2026-09-22 · 요청 처리 일정. ITSM 에 없는 데이터라 이 보드가 원본이다 → 백업 대상.
            // 끝난 일정은 지우지 않는다(REVISED/CANCELLED + reason). ACTIVE 는 요청당 하나 — 서비스에서 검사한다.
            """
            CREATE TABLE IF NOT EXISTS request_schedule (
              id          BIGINT PRIMARY KEY AUTO_INCREMENT,
              req_no      VARCHAR(32)  NOT NULL,
              assignee_id BIGINT       NOT NULL,
              created_by  BIGINT       NOT NULL,
              start_dt    DATETIME     NOT NULL,
              end_dt      DATETIME     NOT NULL,
              status      VARCHAR(12)  NOT NULL,
              reason      VARCHAR(500),
              closed_by   BIGINT,
              closed_at   DATETIME,
              prev_id     BIGINT,
              created_at  DATETIME     NOT NULL,
              KEY idx_rs_req (req_no),
              KEY idx_rs_assignee (assignee_id, status),
              KEY idx_rs_range (status, start_dt, end_dt)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,

            // 2026-09-22 · SME 첨부파일 메타. 실제 파일은 디스크(app.upload-dir) — 백업 대상에 그 폴더도 들어간다.
            // 삭제는 물리 삭제(사용자 결정)라 소프트 삭제 컬럼이 없다.
            """
            CREATE TABLE IF NOT EXISTS request_attachment (
              id           BIGINT PRIMARY KEY AUTO_INCREMENT,
              req_no       VARCHAR(32)  NOT NULL,
              file_name    VARCHAR(500) NOT NULL,
              stored_name  VARCHAR(200) NOT NULL,
              content_type VARCHAR(100),
              file_size    BIGINT,
              uploaded_by  BIGINT       NOT NULL,
              uploaded_at  DATETIME     NOT NULL,
              KEY idx_ra_req (req_no)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """
    );

    @Override
    public void run(ApplicationArguments args) {
        for (String ddl : ADDITIVE) {
            try {
                jdbc.execute(ddl);
            } catch (Exception e) {
                // 여기서 죽으면 애플리케이션이 아예 못 뜬다. 이미 반영된 상태일 수도 있으니 남기고 넘어간다.
                log.warn("스키마 덧붙이기 건너뜀: {} — {}", ddl, e.getMessage());
            }
        }
    }
}
