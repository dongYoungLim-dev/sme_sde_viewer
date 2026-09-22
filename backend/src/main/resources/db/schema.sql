-- =====================================================================
-- SDE 작업현황 대시보드 — 스키마 (전체 재생성)
-- 기준: Obsidian 노트 10_DB스키마.md + 실제 ITSM 목록 API 응답(2026-09-08 확인)
-- 샘플 데이터 없음. 모든 업무 테이블은 동기화가 채운다(app_user 제외).
-- =====================================================================

DROP TABLE IF EXISTS request_attachment;
DROP TABLE IF EXISTS request_schedule;
DROP TABLE IF EXISTS request_owner;
DROP TABLE IF EXISTS request_status_history;
DROP TABLE IF EXISTS attachment_ref;
DROP TABLE IF EXISTS request_detail;
DROP TABLE IF EXISTS comment_read;
DROP TABLE IF EXISTS request_comment;
DROP TABLE IF EXISTS note_read;
DROP TABLE IF EXISTS request_note_revision;
DROP TABLE IF EXISTS request_note;
DROP TABLE IF EXISTS team_system;
DROP TABLE IF EXISTS team_corp;
DROP TABLE IF EXISTS sde_assignment;
DROP TABLE IF EXISTS itsm_request;
DROP TABLE IF EXISTS sync_log;
DROP TABLE IF EXISTS app_user;

