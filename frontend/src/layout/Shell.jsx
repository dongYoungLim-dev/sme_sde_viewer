import Sidebar from './Sidebar.jsx'
import Topbar from './Topbar.jsx'
import CoverageBanner from './CoverageBanner.jsx'
import ErrorBanner from '../session/ErrorBanner.jsx'
import { SearchProvider } from './SearchContext.jsx'
import { useSession } from '../session/SessionContext.jsx'
import { useRouter } from '../router/RouterContext.jsx'
import { routeTitle } from '../router/routes.js'
import { roleKeyOf } from '../domain/role.js'

/** 사이드바 + 상단바 + 본문. 화면이 무엇이든 이 틀은 같다. */
export default function Shell({ children }) {
  const { me, logout } = useSession()
  const { route } = useRouter()
  const roleKey = roleKeyOf(me.role)

  const pageTitle = route === 'dash'
    ? { sme: '내 요청 현황', lead: '팀 작업 현황', sde: '내 작업 현황' }[roleKey]
    : routeTitle(route)

  return (
    <SearchProvider>
      <div className="shell">
        <Sidebar user={me} roleKey={roleKey} onLogout={logout} />
        <main className="main">
          <Topbar user={me} pageTitle={pageTitle} />
          <ErrorBanner />
          {route !== 'detail' && <CoverageBanner user={me} roleKey={roleKey} />}
          {children}
          <footer><b>동작 방식</b> — <b>로그인한 내 ITSM 계정</b>으로 5분마다 내 할 일을 조회해 미러링하고, 직전 스냅샷과 비교해 변화를 기록합니다.
            ITSM 비밀번호는 저장하지 않으며, 로그아웃하면 수집도 멈춥니다. 이 화면은 ITSM에 아무것도 쓰지 않는 <b>읽기 전용</b>입니다.</footer>
        </main>
      </div>
    </SearchProvider>
  )
}
