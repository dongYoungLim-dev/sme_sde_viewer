import Select from '../../ui/Select.jsx'
import { DIST_ORDER, STATUS } from '../../domain/status.js'

/**
 * 상태별 목록 조회. 기본값은 `전체`.
 * 앞쪽 6개는 **조회 축**(항상 노출), 뒤쪽은 **상태 버킷**(건수가 있거나 지금 고른 것만).
 * 상태가 늘어나도 헤더 한 줄이 넘치지 않게 하려는 것이다(예전의 칩 나열을 대체).
 *
 * ⚠️ **`새 댓글`(CMT) 은 여기 없다.** 잠깐 넣었다가 뺐다 — 셀렉트 안에 있으면
 * **드롭다운을 열기 전에는 새 댓글이 있는지 알 수 없다**(사용자 지적 2026-09-11).
 * 접히지 않는 자리가 필요해서 `NewCommentFilter` 버튼으로 나갔다. 같은 조회를 두 곳에 두지 않는다.
 */
export default function StatusFilterSelect({ stats, filter, setFilter }) {
  const k = stats?.kpis
  const distMap = {}
  ;(stats?.dist || []).forEach(d => { distMap[d.status] = d.count })

  const options = [
    { key: 'ALL', label: '전체', count: k?.total ?? 0 },
    { key: 'OPEN', label: '진행중', count: k?.open ?? 0 },
    { key: 'LATE', label: '지연', count: k?.late ?? 0 },
    { key: 'INTAKE', label: '미할당', count: k?.intake ?? 0 },
    { key: 'LEADERP', label: 'SDE 배정대기', count: k?.leaderPending ?? 0 },
    { key: 'CLOSED', label: '할 일 종료', count: k?.closed ?? 0 },
    ...DIST_ORDER.filter(x => distMap[x] || filter === x).map((x, i) => ({
      key: x, label: STATUS[x].l, count: distMap[x] || 0, dotVar: STATUS[x].v, divider: i === 0
    }))
  ]

  return <Select options={options} value={filter} onChange={setFilter}
    caption="상태" ariaLabel="상태별 조회" />
}
