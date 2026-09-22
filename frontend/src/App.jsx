import { useEffect } from 'react'
import Shell from './layout/Shell.jsx'
import AuthPage from './pages/AuthPage.jsx'
import DashboardPage from './pages/DashboardPage.jsx'
import RequestListPage from './pages/RequestListPage.jsx'
import DoneListPage from './pages/DoneListPage.jsx'
import RequestDetailPage from './pages/RequestDetailPage.jsx'
import SchedulePage from './pages/SchedulePage.jsx'
import PoolPage from './pages/PoolPage.jsx'
import SettingsPage from './pages/SettingsPage.jsx'
import StatsPage from './pages/StatsPage.jsx'
import ErrorBanner from './session/ErrorBanner.jsx'
import { useSession } from './session/SessionContext.jsx'
import { useRouter } from './router/RouterContext.jsx'

/**
 * 하는 일은 둘뿐이다 — **로그인했는가**, **어느 화면인가**.
 * (예전에는 이 파일 하나가 1,069줄에 컴포넌트 25개였다)
 */
const PAGES = {
  dash: DashboardPage,
  list: RequestListPage,
  done: DoneListPage,
  detail: RequestDetailPage,
  sched: SchedulePage,
  pool: PoolPage,
  set: SettingsPage,
  stats: StatsPage
}

export default function App() {
  const { me, ready } = useSession()
  const { route, navigate } = useRouter()

  /**
   * 로그아웃하거나 세션이 끊기면 **주소를 대시보드로 되돌린다.**
   * 안 그러면 같은 탭에서 다시 로그인했을 때 앞사람이 보던 화면이 그대로 열린다
   * (새로고침이 아니라 앱 안에서 일어나는 일이라 `bootAtDashboard` 가 잡지 못한다).
   */
  useEffect(() => { if (ready && !me) navigate('/', { replace: true }) }, [ready, me, navigate])

  if (!ready) return null
  if (!me) return <><ErrorBanner /><AuthPage /></>

  const Page = PAGES[route] || DashboardPage
  return <Shell><Page /></Shell>
}
