import { useCallback, useEffect, useState } from 'react'
import { useSession } from '../session/SessionContext.jsx'

/**
 * 서버에서 한 덩어리를 읽어 오는 **유일한 방법**.
 *
 * 화면마다 제각각이던 것을 여기 모았다. 특히 두 가지를 구조로 막는다.
 *
 *  1. **응답 경합** — 상세를 빠르게 두 번 열면 늦게 온 응답이 이겨서 *다른 건*이 뜨는 일이 있었다.
 *     `AbortController` 로 이전 요청을 끊고, 살아 있지 않은 화면에는 쓰지 않는다.
 *     (`DoneView`·`NoteEditor` 에만 `live` 플래그가 있었고 상세에는 없었다 — 같은 문제, 다른 처리)
 *  2. **판(版) 갱신** — `dataVersion` 이 의존성에 들어 있어, [동기화] 한 번이면
 *     대시보드뿐 아니라 **그때 열려 있는 모든 화면**이 다시 읽는다.
 *
 * `fetcher` 는 매 렌더 새 함수라 의존성에 넣지 않는다 — **언제 다시 읽을지는 `deps` 가 정한다.**
 */
export function useAsyncData(fetcher, deps = [], { silent = false, skip = false } = {}) {
  const { run, dataVersion } = useSession()
  const [state, setState] = useState({ data: null, loading: !skip, error: null })
  const [tick, setTick] = useState(0)

  useEffect(() => {
    if (skip) { setState({ data: null, loading: false, error: null }); return }
    const ac = new AbortController()
    let live = true
    setState(s => ({ ...s, loading: true }))
    run(() => fetcher(ac.signal), { silent })
      .then(d => { if (live) setState({ data: d, loading: false, error: null }) })
      .catch(e => { if (live && e?.name !== 'AbortError') setState({ data: null, loading: false, error: e }) })
    return () => { live = false; ac.abort() }
    // eslint 규칙이 붙으면 fetcher 를 넣으라고 하겠지만, 그러면 매 렌더 재요청이 된다.
  }, [...deps, dataVersion, tick, skip, run])

  return { ...state, reload: useCallback(() => setTick(t => t + 1), []) }
}
