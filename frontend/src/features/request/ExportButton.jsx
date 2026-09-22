import { useState } from 'react'
import Icon from '../../ui/Icon.jsx'
import { api } from '../../api/endpoints.js'
import { useSession } from '../../session/SessionContext.jsx'
import { periodLabel } from '../../domain/period.js'

/**
 * 엑셀(XLSX) 내보내기 — **지금 조건**(현재 상태 필터 + 검색어)에 맞는 전체를 서버가 만든다.
 * 상태가 `미할당`일 때만 현업이 채워 돌려줄 **우선순위 빈 열**이 붙는다(이 기능의 원래 목적).
 *
 * ⚠️ **페이지와는 무관하다.** 목록에 페이징이 붙은 뒤로 "화면에 보이는 그대로" 는
 * '이 20건' 으로 읽힌다 — 받아 보고 나서야 다르다는 걸 알게 되는 종류의 오해라 문구로 못박는다.
 */
export default function ExportButton({ filter, q, from = '', to = '' }) {
  const { run } = useSession()
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState('')
  const withPri = filter === 'INTAKE'
  const withDone = filter === 'COMPLETED'

  const go = async () => {
    setBusy(true); setErr('')
    try { await run(() => api.exportRequests(filter, q, from, to), { silent: true }) }
    catch { setErr('내려받기 실패') }
    finally { setBusy(false) }
  }

  return (
    <>
      {err && <span className="x-err">{err}</span>}
      <button type="button" className="btn-xlsx" onClick={go} disabled={busy}
        title={withPri ? '미할당 목록 — 현업이 채울 「우선순위」 빈 열이 포함됩니다'
          : withDone ? `완료 목록 — 「완료일(관측)」 열이 포함됩니다 · ${periodLabel(from, to)}`
          : '지금 조건(상태·검색)에 맞는 전체를 내려받습니다 — 보고 있는 페이지와 무관합니다'}>
        <Icon name="excel" />{busy ? '만드는 중…' : '엑셀 다운로드'}
        {withPri && <span className="bx-tag">우선순위 포함</span>}
        {withDone && <span className="bx-tag">완료일 포함</span>}
      </button>
    </>
  )
}
