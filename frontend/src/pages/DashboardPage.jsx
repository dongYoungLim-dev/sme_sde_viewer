import { useRef, useState } from 'react'
import Kpis from '../features/dashboard/Kpis.jsx'
import ProgressMatrix from '../features/dashboard/ProgressMatrix.jsx'
import Timeline from '../features/dashboard/Timeline.jsx'
import ListHead from '../features/request/ListHead.jsx'
import RequestSection from '../features/request/RequestSection.jsx'
import ScheduleModal from '../features/schedule/ScheduleModal.jsx'
import EmptyState from '../ui/EmptyState.jsx'
import Pager from '../ui/Pager.jsx'
import { useRequests } from '../hooks/useRequests.js'
import { useSearch } from '../hooks/useSearch.js'
import { usePaging } from '../hooks/usePaging.js'
import { useSearchQuery } from '../layout/SearchContext.jsx'
import { useSession } from '../session/SessionContext.jsx'
import { useRouter } from '../router/RouterContext.jsx'
import { pathTo } from '../router/routes.js'
import { roleKeyOf } from '../domain/role.js'
import { openCount } from '../domain/status.js'

/**
 * 대시보드.
 *
 * **완료된 건은 표에서 뺀다** — 매일 보는 화면에서 끝난 일이 자리를 차지하면
 * 정작 처리할 건이 아래로 밀린다. 건수는 위 KPI '완료' 카드에 그대로 남고,
 * 전체를 보려면 **요청 목록** / **완료 목록** 으로 간다.
 *
 * 남은 건은 **신규 유입 / 진행 중** 으로 나눈다. 기준은 ITSM 등록일이 아니라
 * **우리 시스템에 처음 나타난 시각**(`app.new-request-hours`, 기본 24시간) —
 * 화면이 답할 질문은 "언제 접수됐나" 가 아니라 "내가 아직 못 본 건인가" 이기 때문이다.
 *
 * <h4>위쪽 한 줄이 '지금 어떤가' 다 (2026-09-10 · `UR-260910-4`)</h4>
 * 예전에는 `현황 숫자` 만 위에 있고 **상태 분포·담당자별 현황·최근 변화가 목록 아래**에 있었다.
 * 매일 보는 화면에서 진행 상황이 목록을 다 지나야 나오는 자리에 있으면 한눈에 안 들어온다.
 * 지금은 셋이 **목록 위 한 줄**에 선다 — `현황(KPI)` | `진행 현황(표)` | `최근 상태 변화`.
 * 상태 분포와 담당자별 현황은 **같은 축(상태)을 공유하고 있었으므로 표 하나로 합쳤다**({@link ProgressMatrix}).
 *
 * <h4>페이징은 '진행 중' 에만 붙인다</h4>
 * **신규 유입은 통째로 둔다.** 그 묶음이 답하는 질문이 "내가 아직 못 본 게 있나" 라서,
 * 2페이지로 넘어간 신규 건은 <b>없는 것과 같아진다</b> — 24시간 안의 몇 건이라 넘칠 일도 없다.
 * 길어지는 쪽은 언제나 진행 중이고, 페이징이 필요한 것도 그쪽이다.
 */
export default function DashboardPage() {
  const { me, refreshData } = useSession()
  const { navigate } = useRouter()
  const { needle } = useSearchQuery()
  const [filter, setFilter] = useState('ALL')
  const { rows, stats, timeline } = useRequests(filter)
  const visible = useSearch(rows, needle)
  const roleKey = roleKeyOf(me.role)
  const onOpen = (reqNo) => navigate(pathTo.detail(reqNo))
  // 목록에서 바로 스케줄을 등록·확인한다(`UR-260922-1`, §8-9) — 상세로 안 가도 되게.
  const [schedRow, setSchedRow] = useState(null)

  // ⚠️ `새 댓글` 로 좁혔을 때만 **완료 건도 남긴다.** 이 화면은 평소 완료를 숨기는데,
  //    그러면 셀렉트에는 `새 댓글 1` 이라 적혀 있는데 목록은 비어 있는 상태가 된다 —
  //    "댓글만 남기고 확인을 못 한다" 는 바로 그 문제다(사용자 지적 2026-09-11).
  const open = filter === 'CMT' ? visible : visible.filter(r => r.workStatus !== 'DONE')
  const doneCount = visible.length - open.length
  const fresh = open.filter(r => r.fresh)
  const ongoing = open.filter(r => !r.fresh)
  // 검색어·상태가 바뀌면 다른 목록이니 1페이지로 돌아간다
  const pg = usePaging(ongoing, { resetKey: filter + ' ' + needle })
  const ongoingHead = useRef(null)

  /**
   * 페이지 이동. 올려 보낼 자리는 **카드 머리가 아니라 '진행 중' 머리**다 —
   * 카드 머리로 올리면 위에 있는 신규 유입부터 다시 지나야 다음 페이지의 첫 행이 나온다.
   */
  const goto = (n) => {
    pg.goto(n)
    if (ongoingHead.current && ongoingHead.current.getBoundingClientRect().top < 0)
      ongoingHead.current.scrollIntoView({ block: 'start' })
  }

  return <>
    <div className="board">
      <div className="card"><div className="hd"><h3>현황</h3></div>
        <Kpis roleKey={roleKey} stats={stats} /></div>
      {/* 맨 윗줄이 예전 '상태 분포', 아래가 예전 '담당자별 현황' 이다. 모수는 **완료를 뺀 건수**라
          KPI 의 '완료' 와 겹치지 않는다 — 카드 머리의 숫자가 그 사실을 밝힌다. */}
      <div className="card"><div className="hd"><h3>진행 현황</h3>
        <span className="cnt">{openCount(stats?.dist)}건 · 완료 제외</span></div>
        <ProgressMatrix stats={stats} /></div>
      <div className="card"><div className="hd"><h3>최근 상태 변화</h3><span className="cnt">폴링 감지</span></div>
        <Timeline rows={timeline} /></div>
    </div>
    <div className="card pop">
      <ListHead title={roleKey === 'sme' ? '내가 전달한 요청' : '요청 목록'}
        rows={open} q={needle} stats={stats} filter={filter} setFilter={setFilter}
        extra={doneCount ? `완료 ${doneCount}건 숨김` : null} />
      {!open.length
        ? <EmptyState>{doneCount ? '완료된 건만 있습니다. 완료 목록에서 확인하세요.' : '표시할 요청이 없습니다.'}</EmptyState>
        : <>
          <RequestSection cls="fresh" title="신규 유입" note="최근 24시간 안에 처음 들어온 건" rows={fresh} onOpen={onOpen} onSchedule={setSchedRow} />
          <RequestSection title={fresh.length ? '진행 중' : '진행 중인 요청'} rows={pg.pageRows}
            count={pg.total} headRef={ongoingHead} onOpen={onOpen} onSchedule={setSchedRow} />
          <Pager page={pg.page} pageCount={pg.pageCount} size={pg.size} sizes={pg.sizes}
            total={pg.total} from={pg.from} to={pg.to} onPage={goto} onSize={pg.setSize} />
        </>}
    </div>
    {schedRow && <ScheduleModal reqNo={schedRow.reqNo} title={schedRow.title} workStatus={schedRow.workStatus}
      schedule={schedRow.schedule} onClose={() => setSchedRow(null)} onSaved={refreshData} />}
  </>
}
