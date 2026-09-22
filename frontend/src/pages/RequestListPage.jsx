import { useRef, useState } from 'react'
import ListHead from '../features/request/ListHead.jsx'
import RequestTable from '../features/request/RequestTable.jsx'
import ScheduleModal from '../features/schedule/ScheduleModal.jsx'
import Pager from '../ui/Pager.jsx'
import { useRequests } from '../hooks/useRequests.js'
import { useSearch } from '../hooks/useSearch.js'
import { usePaging } from '../hooks/usePaging.js'
import { useSearchQuery } from '../layout/SearchContext.jsx'
import { useSession } from '../session/SessionContext.jsx'
import { useRouter } from '../router/RouterContext.jsx'
import { pathTo } from '../router/routes.js'

/**
 * 요청 목록 — 대시보드와 **같은 표**를 쓴다(컬럼이 어긋나지 않도록 컴포넌트가 하나다).
 *
 * 표시는 **페이지 단위**지만 머리의 건수·상태 필터·검색·내보내기는 여전히 <b>전체 기준</b>이다.
 * "지금 화면에 20건" 과 "조건에 맞는 65건" 은 다른 숫자이고, 사람이 옮겨 적는 쪽은 후자다.
 */
export default function RequestListPage() {
  const { navigate } = useRouter()
  const { refreshData } = useSession()
  const { needle } = useSearchQuery()
  const [filter, setFilter] = useState('ALL')
  const { rows, stats } = useRequests(filter)
  const visible = useSearch(rows, needle)
  // 검색어·상태가 바뀌면 다른 목록이니 1페이지로 돌아간다
  const pg = usePaging(visible, { resetKey: filter + ' ' + needle })
  const card = useRef(null)
  const [schedRow, setSchedRow] = useState(null)

  /**
   * 페이지 이동. 표 아래(페이저 옆)에서 누르므로, 그대로 두면 <b>다음 페이지의 첫 행이 화면 위쪽 밖</b>에 있다.
   * 카드 머리가 이미 보이는 상태에서는 움직이지 않는다 — 짧은 목록에서 화면이 튀는 게 더 거슬린다.
   */
  const goto = (n) => {
    pg.goto(n)
    if (card.current && card.current.getBoundingClientRect().top < 0)
      card.current.scrollIntoView({ block: 'start' })
  }

  return (
    <div className="card pop" ref={card}>
      <ListHead title="전체 요청" rows={visible} q={needle} stats={stats} filter={filter} setFilter={setFilter} />
      <RequestTable rows={pg.pageRows} onOpen={(reqNo) => navigate(pathTo.detail(reqNo))} onSchedule={setSchedRow} />
      <Pager page={pg.page} pageCount={pg.pageCount} size={pg.size} sizes={pg.sizes}
        total={pg.total} from={pg.from} to={pg.to} onPage={goto} onSize={pg.setSize} />
      {schedRow && <ScheduleModal reqNo={schedRow.reqNo} title={schedRow.title} workStatus={schedRow.workStatus}
        schedule={schedRow.schedule} onClose={() => setSchedRow(null)} onSaved={refreshData} />}
    </div>
  )
}
