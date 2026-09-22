import { useCallback, useEffect, useState } from 'react'
import { api } from '../api/endpoints.js'
import { useSession } from '../session/SessionContext.jsx'
import { useAsyncData } from './useAsyncData.js'

/**
 * SDE 인력풀. 쓰기 응답이 **표 전체**라 그대로 갈아 끼운다(서버가 갱신본을 준다).
 * 실패 문구는 표 위에 그 자리에서 보여주는 편이 낫기 때문에 `silent` 로 받아 직접 띄운다.
 */
export function usePool() {
  const { run } = useSession()
  const { data, loading, reload } = useAsyncData(sig => api.pool(sig), [])
  const [fresh, setFresh] = useState(null)     // 쓰기로 갱신된 최신본
  const [err, setErr] = useState('')

  // 서버에서 새로 읽어 온 것이 있으면 쓰기 스냅샷은 버린다 —
  // 안 그러면 [동기화] 뒤에도 이 화면만 옛 표를 붙들고 있게 된다.
  useEffect(() => { if (data) setFresh(null) }, [data])

  const write = useCallback(async (fn, fallbackMsg) => {
    setErr('')
    try { setFresh(await run(fn, { silent: true })) }
    catch (e) { if (e?.name !== 'AbortError') setErr(e?.body?.message || fallbackMsg) }
  }, [run])

  return {
    data: fresh || data,
    loading,
    err,
    reload,
    // 차수 배정은 **시스템 줄에만** 있다. 법인담당SDE 는 차수가 없어 addLead/removeLead 를 쓴다.
    assign: (corpNm, systemNm, tier, userId) =>
      write(() => api.assignPool(corpNm, systemNm, tier, userId), '배정을 저장하지 못했습니다.'),
    addLead: (corpNm, userId) =>
      write(() => api.addPoolLead(corpNm, userId), '담당자를 추가하지 못했습니다.'),
    removeLead: (corpNm, userId) =>
      write(() => api.removePoolLead(corpNm, userId), '담당자를 빼지 못했습니다.'),
    addRow: (corpNm, systemNm) =>
      write(() => api.addPoolRow(corpNm, systemNm), '줄을 추가하지 못했습니다.'),
    removeRow: (corpNm, systemNm) =>
      write(() => api.removePoolRow(corpNm, systemNm), '줄을 내리지 못했습니다.')
  }
}
