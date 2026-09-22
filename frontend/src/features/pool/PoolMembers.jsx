import Avatar from '../../ui/Avatar.jsx'
import Icon from '../../ui/Icon.jsx'
import EmptyState from '../../ui/EmptyState.jsx'

/** 내 팀 배정 대상 — 가입한 SDE·리더와 연동 상태. 리더에게만 보인다. */
export default function PoolMembers({ members }) {
  return (
    <div className="card" style={{ marginTop: 22 }}>
      <div className="hd"><h3>내 팀 배정 대상</h3><span className="cnt">SDE · 리더 {members.length}명</span></div>
      {!members.length
        ? <EmptyState>아직 가입한 팀원이 없습니다. SDE 가 회원가입(역할 = SDE, 팀 선택)을 해야 배정할 수 있습니다.</EmptyState>
        : <div className="tbl-scroll"><table>
          <thead><tr><th>이름</th><th>ITSM 계정</th><th>연동</th><th>담당 법인 · 차수</th></tr></thead>
          <tbody>{members.map(m => (
            <tr key={m.userId}>
              <td><span className="person"><Avatar perId={m.loginId} name={m.name} /><span className="pn">{m.name}</span>
                {m.role === 'SDE_LEADER' && <span className="rank-badge">리더</span>}</span></td>
              <td className="mono sel" style={{ fontSize: 12 }}>{m.loginId}</td>
              <td>{m.linked
                ? <span className="pill soft" style={{ '--st': 'var(--st-done)' }}><span className="pd" />연동됨</span>
                : <span className="pill soft">로그인 대기</span>}</td>
              <td style={{ fontSize: 12, color: 'var(--ink-2)' }}>{m.corps.length ? m.corps.join(' · ') : '미배정'}</td>
            </tr>
          ))}</tbody>
        </table></div>}
      <p className="src-note" style={{ margin: '0 20px 16px' }}><Icon name="shield" />
        연동은 <b>그 사람이 로그인해 있는 동안</b>에만 일어납니다 — 가입만 하고 로그인하지 않으면 그 사람의 요청은 미러링되지 않습니다.</p>
    </div>
  )
}