-- ============ 사용자 / 권한 (유일한 편집 대상: 마스터) ============
-- 로그인 = ITSM 계정 인증. ITSM 비밀번호는 어디에도 저장하지 않는다(세션 메모리 전용).
CREATE TABLE app_user (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  login_id      VARCHAR(64)  NOT NULL UNIQUE, -- = ITSM 계정(perId). 예: p_meta.ldy6740
  name          VARCHAR(100) NOT NULL,
  email         VARCHAR(255),
  itsm_per_id   VARCHAR(64),                  -- ITSM perId (login_id 와 동일값)
  itsm_comp_cd  VARCHAR(10),                  -- ITSM 토큰 payload 의 compCd (가입 시 자동 취득)
  role          VARCHAR(20)  NOT NULL,        -- SME / SDE_LEADER / SDE
  -- SDE 리더의 정/부 구분. MAIN(정) / SUB(부), 리더가 아니면 NULL.
  -- ⚠️ 권한 차이가 없는 값이라 일부러 role 에 섞지 않았다 — role 문자열 비교가 코드 곳곳에 있어
  --    값을 늘리면 조용히 리더로 인식되지 않는 곳이 생긴다.
  leader_rank   VARCHAR(10),
  corp_cd       VARCHAR(10),                  -- SME 소속 법인코드 (기본 = itsm_comp_cd)
  corp_nm       VARCHAR(100),                 -- SME 소속 법인명
  team          VARCHAR(100),                 -- SDE 리더 소속 팀명
  link_status   VARCHAR(20)  NOT NULL DEFAULT 'LINKED',  -- LINKED / AUTH_FAILED
  last_sync_at  DATETIME,
  active        TINYINT(1)   NOT NULL DEFAULT 1,
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_user_role (role),
  KEY idx_user_itsm (itsm_per_id),
  KEY idx_user_corp (corp_cd),
  KEY idx_user_team (team)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============ ITSM 요청 미러 (폴링 결과, 읽기 전용) ============
-- 주석의 (확정) 은 my-to-do-request-operation 실제 응답으로 확인된 필드.
CREATE TABLE itsm_request (
  id              BIGINT PRIMARY KEY AUTO_INCREMENT,
  req_no          VARCHAR(32)  NOT NULL UNIQUE,  -- (확정) reqNo    예: CSD260828000085
  title           VARCHAR(500),                  -- (확정) reqTitle
  requester       VARCHAR(200),                  -- (확정) perNm    예: 박지은(park Ji Eun)
  req_comp_nm     VARCHAR(100),                  -- (확정) reqCompNm 요청 회사
  req_cat_nm      VARCHAR(100),                  -- (확정) reqCatNm  요청 분류(업무시스템 등)
  comp_cd         VARCHAR(10),                   -- 토큰의 compCd (목록 응답엔 없음)
  body            TEXT,                          -- 요청 원문 — 상세 API 확정 후 채움(목록엔 없음)
  req_typ_cd      VARCHAR(10),                   -- (확정) reqTypCd 예: 00365
  req_typ_nm      VARCHAR(50),                   -- (확정) reqTypNm 예: 변경 / 배포메인 상태
  trf_per_id      VARCHAR(100),                  -- (확정) trfPerId — ID가 아니라 '이름' 문자열로 옴
  leader_per_id   VARCHAR(64),                   -- 미제공 — 상세 API 확인 대기
  leader_name     VARCHAR(100),                  -- 미제공
  assignee_per_id VARCHAR(64),                   -- 미제공(목록엔 ID 없음)
  assignee_name   VARCHAR(100),                  -- trfPerId 값을 담당자 '이름'으로 사용
  assign_stage    VARCHAR(10),                   -- 파생: INTAKE / FINAL (LEADER는 필드 부재로 미사용)
  itsm_sta_cd     VARCHAR(10),                   -- (확정) staCd 예: 00566
  itsm_sta_nm     VARCHAR(100),                  -- (확정) staNm 예: 변경접수 / 배포접수 및 준비
  work_status     VARCHAR(20),                   -- 보드 4단계: WAITING/IN_PROGRESS/DONE/UNTRACKED (스케줄 유무 + To-Do 잔존)
  work_type       VARCHAR(20),                   -- processing / direct (workNo로 판별)
  work_no         INT,                           -- (확정) workNo
  req_dt          DATETIME,                      -- (확정) reqDt      epoch millis → KST
  due_date        DATETIME,                      -- (확정) defDueDate epoch millis → KST
  last_changed_at DATETIME,                      -- 미러 값이 마지막으로 바뀐 시각(우리 기준)
  completed_manually_by BIGINT,                  -- UNTRACKED → DONE 을 사람이 확정한 경우
  completed_manually_at DATETIME,
  done_at         DATETIME,                      -- ⚠️ ITSM 이 완료 '시각'을 주지 않는다 → **우리가 처음 완료를 관측한 시각**
                                                 --    (DONE 전이 관측 또는 To-Do 에서 내려간 시각. 폴링 주기만큼 늦다)
  raw_json        JSON,                          -- 목록 응답 원본 보존
  synced_at       DATETIME     NOT NULL,
  created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_req_status (work_status),
  KEY idx_req_sta_cd (itsm_sta_cd),
  KEY idx_req_assignee (assignee_name),
  KEY idx_req_leader (leader_per_id),
  KEY idx_req_astage (assign_stage),
  KEY idx_req_dt (req_dt),
  KEY idx_req_done_at (done_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============ 요청 분석 노트 (요구사항 정의서 — ITSM 에 없는 데이터, 우리가 원본) ============
-- SME 가 분석해 적고, 할당받은 SDE 가 읽는다. ⚠️ 유실되면 복구할 곳이 없다.
-- body_delta 는 Quill Delta(JSON) 다. **HTML 을 저장하지 않는다** —
-- 마크업을 저장하면 그리는 순간 주입 경로가 생기고, 서버 HTML 새니타이저가 필요해진다.
CREATE TABLE request_note (
  id         BIGINT PRIMARY KEY AUTO_INCREMENT,
  req_no     VARCHAR(32) NOT NULL,
  body_delta MEDIUMTEXT,                     -- Quill Delta(JSON) — 저장 원본
  body_text  MEDIUMTEXT,                     -- 평문 파생값 (검색·미리보기·내보내기용)
  -- 2026-09-09 · 임시저장(UR-260909-6). ⚠️ 본문이 **두 벌**인 것이 이 기능의 핵심이다 —
  -- 한 행에 하나뿐이면 공유한 노트를 고치다 임시저장하는 순간 SDE 가 보던 내용이 사라진다.
  draft_delta      MEDIUMTEXT,               -- 아직 공유하지 않은 작업본 (편집 권한자에게만 응답에 실린다)
  draft_updated_at DATETIME,
  published_at     DATETIME,                 -- 마지막 공유 시각 = 읽는 쪽에 보여줄 시각
  author_id  BIGINT      NOT NULL,           -- 마지막으로 저장한 사람
  updated_at DATETIME    NOT NULL,           -- 낙관적 잠금 키로도 쓴다 (초안 저장도 올린다)
  created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_note_req (req_no)            -- 요청 1건 : 노트 1개
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============ 노트 공유 이력 (ITSM 에 없는 데이터 — 우리가 원본) ============
-- ⚠️ **공유할 때만** 한 행 쌓인다. 임시저장은 이력을 남기지 않는다 → 요청당 두세 행이다.
-- 공유본을 덮어쓰면 그 내용으로 작업 중이던 SDE 가 혼란스럽다(사용자 지적 2026-09-09).
-- append-only 라 덮어쓰기로 사라질 위험이 오히려 준다. 되돌리기·diff UI 는 1단계에서 뺐다.
CREATE TABLE request_note_revision (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  req_no       VARCHAR(32) NOT NULL,
  seq          INT         NOT NULL,         -- 몇 차 공유인가 (요청 안에서 1부터)
  body_delta   MEDIUMTEXT,                   -- 그때 공유한 본문 (Quill Delta)
  body_text    MEDIUMTEXT,                   -- 이력 목록의 미리보기가 여기서 나온다
  publish_memo VARCHAR(300),                 -- 무엇을 바꿨는지 한 줄. 2차 공유부터 필수
  author_id    BIGINT      NOT NULL,
  published_at DATETIME    NOT NULL,
  created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_rev_req_seq (req_no, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============ 노트 읽음 시각 ============
-- 목록의 `노트 갱신` 배지가 이 표 하나로 산다. "갱신됨" 은 **보는 사람마다 다른 사실**이라
-- "최근 N시간 안에 공유됐으면 갱신" 같은 근사치로는 방금 읽은 사람에게도 배지가 뜬다.
-- 기록 시점 = 노트를 실제로 연 순간 + 자기가 공유한 순간. 목록을 스친 것은 읽은 것이 아니다.
CREATE TABLE note_read (
  id      BIGINT PRIMARY KEY AUTO_INCREMENT,
  req_no  VARCHAR(32) NOT NULL,
  user_id BIGINT      NOT NULL,
  read_at DATETIME    NOT NULL,
  UNIQUE KEY uk_nr_req_user (req_no, user_id),
  KEY idx_nr_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============ 요청건 코멘트 (ITSM 에 없는 데이터 — 우리가 원본) ============
-- SME↔리더 / SME↔SDE **두 축**이다(사용자 결정 2026-09-10) —
--   "SDE리더와 SME 간에 댓글을 SDE 가 볼 필요가 없고, SDE와 SME 간에 댓글을 SDE 리더가 볼 필요가 없다"
-- 대화방을 안 만든 이유: 커지는 것은 채팅이 요구하는 것들(방·참여자·실시간·푸시)이지 양방향 자체가 아니다.
-- **append-only** — 수정·삭제가 없어서 초안·이력·동시편집 표가 따로 필요 없다(노트는 그 셋 때문에 표가 셋이다).
-- author_role 은 **쓴 시점의 역할**을 찍어 둔다. 지금 역할로 되짚으면 승격·이동 후 과거가 조용히 달라진다.
CREATE TABLE request_comment (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  req_no      VARCHAR(32)   NOT NULL,
  channel     VARCHAR(16)   NOT NULL,        -- SME_LEAD | SME_SDE (역할 쌍이다, 사람 쌍이 아니다)
  author_id   BIGINT        NOT NULL,
  author_role VARCHAR(20)   NOT NULL,
  body        VARCHAR(2000) NOT NULL,        -- 평문. Delta·HTML 이 아니라 주입 경로가 없다
  created_at  DATETIME      NOT NULL,
  KEY idx_rc_req_ch (req_no, channel, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============ 코멘트 읽음 시각 ============
-- 목록의 `새 댓글` 배지. note_read 와 같은 이유로 존재한다(새 글은 보는 사람마다 다른 사실이다).
-- ⚠️ **채널까지 키에 넣는다.** 요청 단위로만 찍으면 한쪽 탭을 열었을 때 다른 쪽 배지까지 같이 꺼진다.
CREATE TABLE comment_read (
  id      BIGINT PRIMARY KEY AUTO_INCREMENT,
  req_no  VARCHAR(32) NOT NULL,
  user_id BIGINT      NOT NULL,
  channel VARCHAR(16) NOT NULL,
  read_at DATETIME    NOT NULL,
  UNIQUE KEY uk_cr_req_user_ch (req_no, user_id, channel),
  KEY idx_cr_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============ SDE 인력풀 배정 (ITSM 에 없는 데이터 — 우리가 원본) ============
-- ⚠️ 이 표는 ITSM 어디에도 없다. 유실되면 복구할 곳이 없는 첫 데이터다.
-- 유지 주체는 SDE 리더(사용자 결정 2026-09-08) — 지금 엑셀로 관리하던 것을 옮긴 것.
-- corp_nm 이 코드가 아니라 문자열인 이유: 목록 응답에 compCd 가 없어 reqCompNm 문자열에 맞춰야 한다.
CREATE TABLE sde_assignment (
  id         BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id    BIGINT       NOT NULL,          -- app_user.id (role=SDE)
  corp_nm    VARCHAR(100) NOT NULL,          -- 법인명 = itsm_request.req_comp_nm
  -- 담당 시스템 (2026-09-10 UR-260910-1). NULL = "법인담당SDE" — 시스템을 나누기 전의 배정이 여기 남는다.
  -- ⚠️ NULL 을 빈 문자열로 바꾸지 말 것. 목록의 차수 배지는 **NULL 행만** 읽는다(어느 시스템 건인지 ITSM 이 안 준다).
  system_nm  VARCHAR(100) NULL,
  -- 담당 차수 1~5 (sde.tiers). ⚠️ NULL 허용 — **법인담당SDE 는 차수가 없다**(사용자 2026-09-10).
  -- 차수는 시스템 줄에만 있다. system_nm IS NULL 인 행 = 법인담당SDE 명단(인원 제한 없음).
  tier       INT          NULL,
  updated_by BIGINT,                         -- 마지막으로 고친 리더
  updated_at DATETIME     NOT NULL,
  created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_sa_user (user_id),
  KEY idx_sa_corp (corp_nm, tier)
  -- ⚠️ (corp_nm, system_nm, tier) UNIQUE 를 일부러 걸지 않았다 — 차수의 의미가 확정되지 않아
  --    '한 칸에 한 명' 규칙을 서비스에서 검사한다. 확정되면 제약으로 올릴 것.
  --    (MariaDB 의 UNIQUE 는 NULL 을 서로 다른 값으로 보므로 법인담당SDE 행에는 어차피 안 걸린다)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============ 팀별 담당 법인 (인력풀 표에 세울 줄) ============
-- 관측 법인을 자동으로 전부 세우면 관련 없는 법인이 빈 줄로 자리만 차지한다(사용자 정정 2026-09-09).
-- 담당자가 없는 빈 줄도 남아야 하므로 sde_assignment 만으로는 표현할 수 없다.
CREATE TABLE team_corp (
  id         BIGINT PRIMARY KEY AUTO_INCREMENT,
  team       VARCHAR(100) NOT NULL,          -- app_user.team
  corp_nm    VARCHAR(100) NOT NULL,          -- 법인명 = itsm_request.req_comp_nm
  added_by   BIGINT,                         -- 추가한 리더
  added_at   DATETIME     NOT NULL,
  created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- ⚠️ 전역 UNIQUE = "한 법인은 한 팀"(사용자 2026-09-09).
  --    이 전제 덕에 sde_assignment 의 (corp_nm,tier) 전역 유일 규칙을 그대로 둘 수 있다.
  --    전제가 틀렸다면 다른 팀이 추가하려는 순간 거부되면서 드러난다.
  UNIQUE KEY uk_tc_corp (corp_nm),
  KEY idx_tc_team (team)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============ 법인 아래 담당 시스템 (인력풀 표에 세울 줄) ============
-- 2026-09-10 `UR-260910-1` — 차수를 법인이 아니라 **시스템**마다 정한다.
-- 담당자가 없는 빈 줄도 남아야 하므로 sde_assignment 만으로는 표현할 수 없다(team_corp 와 같은 이유).
-- ⚠️ team 컬럼이 없다. 팀 소유권은 team_corp 하나가 갖는다 — 두 곳에 적으면 조용히 어긋난다.
CREATE TABLE team_system (
  id         BIGINT PRIMARY KEY AUTO_INCREMENT,
  corp_nm    VARCHAR(100) NOT NULL,          -- team_corp.corp_nm (팀은 여기서 따라온다)
  system_nm  VARCHAR(100) NOT NULL,          -- 시스템명 (sde.systems 후보 또는 직접 입력)
  added_by   BIGINT,                         -- 추가한 리더
  added_at   DATETIME     NOT NULL,
  created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_ts_corp_sys (corp_nm, system_nm),
  KEY idx_ts_corp (corp_nm)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============ 상세 미러 (상세 API 응답 보존) ============
-- 상세 2종(processing/direct)은 아직 미검증. 테이블만 만들어 둔다.
CREATE TABLE request_detail (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  req_no        VARCHAR(32) NOT NULL,
  work_no       INT NOT NULL DEFAULT 0,
  detail_json   JSON NOT NULL,
  synced_at     DATETIME NOT NULL,
  CONSTRAINT fk_rd_req FOREIGN KEY (req_no) REFERENCES itsm_request(req_no) ON DELETE CASCADE,
  UNIQUE KEY uk_rd (req_no, work_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============ 상태/담당 변화 이력 (폴링으로 감지) ============
CREATE TABLE request_status_history (
  id             BIGINT PRIMARY KEY AUTO_INCREMENT,
  req_no         VARCHAR(32) NOT NULL,
  from_status    VARCHAR(20),                   -- 정규화 상태(우리 버킷)
  to_status      VARCHAR(20),
  from_sta_nm    VARCHAR(100),                  -- ITSM 원본 상태명(변화 전)
  to_sta_nm      VARCHAR(100),                  -- ITSM 원본 상태명(변화 후)
  from_assignee  VARCHAR(100),
  to_assignee    VARCHAR(100),
  actor_per_id   VARCHAR(64),
  actor_name     VARCHAR(100),
  observed_at    DATETIME NOT NULL,
  CONSTRAINT fk_sh_req FOREIGN KEY (req_no) REFERENCES itsm_request(req_no) ON DELETE CASCADE,
  KEY idx_sh_req (req_no, observed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============ 첨부 메타 (ITSM 제공 확인 후 사용) ============
CREATE TABLE attachment_ref (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  req_no        VARCHAR(32) NOT NULL,
  itsm_file_id  VARCHAR(128),
  file_name     VARCHAR(500),
  file_size     BIGINT,
  content_type  VARCHAR(100),
  download_ref  VARCHAR(1000),
  synced_at     DATETIME NOT NULL,
  CONSTRAINT fk_af_req FOREIGN KEY (req_no) REFERENCES itsm_request(req_no) ON DELETE CASCADE,
  KEY idx_af_req (req_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============ 요청 소유자 매핑 (누구의 To-Do 로 조회됐나) ============
-- 같은 요청이 여러 사용자의 To-Do 에 동시에 나올 수 있어, 요청 본문은 1행으로 두고 소유만 매핑한다.
CREATE TABLE request_owner (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id       BIGINT      NOT NULL,
  req_no        VARCHAR(32) NOT NULL,
  first_seen    DATETIME    NOT NULL,
  last_seen     DATETIME    NOT NULL,
  active        TINYINT(1)  NOT NULL DEFAULT 1,   -- 목록에서 사라지면 0
  CONSTRAINT fk_ro_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE,
  CONSTRAINT fk_ro_req  FOREIGN KEY (req_no)  REFERENCES itsm_request(req_no) ON DELETE CASCADE,
  UNIQUE KEY uk_ro (user_id, req_no),
  KEY idx_ro_req (req_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============ 요청 처리 일정 (2026-09-22, UR-260922-1) ============
CREATE TABLE request_schedule (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  req_no      VARCHAR(32)  NOT NULL,
  assignee_id BIGINT       NOT NULL,            -- 실제 작업자 (담당자 표시를 바꾸지 않는다)
  created_by  BIGINT       NOT NULL,            -- 등록한 사람
  start_dt    DATETIME     NOT NULL,
  end_dt      DATETIME     NOT NULL,
  status      VARCHAR(12)  NOT NULL,            -- ACTIVE / REVISED / CANCELLED
  reason      VARCHAR(500),                     -- 이 일정이 끝난 이유(수정·취소 사유)
  closed_by   BIGINT,
  closed_at   DATETIME,
  prev_id     BIGINT,
  created_at  DATETIME     NOT NULL,
  KEY idx_rs_req (req_no),
  KEY idx_rs_assignee (assignee_id, status),
  KEY idx_rs_range (status, start_dt, end_dt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============ SME 첨부파일 (2026-09-22, UR-260922-1) — 메타만, 파일은 디스크 ============
CREATE TABLE request_attachment (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  req_no       VARCHAR(32)  NOT NULL,
  file_name    VARCHAR(500) NOT NULL,
  stored_name  VARCHAR(200) NOT NULL,
  content_type VARCHAR(100),
  file_size    BIGINT,
  uploaded_by  BIGINT       NOT NULL,
  uploaded_at  DATETIME     NOT NULL,
  KEY idx_ra_req (req_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============ 동기화 로그 ============
CREATE TABLE sync_log (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id       BIGINT,                        -- 어느 사용자 세션으로 돌린 동기화인지
  started_at    DATETIME NOT NULL,
  finished_at   DATETIME,
  result        VARCHAR(20),
  fetched_cnt   INT NOT NULL DEFAULT 0,
  upserted_cnt  INT NOT NULL DEFAULT 0,
  changed_cnt   INT NOT NULL DEFAULT 0,
  error_msg     TEXT,
  KEY idx_sl_started (started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
