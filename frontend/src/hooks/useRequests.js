import { useCallback, useState } from 'react'
import { api } from '../api/endpoints.js'
import { useSession } from '../session/SessionContext.jsx'
import { useAsyncData } from './useAsyncData.js'

/** 대시보드·요청목록이 함께 쓰는 세 덩어리. 상태 필터가 바뀌면 목록만 다시 읽는다. */
export function useRequests(filter) {
  const rows = useAsyncData(sig => api.requests(filter, sig), [filter])
  const stats = useAsyncData(sig => api.stats(sig), [])
  const timeline = useAsyncData(sig => api.timeline(8, sig), [])
  return {
    rows: rows.data || [],
    stats: stats.data,
    timeline: timeline.data || [],
    loading: rows.loading
  }
}

/** 요청 상세. `reqNo` 가 바뀌면 이전 요청은 취소된다(늦게 온 응답이 이기지 않게). */
export function useRequestDetail(reqNo) {
  const { data, loading, error } = useAsyncData(
    sig => api.detail(reqNo, sig), [reqNo], { skip: !reqNo })
  return { detail: data, loading, error }
}

/**
 * 수동 동기화.
 * ⚠️ **시각을 여기서 만들지 않는다.** 예전에는 `clockText()`(브라우저 시계)를 찍었는데,
 * 그러면 상단바가 *"내가 마지막으로 버튼을 누른 시각"* 을 보여주게 된다 — 서버가 5분마다
 * 동기화해도 화면은 그대로였고, 실패했을 때조차 방금 된 것처럼 보였다(사용자 지적 2026-09-10).
 * 지금은 서버가 준 `me.lastSyncAt` 하나만 보여준다. 실패 문구는 공통 배너가 띄운다.
 */
export function useSync() {
  const { run, refreshData, reloadMe } = useSession()
  const [busy, setBusy] = useState(false)

  const syncNow = useCallback(async () => {
    setBusy(true)
    try {
      await run(() => api.runSync())
      refreshData()          // 열려 있는 모든 화면이 다시 읽는다
      reloadMe()             // 상단바의 동기화 시각은 **여기서 온 서버 값**이다
    } catch { /* 배너에 이미 떴다 */ }
    finally { setTimeout(() => setBusy(false), 400) }
  }, [run, refreshData, reloadMe])

  return { syncNow, busy }
}
