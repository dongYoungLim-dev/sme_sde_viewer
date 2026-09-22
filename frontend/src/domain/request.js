// 요청 한 건에 대한 **업무 판정**. 포맷(format.js)·상수(status.js)와 섞지 않는다.

import { fmtMD, daysTo } from './format.js'

/**
 * 상단 검색이 훑는 필드 — **여기가 유일한 정의다.**
 *
 * ⚠️ 서버 `ExportService.matchesQuery` 가 **같은 목록**을 갖는다(엑셀은 서버가 만든다).
 * 한쪽만 늘리면 "화면엔 나오는데 엑셀엔 없다"가 된다 — 고칠 때 둘을 함께 본다.
 * (예전에는 이 배열이 대시보드·완료목록에 복붙으로 두 벌 더 있었다)
 */
export const SEARCH_FIELDS = [
  'reqCompNm', 'dept', 'reqNo', 'title', 'requester', 'requesterName', 'assigneeName', 'itsmStaNm'
]

/** 검색어 하나가 위 필드 중 아무 곳에나 걸리면 통과. 대소문자 무시. */
export function matchesQuery(row, needle) {
  if (!needle) return true
  const n = String(needle).trim().toLowerCase()
  if (!n) return true
  return SEARCH_FIELDS.some(k => {
    const v = row[k]
    return v && String(v).toLowerCase().includes(n)
  })
}

export function filterByQuery(rows, needle) {
  if (!needle || !needle.trim()) return rows
  return rows.filter(r => matchesQuery(r, needle))
}

// 처리 유형 라벨. ITSM 목록 응답만으로는 서비스요청 처리/직접처리를 구분할 수 없어 단정하지 않는다.
export function workTypeLabel(r, long = false) {
  if (r.workType === 'processing') return long ? '서비스요청 처리상세' : '서비스요청 처리'
  if (r.workType === 'direct') return long ? '직접처리 상세' : '직접처리'
  return long ? '처리유형 미확인' : '유형 미확인'
}

/**
 * 'ITSM 할 일에서 내려감' 의 뜻은 하나가 아니다.
 * `inTodo` 는 **그 요청의 전체 소유자** 기준이라(2026-09-08), false 는 "우리가 아는 누구의
 * 할 일에도 없다" 는 뜻이다. **완료**일 수도 있고 **추적불가**(스케줄 없이 사라짐, `UR-260922-1`)
 * 일 수도 있다. 둘을 같은 말로 보여주면 후자가 완료로 오인된다.
 *
 * ⚠️ 2026-09-22 — 4단계 상태 도입 뒤로는 `!inTodo && workStatus !== 'DONE'` 이면 **항상**
 * `workStatus === 'UNTRACKED'` 다(`SyncService.deactivateMissingOwners` 가 둘을 같은 트랜잭션에서
 * 같이 바꾼다). 그래서 라벨을 `StatusPill`·KPI 와 같은 말(**추적불가**)로 맞췄다 — 예전 `추적 중단`은
 * 같은 뜻인데 다른 단어라 한 행 안에서 서로 다른 말이 나란히 뜨는 문제가 있었다.
 */
export function todoTag(r) {
  if (r.inTodo) return null
  if (r.workStatus === 'DONE') {
    return r.completedManually
      ? { l: '작업완료 (수동 확정)', t: '추적불가 상태였던 건을 사람이 작업완료로 확정했습니다', k: 'done' }
      : { l: 'ITSM 할 일 종료', t: '완료 확인되어 할 일에서 내려갔습니다', k: 'done' }
  }
  return { l: '추적불가', t: '스케줄 없이 ITSM 목록에서 사라졌습니다 — 확인이 필요합니다(일정 버튼에서 작업완료로 확정 가능)', k: 'lost' }
}

// 기한 표기 — 목록의 '진행 현황' 칸 두 번째 줄
export function dueText(r) {
  if (!r.dueDate) return r.inTodo ? '기한 없음' : (todoTag(r)?.l || '할 일에서 내려감')
  const d = daysTo(r.dueDate)
  if (r.workStatus === 'DONE') return `기한 ${fmtMD(r.dueDate)} · 완료`
  if (r.late) return `기한 ${fmtMD(r.dueDate)} · ${-d}일 지연`
  return `기한 ${fmtMD(r.dueDate)} · ${d === 0 ? '오늘 마감' : `${d}일 남음`}`
}
