import { useState } from 'react'
import Avatar from '../../ui/Avatar.jsx'
import MemberPicker from './MemberPicker.jsx'
import { fmtDT } from '../../domain/format.js'

/**
 * **시스템별 차수** 표의 한 칸.
 * 디자인은 법인 차수 표에서 쓰던 것을 그대로 옮겼다(사용자 요청 2026-09-10) — 바뀐 것은 줄의 단위뿐이다.
 *
 * 2026-09-10 — 고르는 방식만 **드롭다운 → 팝업**으로 바꿨다. 칸은 입력칸처럼 생겼고, 누르면
 * 화면 한가운데 검색 가능한 목록이 뜬다(`MemberPicker`). 드롭다운은 칸에 붙어 열려서
 * 표 아래쪽 칸에서는 **스크롤해야 보이는 사람**이 생겼다.
 *
 * `mine` = 이 칸이 보고 있는 사람 자신인가. SDE 화면에는 같은 법인의 다른 차수도 함께 차기 때문에
 * (`UR-260909-5`) 표시가 없으면 어느 칸이 나인지 이름으로 찾아야 한다.
 */
export default function PoolCell({ corpNm, systemNm, cell, members, editable, onAssign, mine }) {
  const [open, setOpen] = useState(false)
  const cls = 'pool-cell' + (cell.userId ? '' : ' vacant') + (mine ? ' mine' : '')
  const meta = cell.userId && cell.updatedByName
    ? <div className="pool-meta">{cell.updatedByName} · {fmtDT(cell.updatedAt)}</div>
    : null

  if (!editable) {
    return (
      <td className={cls}>
        {cell.userId
          ? <span className="person"><Avatar perId={cell.loginId} name={cell.name} /><span className="pn">{cell.name}</span>
              {mine && <span className="rank-badge">나</span>}</span>
          : <span className="pn vacant">미배정</span>}
        {meta}
      </td>
    )
  }

  const pick = (userId) => { setOpen(false); onAssign(corpNm, systemNm, cell.tier, userId) }

  return (
    <td className={cls}>
      <button type="button" className="pool-btn" onClick={() => setOpen(true)}
        aria-label={`${corpNm} ${systemNm} ${cell.tier}차 담당자`}>
        {cell.userId
          ? <><Avatar perId={cell.loginId} name={cell.name} /><span className="pn">{cell.name}</span></>
          : <span className="pn vacant">담당자 선택</span>}
      </button>
      {meta}
      {open && (
        <MemberPicker
          title={`${corpNm} · ${systemNm} · ${cell.tier}차`}
          members={members} current={cell.userId}
          allowClear={!!cell.userId}
          onPick={pick}
          onClear={() => pick(null)}
          onClose={() => setOpen(false)} />
      )}
    </td>
  )
}
