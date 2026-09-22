// 완료 목록의 기간 계산.

import { fmtYMD } from './format.js'

/**
 * 기간 프리셋. **주는 월요일 시작**(주간보고가 그 단위다).
 * `to` 는 미래로 두지 않고 오늘까지만 잡는다 — 완료 건이 미래에 있을 수 없다.
 */
export const PERIODS = [
  ['this-week', '이번 주'], ['last-week', '지난 주'], ['this-month', '이번 달'], ['all', '전체']
]

const isoD = (x) => `${x.getFullYear()}-${String(x.getMonth() + 1).padStart(2, '0')}-${String(x.getDate()).padStart(2, '0')}`
const mondayOf = (x) => { const c = new Date(x); c.setDate(c.getDate() - ((c.getDay() + 6) % 7)); return c }

export function periodRange(key) {
  const today = new Date(); today.setHours(0, 0, 0, 0)
  if (key === 'this-week') return { from: isoD(mondayOf(today)), to: isoD(today) }
  if (key === 'last-week') {
    const s = mondayOf(today); s.setDate(s.getDate() - 7)
    const e = new Date(s); e.setDate(e.getDate() + 6)
    return { from: isoD(s), to: isoD(e) }
  }
  if (key === 'this-month') return { from: isoD(new Date(today.getFullYear(), today.getMonth(), 1)), to: isoD(today) }
  return { from: '', to: '' }        // 'all' · 'custom'
}

export function periodLabel(from, to) {
  if (!from && !to) return '전체 기간'
  return `${from ? fmtYMD(from) : '처음'} ~ ${to ? fmtYMD(to) : '오늘'}`
}
