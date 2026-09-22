import EmptyState from '../../ui/EmptyState.jsx'
import PoolCell from './PoolCell.jsx'
import CorpLead from './CorpLead.jsx'
import SystemAdder from './SystemAdder.jsx'

/**
 * 법인 하나의 블록 — **법인담당SDE 명단(칩)** + **시스템별 차수(표)**.
 *
 * 두 영역을 나눈 이유는 [[CorpLead]] 에 적었다. 표 쪽은 **예전 법인 차수 표 디자인 그대로**다
 * (사용자 요청 2026-09-10) — 바뀐 것은 첫 열이 법인에서 시스템으로 내려온 것뿐이다.
 */
export default function PoolCorpBlock({ corp, tiers, data, drop, setDrop, actions }) {
  const { corpNm, leads, systems } = corp
  const tierNos = Array.from({ length: tiers }, (_, i) => i + 1)
  const taken = new Set(systems.map(r => r.systemNm))
  const dropKey = (systemNm) => corpNm + '|' + (systemNm || '')

  const dropBtn = (systemNm, title) => {
    const k = dropKey(systemNm)
    // 삭제 확인은 브라우저 confirm 이 아니라 그 자리에서 한다 — 무엇을 지우는지 보면서 누르게
    return drop === k
      ? <span className="corp-drop">
          <button type="button" className="lnk danger"
            onClick={() => { actions.removeRow(corpNm, systemNm); setDrop('') }}>내린다</button>
          <button type="button" className="lnk" onClick={() => setDrop('')}>취소</button>
        </span>
      : <button type="button" className="corp-x" title={title} onClick={() => setDrop(k)}>×</button>
  }

  return (
    <section className="corp-block">
      <header className="corp-block-hd">
        <h4>{corpNm}</h4>
        <span className="cnt">시스템 {systems.length}개</span>
        {data.editable && dropBtn(null, '이 법인을 표에서 내린다')}
      </header>

      <CorpLead corpNm={corpNm} leads={leads} members={data.members} editable={data.editable}
        onAdd={actions.addLead} onRemove={actions.removeLead} meUserId={data.meUserId} />

      {!systems.length
        ? <EmptyState>{data.editable
          ? <>등록된 시스템이 없습니다. 아래 <b>시스템 추가</b>로 이 법인의 시스템을 올려 주세요.</>
          : <>등록된 시스템이 없습니다. 시스템은 담당 팀의 SDE 리더가 추가합니다.</>}</EmptyState>
        : <div className="tbl-scroll"><table className="pool-tbl">
          <thead><tr>
            <th style={{ minWidth: 180 }}>시스템</th>
            {tierNos.map(t => <th key={t} style={{ minWidth: 150 }}>{t}차 담당</th>)}
          </tr></thead>
          <tbody>{systems.map(row => (
            <tr key={row.systemNm}>
              <td className="c-corp sel">
                <div className="corp-hd"><span>{row.systemNm}</span>
                  {data.editable && dropBtn(row.systemNm, '이 시스템을 표에서 내린다')}</div>
                <div className="pool-fill">{row.filled}/{tiers} 배정</div></td>
              {row.tiers.map(c => (
                <PoolCell key={c.tier} corpNm={corpNm} systemNm={row.systemNm} cell={c}
                  members={data.members} editable={data.editable} onAssign={actions.assign}
                  mine={!!c.userId && c.userId === data.meUserId} />
              ))}
            </tr>
          ))}</tbody>
        </table></div>}

      {data.editable && <SystemAdder corpNm={corpNm} systemOptions={data.systemOptions}
        taken={taken} onAdd={actions.addRow} />}
    </section>
  )
}
