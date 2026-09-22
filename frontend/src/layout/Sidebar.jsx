import Avatar from '../ui/Avatar.jsx'
import Icon from '../ui/Icon.jsx'
import { useRouter } from '../router/RouterContext.jsx'
import { pathTo } from '../router/routes.js'
import { LEADER_RANK, ROLES } from '../domain/role.js'

/** 메뉴. '통계·리포트'는 자리만 잡아 둔 것이라 눌리지 않는다. */
export default function Sidebar({ user, roleKey, onLogout }) {
  const { route, navigate } = useRouter()

  const items = [
    ['dash', '대시보드', 'dash'],
    ['list', '요청 목록', 'list'],
    ['done', '완료 목록', 'done'],
    ['sched', '스케줄', 'calendar'],
    ['pool', '인력풀', 'team'],
    ['stats', '통계·리포트', 'stats', true],
    ['set', '설정', 'set']
  ]

  return (
    <aside className="sidebar">
      <div className="sb-inner">
        <div className="sb-brand"><div className="lg">S</div><div><h1>SDE 보드</h1><p>ITSM 미러</p></div></div>
        <div className="nav-sec">메뉴</div>
        <nav className="nav">
          {items.map(([k, label, icon, soon]) => (
            <a key={k} className={soon ? 'disabled' : ''} aria-current={route === k ? 'page' : undefined}
              href={soon ? undefined : pathTo[k]()}
              onClick={soon ? undefined : (e) => { e.preventDefault(); navigate(pathTo[k]()) }}>
              <span className="ic"><Icon name={icon} /></span>{label}{soon && <span className="soon">준비중</span>}</a>
          ))}
        </nav>
        <div className="sb-user">
          <Avatar perId={user.loginId} name={user.name} />
          <div className="uu"><div className="un">{user.name}
            {/* 정/부는 이름 옆에 붙는다(사용자 지정 2026-09-09) — 역할 줄이 아니라 이름 줄이다 */}
            {LEADER_RANK[user.leaderRank]
              ? <span className="rank-badge" title={LEADER_RANK[user.leaderRank].d}>{LEADER_RANK[user.leaderRank].l}</span>
              /* 이미 가입한 리더는 값이 비어 있다 — 숨기면 "넣었는데 아무 데도 안 보인다"가 된다 */
              : user.role === 'SDE_LEADER' &&
                <span className="rank-badge unset" title="설정 화면에서 정/부를 고르세요">미설정</span>}</div>
            <div className="ur">{ROLES[user.role]?.l || user.role}{(user.corpNm || user.team) ? ` · ${user.corpNm || user.team}` : ''}</div></div>
          <button className="logout" onClick={onLogout} aria-label="로그아웃"><Icon name="logout" /></button>
        </div>
      </div>
    </aside>
  )
}
