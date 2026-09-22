import { STATUS } from '../domain/status.js'

/**
 * 상태 알약. `st` = 정규화 버킷(색), `label` = ITSM 원본 상태명(staNm).
 * **원본이 있으면 원본을 보여준다** — 화면은 ITSM 이 쓰는 말로 말해야 한다.
 */
export default function StatusPill({ st, label }) {
  if (!st || !STATUS[st]) return <span className="pill soft">{label || '-'}</span>
  return (
    <span className="pill soft" style={{ '--st': `var(${STATUS[st].v})` }} title={STATUS[st].l}>
      <span className="pd" />{label || STATUS[st].l}
    </span>
  )
}
