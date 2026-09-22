// 이 보드가 매기는 4단계 상태 — 색·라벨·순서. 서버 `BoardStatus`(2026-09-22, UR-260922-1)와 짝이다.
// ITSM 11단계 파이프라인(NEW/ANAL/DEV/TEST/DEP/HOLD)은 폐기됐다 — 진행률·순번도 더는 서버가 안 준다.

export const STATUS = {
  WAITING:     { k: 'waiting',  l: '대기',    v: '--st-new',  g: '--g-new' },
  IN_PROGRESS: { k: 'progress', l: '작업중',  v: '--st-dev',  g: '--g-dev' },
  UNTRACKED:   { k: 'untracked',l: '추적불가', v: '--st-hold', g: '--g-hold' },
  DONE:        { k: 'done',     l: '작업완료', v: '--st-done', g: '--g-done' }
}

/**
 * 상태 분포·필터에 쓰는 **표시 순서**.
 * ⚠️ 서버 `BoardStatus.ORDER`(= `DashboardService.DIST_ORDER`)와 같은 순서다 —
 * 한쪽만 바꾸면 화면과 집계가 어긋난다.
 */
export const DIST_ORDER = ['WAITING', 'IN_PROGRESS', 'UNTRACKED', 'DONE']

/**
 * 진행 현황 표의 **상태 열** — `DIST_ORDER` 에서 완료만 뺀 것.
 * 표가 답하는 질문이 "지금 누가 무엇을 들고 있나" 라 끝난 일은 자리를 차지할 이유가 없고,
 * 완료 건수는 KPI 에 있다.
 * ⚠️ 서버 `DashboardService.ROW_ORDER` 와 **같아야 한다** — 한쪽만 바꾸면 열이 조용히 비거나 넘친다.
 */
export const MATRIX_ORDER = DIST_ORDER.filter(s => s !== 'DONE')

/** 완료를 뺀 건수 = 진행 현황 표의 모수. KPI 의 `total`(완료 포함)과 다른 수다. */
export function openCount(dist) {
  return (dist || []).filter(d => d.status !== 'DONE').reduce((n, d) => n + d.count, 0)
}

/** 상태별 건수 맵. `dist` 는 배열로 오므로 화면마다 이걸로 편다. */
export function distMapOf(dist) {
  const m = {}
  ;(dist || []).forEach(d => { m[d.status] = d.count })
  return m
}

/** 할당 단계 — ITSM 상태가 '공정할당요청' 이상이면 LEADER, 담당자가 실려 오면 FINAL. */
export const ASTAGE = {
  INTAKE: { l: '미할당',    d: '변경접수 · 담당 배정 전' },
  LEADER: { l: '할당 요청', d: 'SDE 배정 대기' },
  FINAL:  { l: '할당 완료', d: '담당자 배정됨' }
}
