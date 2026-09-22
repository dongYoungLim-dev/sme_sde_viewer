/**
 * 화면 목록. **URL 이 곧 화면**이다.
 *
 * 예전에는 `view` 라는 문자열 state 하나로 화면을 갈아 끼웠고, '← 목록' 은 `backTo` state 로
 * 손수 구현했다. URL 이 화면이 되면서 **뒤로가기·페이지 번호 복원**이 공짜로 따라왔다.
 *
 * ⚠️ **단, 첫 진입은 언제나 대시보드다**(2026-09-11 사용자 결정 — `RouterContext.bootAtDashboard`).
 * 로그아웃했다 다시 들어와도 앞사람 화면이 열리던 문제 때문이다. **그래서 상세 링크 공유는 안 된다** —
 * 붙여넣은 링크와 새로고침을 구분할 방법이 없다. 대신 요청번호는 드래그 복사가 된다.
 */
export const ROUTES = [
  { name: 'dash',   path: '/',              title: '대시보드' },
  { name: 'list',   path: '/requests',      title: '요청 목록' },
  { name: 'done',   path: '/done',          title: '완료 목록' },
  { name: 'detail', path: '/requests/:reqNo', title: '요청 상세' },
  { name: 'sched',  path: '/schedule',      title: '스케줄' },
  { name: 'pool',   path: '/pool',          title: 'SDE 인력풀' },
  { name: 'stats',  path: '/stats',         title: '통계·리포트' },
  { name: 'set',    path: '/settings',      title: '설정' }
]

export const routeTitle = (name) => ROUTES.find(r => r.name === name)?.title || ''

/** 경로 → { name, params }. 고정 경로를 먼저 보고, 없으면 `:param` 자리를 맞춘다. */
export function matchPath(pathname) {
  const clean = (pathname || '/').replace(/\/+$/, '') || '/'
  const exact = ROUTES.find(r => r.path === clean)
  if (exact) return { name: exact.name, params: {} }

  const segs = clean.split('/').filter(Boolean)
  for (const r of ROUTES) {
    const rs = r.path.split('/').filter(Boolean)
    if (rs.length !== segs.length) continue
    const params = {}
    const ok = rs.every((s, i) => {
      if (s.startsWith(':')) { params[s.slice(1)] = decodeURIComponent(segs[i]); return true }
      return s === segs[i]
    })
    if (ok) return { name: r.name, params }
  }
  return { name: 'dash', params: {} }
}

export const pathTo = {
  dash: () => '/',
  list: () => '/requests',
  done: () => '/done',
  detail: (reqNo) => `/requests/${encodeURIComponent(reqNo)}`,
  sched: () => '/schedule',
  pool: () => '/pool',
  stats: () => '/stats',
  set: () => '/settings'
}
