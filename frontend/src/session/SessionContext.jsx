import { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react'
import { api } from '../api/endpoints.js'
import { getSession, setSession, onSessionChanged } from './storage.js'

/**
 * 로그인 세션과 **화면 공통의 실패 처리**를 한 곳에서 갖는다.
 *
 * 예전에는 `guard()` 라는 함수를 Board 가 만들어 `DoneView`·`PoolView`·`DetailView`·`MyPage`
 * → `NoteEditor` 까지 **프롭으로 내려보냈다.** 문제가 둘이었다.
 *  1. `guard` 는 매 렌더 새로 만들어지는데 각 화면의 `useCallback`/`useEffect` 의존성 목록에는
 *     빠져 있었다 — 지금 안 터지는 건 우연이지 계약이 아니었다.
 *  2. 실패를 어떻게 보여줄지가 화면마다 달랐다(무시 11곳 · 지역 표시 3곳 · 침묵 1곳).
 *
 * 그래서 `run()` **하나**를 컨텍스트로 준다. 이 함수는 렌더와 무관하게 **항상 같은 참조**라
 * 의존성 목록에 넣어도 루프가 생기지 않는다.
 */
const Ctx = createContext(null)

export function useSession() {
  const v = useContext(Ctx)
  if (!v) throw new Error('SessionProvider 밖에서 useSession 을 불렀습니다')
  return v
}

export function SessionProvider({ children }) {
  const [me, setMe] = useState(null)
  const [ready, setReady] = useState(false)
  const [error, setError] = useState('')
  /** 데이터 판(版) 번호. 올리면 **열려 있는 모든 화면**이 다시 읽는다(§동기화). */
  const [dataVersion, setDataVersion] = useState(0)
  const meRef = useRef(null)
  meRef.current = me

  const loadMe = useCallback(async () => {
    if (!getSession()) { setMe(null); setReady(true); return null }
    try {
      const next = await api.me()
      setMe(next)
      return next
    } catch {
      setSession('')
      setMe(null)
      return null
    } finally { setReady(true) }
  }, [])

  const logout = useCallback(async () => {
    try { await api.logout() } catch { /* 서버가 죽어 있어도 이 브라우저에서는 나간다 */ }
    setSession('')
    setMe(null)
    setError('')
  }, [])

  /**
   * 모든 API 호출은 이걸 통과한다.
   *  · 401 → 세션이 끝난 것이니 조용히 로그인 화면으로 되돌린다(에러 배너를 띄우지 않는다)
   *  · 취소(AbortError) → 화면이 떠난 것이므로 아무 일도 하지 않는다
   *  · 그 밖의 실패 → **배너에 한 번 뜬다.** `silent: true` 면 배너 없이 예외만 던진다
   *    (그 화면이 자기 자리에서 더 나은 문구로 보여줄 수 있을 때).
   *
   * ⚠️ 의존성이 비어 있어 **참조가 영원히 고정**이다 — 이게 이 함수의 핵심이다.
   */
  const run = useCallback(async (fn, { silent = false } = {}) => {
    try {
      return await fn()
    } catch (e) {
      if (e?.name === 'AbortError') throw e
      if (e?.status === 401) { setSession(''); setMe(null); setError(''); throw e }
      if (!silent) setError(e?.body?.message || e?.message || '요청에 실패했습니다.')
      throw e
    }
  }, [])

  /** 동기화·저장 뒤에 부른다. 대시보드뿐 아니라 **완료 목록·인력풀·노트까지** 같이 다시 읽는다. */
  const refreshData = useCallback(() => setDataVersion(v => v + 1), [])

  useEffect(() => { loadMe() }, [loadMe])

  /** 마지막으로 화면에 반영한 서버 동기화 시각. 새 동기화를 알아채는 기준점이다. */
  const syncSeenRef = useRef(null)
  useEffect(() => { syncSeenRef.current = me?.lastSyncAt ?? null }, [me?.lastSyncAt])

  /**
   * 1분마다 `me` 를 다시 읽는다 — **서버 폴링을 브라우저가 알아채는 유일한 통로**다.
   *
   * ⚠️ 서버는 5분마다 ITSM 을 읽지만 브라우저에게 알려줄 방법이 없다(푸시가 없다).
   * 예전에는 이 폴링이 연동 상태만 갱신하고 끝나서, **가만히 두면 화면이 영원히 옛 데이터**였다 —
   * 새로고침하거나 [동기화]를 눌러야 바뀌었다(사용자 지적 2026-09-10).
   *
   * 그래서 `lastSyncAt` 이 **실제로 바뀐 경우에만** 판을 올린다. 1분마다 목록을 통째로 다시
   * 읽지 않는 이유는 하나 — 바뀐 게 없으면 다시 그릴 이유가 없고, 표를 보고 있는 사람의
   * 스크롤·열어 둔 드롭다운만 흔들린다.
   *
   * ⚠️ 401 은 **삼키지 않는다.** 세션이 서버에서 사라졌는데 조용히 넘어가면 시각이 멈춘 채로
   * 로그인 화면으로도 못 가고, 사용자는 "동기화가 안 된다" 로만 보게 된다.
   */
  useEffect(() => {
    if (!me) return
    const t = setInterval(() => {
      api.me().then(next => {
        setMe(next)
        const at = next?.lastSyncAt ?? null
        if (at && at !== syncSeenRef.current) {
          syncSeenRef.current = at
          setDataVersion(v => v + 1)      // 열려 있는 화면이 새 데이터를 읽는다
        }
      }).catch(e => {
        if (e?.status === 401) { setSession(''); setMe(null) }
      })
    }, 60000)
    return () => clearInterval(t)
  }, [me?.loginId])

  // 다른 탭에서 로그아웃하면 이 탭도 따라 나간다(localStorage 는 React 밖의 상태다)
  useEffect(() => onSessionChanged((val) => { if (!val) setMe(null) }), [])

  const value = {
    me, ready, error,
    setMe, reloadMe: loadMe, logout, run,
    dataVersion, refreshData,
    clearError: useCallback(() => setError(''), []),
    notifyError: useCallback((msg) => setError(msg), [])
  }
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>
}
