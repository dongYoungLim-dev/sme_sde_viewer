import { useState } from 'react'
import { api } from '../api/endpoints.js'
import { useAsyncData } from './useAsyncData.js'
import { periodRange } from '../domain/period.js'

/** 완료 목록 — 기간 프리셋과 직접 입력을 함께 관리한다. */
export function useDoneList(initialPreset = 'this-week') {
  const [preset, setPreset] = useState(initialPreset)
  const [range, setRange] = useState(() => periodRange(initialPreset))

  const { data, loading } = useAsyncData(
    sig => api.doneList(range.from, range.to, sig), [range.from, range.to])

  const pick = (key) => {
    setPreset(key)
    if (key !== 'custom') setRange(periodRange(key))
  }
  const setBound = (k) => (e) => { setPreset('custom'); setRange(r => ({ ...r, [k]: e.target.value })) }

  return { data, loading, preset, range, pick, setBound }
}
