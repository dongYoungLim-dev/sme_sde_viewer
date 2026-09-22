import StatusPill from '../../ui/StatusPill.jsx'
import { dueText } from '../../domain/request.js'

/**
 * 진행 현황 칸 — ITSM 원본 상태 + 기한. 목록/대시보드 공통.
 * ⚠️ 2026-09-22(`UR-260922-1`) — ITSM 11단계 파이프라인 폐기로 서버가 진행률·순번(`progress`/`flowSeq`/
 * `flowTotal`)을 더는 주지 않는다. 예전엔 여기서 그 값들로 막대·퍼센트를 그렸다 — 지웠다.
 */
export default function ProgressCell({ r }) {
  return (
    <div className="pcell">
      <div className="pc-top">
        <StatusPill st={r.workStatus} label={r.itsmStaNm} />
      </div>
      <div className={'pc-due ' + (r.late ? 'late' : '')}>{dueText(r)}</div>
    </div>
  )
}
