// 백엔드 API 목록. 전송·인증은 client.js 가 맡는다.
// 모든 함수는 마지막 인자로 AbortSignal 을 받을 수 있다(화면이 떠나면 취소).

import { get, post, put, patch, del, download, postForm } from './client.js'

/** 기간 쿼리스트링. 빈 값은 아예 보내지 않는다(서버가 null = 경계 없음으로 읽는다). */
function period(from, to) {
  return (from ? `&from=${from}` : '') + (to ? `&to=${to}` : '')
}

export const api = {
  // 인증
  signup: (form) => post('/api/auth/signup', form),
  login: (itsmUsername, itsmPassword) => post('/api/auth/login', { itsmUsername, itsmPassword }),
  logout: () => post('/api/auth/logout'),
  meta: (signal) => get('/api/auth/meta', signal),
  me: (signal) => get('/api/auth/me', signal),
  
  // 마이페이지 — 서버가 leaderRank 하나만 받는다(팀·법인은 조회 범위라 열지 않는다)
  updateMe: (leaderRank) => patch('/api/auth/me', { leaderRank }),

  // 요청
  requests: (filter, signal) => get(`/api/requests?filter=${filter}`, signal),
  detail: (reqNo, signal) => get(`/api/requests/${encodeURIComponent(reqNo)}`, signal),
  stats: (signal) => get('/api/stats', signal),
  timeline: (limit = 8, signal) => get(`/api/timeline?limit=${limit}`, signal),
  
  // 작업 완료 목록 — 완료일(= 우리 관측 시각) 기준 기간 조회 + 법인별·담당자별 집계
  doneList: (from = '', to = '', signal) => get(`/api/requests/done?_=1${period(from, to)}`, signal),
  
  // 화면에서 보고 있는 그대로(상태 필터 + 검색어) XLSX 내보내기.
  // 미할당이면 '우선순위' 빈 열, 완료 목록(COMPLETED)이면 '완료일' 열이 붙는다.
  exportRequests: (filter, q = '', from = '', to = '') =>
    download(`/api/requests/export?filter=${encodeURIComponent(filter)}&q=${encodeURIComponent(q)}`
      + period(from, to), '요청목록.xlsx'),

  // 요청건 코멘트 — SME↔리더 / SME↔SDE 두 축. **내가 볼 수 있는 채널만** 실려 온다(서버가 거른다).
  comments: (reqNo, signal) => get(`/api/requests/${encodeURIComponent(reqNo)}/comments`, signal),
  writeComment: (reqNo, channel, body) =>
    post(`/api/requests/${encodeURIComponent(reqNo)}/comments`, { channel, body }),
  // ⚠️ 읽음은 **조회가 아니라 이 호출**이 찍는다 — 조회에서 찍으면 열지도 않은 탭의 배지까지 꺼진다
  readComments: (reqNo, channel) =>
    post(`/api/requests/${encodeURIComponent(reqNo)}/comments/read?channel=${encodeURIComponent(channel)}`),

  // 요청 분석 노트 — 본문은 HTML 이 아니라 Quill Delta(JSON 문자열)다.
  // 저장은 둘이다: mode='DRAFT'(임시저장 — 공유본은 그대로) / 'PUBLISH'(공유 — 이력이 한 행 쌓인다)
  note: (reqNo, signal) => get(`/api/requests/${encodeURIComponent(reqNo)}/note`, signal),
  saveNote: (reqNo, bodyDelta, expectedUpdatedAt, mode = 'PUBLISH', publishMemo = null) =>
    put(`/api/requests/${encodeURIComponent(reqNo)}/note`, { bodyDelta, expectedUpdatedAt, mode, publishMemo }),
  // 초안 폐기 — 편집 상태에서 빠져나오는 유일한 문. 공유본은 건드리지 않는다
  discardNoteDraft: (reqNo) => del(`/api/requests/${encodeURIComponent(reqNo)}/note/draft`),
  // 공유 이력 — 목록(팝업 왼쪽)에는 본문이 없다. 본문은 한 건씩 따로 읽는다(팝업 오른쪽)
  noteRevisions: (reqNo, signal) => get(`/api/requests/${encodeURIComponent(reqNo)}/note/revisions`, signal),
  noteRevision: (reqNo, seq, signal) =>
    get(`/api/requests/${encodeURIComponent(reqNo)}/note/revisions/${seq}`, signal),

  // SDE 인력풀 — 법인 x 차수 배정표. 쓰기는 리더만(서버가 검사한다)
  pool: (signal) => get('/api/sde/pool', signal),
  assignPool: (corpNm, systemNm, tier, userId) => put('/api/sde/pool', { corpNm, systemNm, tier, userId }),
  
  // 담당 법인 줄 — 팀마다 다르다. 추가는 후보 선택 또는 직접 입력, 삭제는 배정이 비어 있을 때만
  // 줄 = 법인, 또는 법인 아래 시스템(2026-09-10). systemNm 이 없으면 법인담당SDE 줄이다.
  // ⚠️ 이름을 경로가 아니라 쿼리로 보낸다 — `FNC시스템 / 하루` 처럼 `/` 가 든 이름이 있다.
  // 법인담당SDE 는 차수가 없다 — 사람만 넣고 뺀다(2026-09-10)
  addPoolLead: (corpNm, userId) => post('/api/sde/pool/leads', { corpNm, userId }),
  removePoolLead: (corpNm, userId) => del('/api/sde/pool/leads?corpNm=' + encodeURIComponent(corpNm)
    + '&userId=' + userId),
  addPoolRow: (corpNm, systemNm) => post('/api/sde/pool/rows', { corpNm, systemNm }),
  removePoolRow: (corpNm, systemNm) => del('/api/sde/pool/rows?corpNm=' + encodeURIComponent(corpNm)
    + (systemNm ? '&systemNm=' + encodeURIComponent(systemNm) : '')),

  downloadUrl: (id) => `/api/attachments/${id}/download`,
  runSync: () => post('/api/sync/run'),

  // 처리 일정 (`UR-260922-1`) — 담당자 표시는 안 바꾼다. 요청 상태(대기↔작업중) 갱신 + 캘린더용.
  calendar: (from, to, signal) => get(`/api/schedules?from=${from}&to=${to}`, signal),
  scheduleAssignees: (signal) => get('/api/schedules/assignees', signal),
  createSchedule: (reqNo, start, end, assigneeId) =>
    post('/api/schedules', { reqNo, start, end, assigneeId }),
  updateSchedule: (id, start, end, assigneeId, reason) =>
    put(`/api/schedules/${id}`, { start, end, assigneeId, reason }),
  cancelSchedule: (id, reason) => post(`/api/schedules/${id}/cancel`, { reason }),
  // 추적불가(스케줄 없이 ITSM 목록에서 사라짐) → 사람이 작업완료로 확정
  completeUntracked: (reqNo) => post(`/api/requests/${encodeURIComponent(reqNo)}/complete`),

  // SME 첨부파일 — ITSM 첨부(attachments, 위 downloadUrl)와 별개. 업로드는 SME 만(서버가 검사).
  files: (reqNo, signal) => get(`/api/requests/${encodeURIComponent(reqNo)}/files`, signal),
  uploadFile: (reqNo, file) => {
    const fd = new FormData()
    fd.append('file', file)
    return postForm(`/api/requests/${encodeURIComponent(reqNo)}/files`, fd)
  },
  downloadFile: (id, fallbackName) => download(`/api/request-files/${id}/download`, fallbackName),
  deleteFile: (id) => del(`/api/request-files/${id}`)
}
