import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react'
import { matchPath } from './routes.js'

/**
 * 작은 라우터. 의존성을 더하지 않으려고 History API 를 직접 쓴다.
 * 필요한 건 넷뿐이다 — 현재 위치 · 이동 · 뒤로 · 질의문자열.
 *
 * <p>질의문자열까지 들고 있는 이유: **목록의 페이지 번호가 URL 에 실린다**(`?p=3&size=50`).
 * 화면 state 로 두면 [보기]로 상세에 들어갔다 [← 목록]으로 돌아올 때마다 1페이지로 떨어진다.
 * 경로 매칭은 여전히 <b>경로만</b> 본다 — `?p=3` 이 붙었다고 다른 화면이 되지는 않는다.
 */
const Ctx = createContext(null)

export function useRouter() {
  const v = useContext(Ctx)
  if (!v) throw new Error('RouterProvider 밖에서 useRouter 를 불렀습니다')
  return v
}

/** 지금 주소 — 경로와 질의문자열을 함께 본다(둘 중 하나만 바뀌어도 다시 그려야 한다). */
const here = () => ({ pathname: window.location.pathname, search: window.location.search })

/**
 * **첫 진입은 언제나 대시보드다**(사용자 결정 2026-09-11).
 *
 * 예전에는 주소를 그대로 살렸다 — 새로고침해도 보던 화면이 유지되는 게 이 라우터를 넣은 이유였다.
 * 그런데 실제로는 **로그아웃했다 다시 들어와도 앞사람이 보던 화면이 그대로 열렸다.**
 * 상세 화면은 그 요청을 볼 수 있는 사람만 열리므로 내용이 새지는 않지만(서버가 404 를 준다),
 * 로그인 직후에 남의 화면이 뜨는 것 자체가 틀린 인상을 준다.
 *
 * ⚠️ **대가 — 상세 주소를 남에게 줘도 대시보드로 열린다.** 붙여넣은 링크와 새로고침은
 * 브라우저 입장에서 똑같은 '새 진입' 이라 구분할 방법이 없다. 되살리려면 이 함수만 지우면 된다.
 * (요청번호는 목록·상세에서 드래그 복사가 되므로 전달 수단이 없어지는 것은 아니다)
 *
 * 질의문자열(`?p=3`)도 함께 지운다 — 페이지 번호만 남으면 목록을 3페이지부터 보게 된다.
 */
function bootAtDashboard() {
  if (window.location.pathname !== '/' || window.location.search)
    window.history.replaceState({ depth: 0 }, '', '/')
  return { pathname: '/', search: '' }
}

export function RouterProvider({ children }) {
  const [loc, setLoc] = useState(bootAtDashboard)

  useEffect(() => {
    const onPop = () => setLoc(here())
    window.addEventListener('popstate', onPop)
    return () => window.removeEventListener('popstate', onPop)
  }, [])

  const navigate = useCallback((to, { replace = false } = {}) => {
    const url = new URL(to, window.location.origin)
    const next = url.pathname + url.search
    if (next === window.location.pathname + window.location.search) return
    const depth = (window.history.state?.depth ?? 0) + (replace ? 0 : 1)
    window.history[replace ? 'replaceState' : 'pushState']({ depth }, '', next)
    setLoc({ pathname: url.pathname, search: url.search })
  }, [])

  /**
   * 뒤로 가기. 단, **상세 URL 을 직접 열어 들어온 경우**(우리 앱에 쌓인 이력이 없는 경우)는
   * 브라우저를 뒤로 보내면 앱 밖으로 나가 버리므로 fallback 경로로 이동한다.
   */
  const back = useCallback((fallback = '/') => {
    if ((window.history.state?.depth ?? 0) > 0) window.history.back()
    else navigate(fallback, { replace: true })
  }, [navigate])

  const query = useMemo(() => new URLSearchParams(loc.search), [loc.search])

  /**
   * 질의문자열 갱신 — 바꿀 것만 준다(`{ p: 3 }`). `null`·`''` 이면 그 값을 **지운다**
   * (기본값을 URL 에 남기지 않는다 — `/requests` 와 `/requests?p=1` 이 같은 화면이어야 한다).
   *
   * <p>기본이 `replace` 인 이유: 페이지를 넘길 때마다 이력이 쌓이면 <b>뒤로가기를 넘긴 횟수만큼</b>
   * 눌러야 목록을 벗어난다. 대신 이력 항목의 URL 이 갱신되므로, 상세로 들어갔다 [← 목록] 으로
   * 돌아오면 <b>마지막으로 보던 페이지가 그대로</b> 복원된다.
   */
  const setQuery = useCallback((patch, { replace = true } = {}) => {
    const q = new URLSearchParams(window.location.search)
    Object.entries(patch).forEach(([k, v]) => {
      if (v == null || v === '') q.delete(k)
      else q.set(k, String(v))
    })
    const qs = q.toString()
    navigate(window.location.pathname + (qs ? '?' + qs : ''), { replace })
  }, [navigate])

  const { name, params } = matchPath(loc.pathname)
  return <Ctx.Provider value={{ path: loc.pathname, search: loc.search, query, setQuery, route: name, params, navigate, back }}>
    {children}
  </Ctx.Provider>
}
