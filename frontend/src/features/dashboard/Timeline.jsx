import StatusPill from '../../ui/StatusPill.jsx'
import { STATUS } from '../../domain/status.js'
import { ago } from '../../domain/format.js'

/** 최근 상태 변화 — 폴링이 감지한 것만 올라온다. */
export default function Timeline({ rows }) {
  return <div className="tl">{rows.map((e, i) => (
    <div className="tl-item" key={i}><span className="tl-dot" style={{ background: `var(${STATUS[e.toStatus]?.v})` }} />
      <div><div className="tl-t"><b>{e.actorName || '시스템'}</b> · {e.fromStatus
        ? <><StatusPill st={e.fromStatus} /> <span className="arrow">→</span> <StatusPill st={e.toStatus} label={e.toStaNm} /></>
        : <><StatusPill st={e.toStatus} label={e.toStaNm} /> 신규</>}</div>
        <div className="tl-m mono">{e.reqNo} · {ago(e.observedAt)}</div></div></div>
  ))}</div>
}
