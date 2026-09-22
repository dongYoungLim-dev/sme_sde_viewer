// 표시용 포맷 — **업무 판정은 여기 두지 않는다**(그건 request.js).

/**
 * 서버가 준 시각 문자열을 Date 로 바꾼다.
 *
 * ⚠️ 서버는 `LocalDateTime` 을 시간대 없이("2026-09-09T15:44:09") 준다.
 * 그대로 `new Date(...)` 하면 **브라우저 시간대로 해석**되므로, 해외·다른 TZ 에서 열면
 * 같은 값이 다른 시각으로 보인다. 이 시스템의 시각 기준은 서버와 같은 **서울**이다
 * (백엔드 `AppTime`). 그래서 시간대 표시가 없으면 여기서 +09:00 을 붙인다.
 */
export function parseServerTime(iso) {
  if (!iso) return null
  const hasZone = /(?:Z|[+-]\d{2}:?\d{2})$/.test(iso)
  const d = new Date(hasZone ? iso : `${iso}+09:00`)
  return isNaN(d.getTime()) ? null : d
}

const COLORS = ['#7928ca', '#2152ff', '#21aefd', '#f5a623', '#17ad37', '#ea4b60', '#5b7aa8']

/** 사람마다 같은 색이 나오게 하는 해시. 이름이 없으면 회색. */
export function colorFor(perId) {
  if (!perId) return '#8b96ab'
  let h = 0
  for (let i = 0; i < perId.length; i++) h = (h * 31 + perId.charCodeAt(i)) % 997
  return COLORS[h % COLORS.length]
}

export function fmtMD(iso) {
  if (!iso) return '-'
  const [, m, d] = iso.split('-')
  return `${Number(m)}/${Number(d)}`
}

export function fmtYMD(iso) {
  if (!iso) return '-'
  return iso.replaceAll('-', '.')
}

export function fmtDT(iso) {
  const t = parseServerTime(iso)
  if (!t) return ''
  const p = (n) => String(n).padStart(2, '0')
  return `${t.getFullYear()}.${p(t.getMonth() + 1)}.${p(t.getDate())} ${p(t.getHours())}:${p(t.getMinutes())}`
}

/** 오늘부터 그 날짜까지 남은 일수. 음수면 지난 것. */
export function daysTo(iso) {
  if (!iso) return null
  const due = new Date(iso + 'T00:00:00')
  const now = new Date(); now.setHours(0, 0, 0, 0)
  return Math.round((due - now) / 86400000)
}

export function ago(iso) {
  const t = parseServerTime(iso)
  if (!t) return ''
  const min = Math.round((Date.now() - t.getTime()) / 60000)
  if (min < 1) return '방금'
  if (min < 60) return `${min}분 전`
  const hr = Math.round(min / 60)
  if (hr < 24) return `${hr}시간 전`
  const day = Math.round(hr / 24)
  return day === 1 ? '어제' : `${day}일 전`
}

/**
 * 서버가 준 시각을 시:분:초 로 — 상단 '동기화' 표시용.
 * ⚠️ 값이 없으면 `--:--:--` 다. **브라우저 시계로 대신 채우지 않는다** — 그러면 서버가 한 번도
 * 동기화하지 않았는데도 방금 한 것처럼 보인다(2026-09-10 사용자 지적의 원인이 정확히 이것이었다).
 */
export function clockOf(iso) {
  const d = parseServerTime(iso)
  return d ? clockText(d) : '--:--:--'
}

/** 시:분:초. */
export function clockText(d = new Date()) {
  const p = (n) => String(n).padStart(2, '0')
  return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

// 요청자 문자열 "박지은(park Ji Eun)" → 괄호 앞 이름
export function shortName(s) {
  if (!s) return '-'
  const i = s.indexOf('(')
  return (i > 0 ? s.slice(0, i) : s).trim()
}

/**
 * 서버 `LocalDateTime`("2026-09-25T14:00:00") ↔ `<input type="datetime-local">` 값("2026-09-25T14:00").
 * 서버는 시간대 없이 서울 시각을 그대로 준다(`AppTime`) — 브라우저 로컬 변환을 거치지 않고 문자열째 자른다/붙인다.
 */
export function toInputDT(iso) {
  return iso ? iso.slice(0, 16) : ''
}
export function toApiDT(inputValue) {
  if (!inputValue) return null
  return inputValue.length === 16 ? inputValue + ':00' : inputValue
}
