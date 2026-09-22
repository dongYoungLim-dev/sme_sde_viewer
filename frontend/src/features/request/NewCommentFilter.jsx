/**
 * `새 댓글 N` — **셀렉트 밖으로 꺼낸 조회 버튼.**
 *
 * 처음에는 상태 셀렉트의 한 항목이었는데, 그러면 **드롭다운을 열기 전에는 새 댓글이 있는지 알 수 없다**
 * (사용자 지적 2026-09-11). 목록 배지는 그 줄이 화면에 있을 때만 보이고, 셀렉트 항목은 열어야 보인다 —
 * 둘 다 "먼저 알아채는" 일을 못 한다. 그래서 **접히지 않는 자리**로 꺼냈다.
 *
 * ⚠️ **셀렉트에는 더 이상 같은 항목을 두지 않는다.** 두 곳에 있으면 한쪽만 고쳐도 둘이 어긋나고,
 * 사용자는 같은 조회를 두 번 만나 어느 쪽이 맞는지 묻게 된다.
 *
 * **없으면 아예 안 그린다.** 늘 `새 댓글 0` 이 붙어 있으면 눈이 그 자리를 무시하게 되고,
 * 그러면 정작 1이 됐을 때도 안 보인다. **나타나는 것 자체가 신호다.**
 */
export default function NewCommentFilter({ stats, filter, setFilter }) {
  const n = stats?.kpis?.newComments ?? 0
  const on = filter === 'CMT'
  // 다 읽어서 0이 돼도 **고른 상태면 남긴다** — 아니면 조회를 끌 버튼이 사라진다
  if (!n && !on) return null

  return (
    <button className={'cmt-filter' + (on ? ' on' : '')}
      aria-pressed={on}
      title={on ? '전체 목록으로 돌아갑니다' : '아직 안 본 코멘트가 달린 요청만 봅니다'}
      onClick={() => setFilter(on ? 'ALL' : 'CMT')}>
      <span className="dot" />새 댓글{n > 0 && <b>{n}</b>}
    </button>
  )
}
