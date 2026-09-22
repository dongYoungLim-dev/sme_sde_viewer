import { useState } from 'react'
import Icon from '../ui/Icon.jsx'
import EmptyState, { EmptyCard } from '../ui/EmptyState.jsx'
import PoolCorpBlock from '../features/pool/PoolCorpBlock.jsx'
import CorpAdder from '../features/pool/CorpAdder.jsx'
import PoolMembers from '../features/pool/PoolMembers.jsx'
import { usePool } from '../hooks/usePool.js'

/**
 * SDE 인력풀 — 법인마다 **법인담당SDE 명단 + 시스템별 차수 담당자**.
 *
 * 리더가 엑셀로 관리하던 담당자 표를 그대로 옮긴 것이다.
 * ⚠️ **ITSM 에 없는 데이터라 이 보드가 원본이다** — 유실되면 복구할 곳이 없다. 화면도 그렇게 말한다.
 * 편집은 **리더만**(사용자 결정 2026-09-08). SME 는 자기 법인을, SDE 는 자기가 배정된 법인을 읽기만 한다.
 *
 * 2026-09-10 `UR-260910-1` — 차수가 **시스템 단위**로 내려왔다. 법인담당SDE 에는 **차수가 없고
 * 인원 제한도 없다**(사용자). 담당자 선택은 칸에 붙는 드롭다운이 아니라 **검색 팝업**이다 —
 * 표가 길어지면 드롭다운 목록이 화면 밖으로 나갔다.
 */
export default function PoolPage() {
  const { data, loading, err, assign, addLead, removeLead, addRow, removeRow } = usePool()
  const [drop, setDrop] = useState('')          // 내리기 확인 중인 줄

  if (loading && !data) return <EmptyCard>불러오는 중…</EmptyCard>
  if (!data) return <EmptyCard>인력풀을 불러오지 못했습니다.</EmptyCard>

  const corps = data.corps || []
  const sysCount = corps.reduce((n, c) => n + c.systems.length, 0)
  const actions = { assign, addLead, removeLead, addRow, removeRow }

  return (
    <>
      <div className="card pop">
        <div className="hd">
          <div className="hd-l"><h3>SDE 인력풀</h3>
            <span className="cnt">{data.scopeLabel} · 법인 {corps.length}개 · 시스템 {sysCount}개</span></div>
          {err && <span className="x-err">{err}</span>}
        </div>
        <div className="dw-note done-note"><Icon name="info" /><span>{data.scopeNote}</span></div>

        {!corps.length
          ? <EmptyState>{data.editable
            ? <>담당 법인이 없습니다. 아래 <b>법인 추가</b>로 우리 팀이 맡는 법인을 올려 주세요.</>
            : <>표시할 법인이 없습니다. 담당 법인은 각 팀의 SDE 리더가 추가합니다.</>}</EmptyState>
          : corps.map(c => (
            <PoolCorpBlock key={c.corpNm} corp={c} tiers={data.tiers} data={data}
              drop={drop} setDrop={setDrop} actions={actions} />
          ))}

        {data.editable && <CorpAdder corpOptions={data.corpOptions} onAdd={addRow} />}
      </div>

      {data.editable && <PoolMembers members={data.members} />}
    </>
  )
}
