import Icon from '../ui/Icon.jsx'
import EmptyState from '../ui/EmptyState.jsx'
import RequestTable from '../features/request/RequestTable.jsx'
import ExportButton from '../features/request/ExportButton.jsx'
import { useDoneList } from '../hooks/useDoneList.js'
import { useSearch } from '../hooks/useSearch.js'
import { useSearchQuery } from '../layout/SearchContext.jsx'
import { useRouter } from '../router/RouterContext.jsx'
import { pathTo } from '../router/routes.js'
import { PERIODS, periodLabel } from '../domain/period.js'

/**
 * 작업 완료 목록 — 주간보고용.
 *
 * ⚠️ **완료일은 ITSM 원본이 아니라 이 보드가 완료를 처음 관측한 시각이다.**
 * ITSM 목록 API 에 완료일 필드가 없어 우리가 만든 값이고, 폴링 주기(5분)와
 * 그때 담당자 세션이 살아 있었는지에 좌우된다. 주간보고에 그대로 옮겨 적는 숫자라
 * 서버가 준 `note` 를 화면 맨 위에 **숨기지 않고** 띄운다.
 */
export default function DoneListPage() {
  const { navigate } = useRouter()
  const { needle } = useSearchQuery()
  const { data, loading, preset, range, pick, setBound } = useDoneList()
  const rows = useSearch(data?.rows, needle)

  return (
    <div className="card pop">
      <div className="hd">
        <div className="hd-l"><h3>작업 완료 목록</h3>
          <span className="cnt">{rows.length}건 · {periodLabel(range.from, range.to)}{needle ? ` · "${needle}" 검색` : ''}</span></div>
        <div className="hd-tools"><ExportButton filter="COMPLETED" q={needle} from={range.from} to={range.to} /></div>
      </div>

      <div className="period">
        {PERIODS.map(([k, l]) => (
          <button key={k} type="button" className="chip-p" aria-pressed={preset === k} onClick={() => pick(k)}>{l}</button>
        ))}
        <span className="p-sep" />
        <span className="p-cap">완료일</span>
        <input className="p-date" type="date" aria-label="완료일 시작"
          value={range.from} max={range.to || undefined} onChange={setBound('from')} />
        <span className="p-tilde">~</span>
        <input className="p-date" type="date" aria-label="완료일 종료"
          value={range.to} min={range.from || undefined} onChange={setBound('to')} />
      </div>

      {data?.note && <div className="dw-note done-note"><Icon name="info" /><span>{data.note}</span></div>}

      {data && rows.length > 0 && (
        <div className="sum">
          <div className="sum-b"><div className="sum-t">법인별</div>
            <div className="sum-l">{data.byCorp.map(c => (
              <span key={c.label} className="sum-i">{c.label} <b className="num">{c.count}</b></span>))}</div></div>
          <div className="sum-b"><div className="sum-t">담당자별</div>
            <div className="sum-l">{data.byAssignee.map(c => (
              <span key={c.label} className="sum-i">{c.label} <b className="num">{c.count}</b></span>))}</div></div>
        </div>
      )}

      {loading && !data
        ? <EmptyState>불러오는 중…</EmptyState>
        : <RequestTable rows={rows} onOpen={(reqNo) => navigate(pathTo.detail(reqNo))} doneCol />}
    </div>
  )
}
