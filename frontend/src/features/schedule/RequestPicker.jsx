import { useEffect, useState } from 'react'
import Icon from '../../ui/Icon.jsx'
import EmptyState from '../../ui/EmptyState.jsx'
import { api } from '../../api/endpoints.js'
import { useSession } from '../../session/SessionContext.jsx'
import { matchesQuery } from '../../domain/request.js'

/**
 * 캘린더 화면에서 **새 일정을 등록할 요청**을 고르는 팝업 (`UR-260922-1`, §8-9).
 *
 * 스케줄은 항상 특정 요청에 딸린 데이터라, 빈 칸에서 바로 캘린더에 일정을 "그릴" 수는 없다 —
 * 먼저 어느 요청의 일정인지 골라야 한다. 후보는 **대기(WAITING)** 상태뿐이다(그 밖의 상태는
 * `ScheduleService.create` 가 서버에서 막는다 — 작업중은 이미 일정이 있고, 완료·추적불가는
 * 새로 잡을 이유가 없거나 먼저 확정부터 해야 한다).
 */
export default function RequestPicker({ onPick, onClose }) {
  const { run } = useSession()
  const [rows, setRows] = useState(null)
  const [q, setQ] = useState('')

  useEffect(() => {
    let alive = true
    run(() => api.requests('WAITING'), { silent: true })
      .then(d => { if (alive) setRows(d) })
      .catch(() => { if (alive) setRows([]) })
    return () => { alive = false }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const filtered = (rows || []).filter(r => matchesQuery(r, q))

  return (
    <div className="modal-back" onMouseDown={(e) => { if (e.target === e.currentTarget) onClose() }}>
      <div className="modal sched" role="dialog" aria-label="일정을 등록할 요청 선택">
        <div className="hd">
          <div className="hd-l"><h3>일정 등록</h3><span className="cnt">대기 중인 요청에서 고르세요</span></div>
          <button type="button" className="modal-x" onClick={onClose} aria-label="닫기">×</button>
        </div>
        <div className="sched-body">
          <div className="field">
            <input placeholder="요청번호 · 제목 · 요청자로 검색" value={q}
              onChange={e => setQ(e.target.value)} autoFocus />
          </div>
          {!rows
            ? <p className="att-empty">불러오는 중…</p>
            : !filtered.length
              ? <EmptyState>{q ? '검색 결과가 없습니다.' : '등록 가능한(대기 상태) 요청이 없습니다.'}</EmptyState>
              : <ul className="att-list" style={{ maxHeight: 340, overflowY: 'auto' }}>
                {filtered.map(r => (
                  <li className="att" key={r.reqNo}>
                    <button type="button" className="att-link" onClick={() => onPick(r)}>
                      <Icon name="file" />
                      <span className="an">{r.title}</span>
                      <span className="as mono">{r.reqNo} · {r.reqCompNm || r.dept}</span>
                    </button>
                  </li>
                ))}
              </ul>}
        </div>
      </div>
    </div>
  )
}
