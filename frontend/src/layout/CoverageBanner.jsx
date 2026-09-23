import Icon from '../ui/Icon.jsx'
import Avatar from '../ui/Avatar.jsx'
import { LEADER_RANK } from '../domain/role.js'

/**
 * "가입 N명 중 M명 연동됨" — 이 화면이 **부분 데이터**임을 숨기지 않는다.
 * 미가입·미로그인 담당자의 요청은 애초에 들어오지 않는다.
 */
export default function CoverageBanner({ user, roleKey }) {
  if (!user.scopeMembers) return null
  return (
    <div className="dw-note" style={{ marginBottom: 14 }}><Icon name="info" />
      <span><b>{user.scopeLabel}</b> 범위 · 가입 {user.scopeMembers}명 중 <b>{user.scopeLinked}명</b> 연동됨.
        {roleKey === 'sme'
          ? <> 우리 법인 요청이면 <b>누가 들고 있든</b> 보입니다. 다만 요청이 이 화면에 들어오려면
              <b> 담당자 중 한 명이라도 가입·로그인</b>해 있어야 합니다 — 아무도 연동돼 있지 않은 건은 표시되지 않습니다.</>
          : <> 여기 보이는 건 <b>연동된 사람의 ITSM 할 일뿐</b>입니다 — 미가입자의 요청은 표시되지 않습니다.</>}</span>
      {/* 담당 SDE 리더는 그 사람이 지금 로그인·동기화 중인지와 무관하게 항상 보인다(UR-260923-2) —
          위 문단이 말하는 "요청 화면"의 한계와는 다른 얘기라 따로 둔다. */}
      {roleKey === 'sme' && user.assignedLeaders?.length > 0 && (
        <span className="leader-contacts">
          <b>담당 SDE 리더</b>
          {user.assignedLeaders.map(l => (
            <span key={l.userId} className={'lead-chip' + (l.linked ? '' : ' unlinked')}
              title={l.linked ? '연동됨' : '아직 로그인·동기화한 적이 없습니다'}>
              <Avatar perId={l.loginId} name={l.name} />
              <span className="pn">{l.name}</span>
              {LEADER_RANK[l.leaderRank] && <span className="rank-badge">{LEADER_RANK[l.leaderRank].l}</span>}
            </span>
          ))}
        </span>
      )}
    </div>
  )
}
