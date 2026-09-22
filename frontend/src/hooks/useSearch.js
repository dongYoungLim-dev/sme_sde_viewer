import { useMemo } from 'react'
import { filterByQuery } from '../domain/request.js'

/**
 * 상단 검색창 필터. 규칙은 `domain/request.js` 한 곳에 있다.
 * (예전에는 같은 필드 배열이 대시보드와 완료목록에 복붙으로 두 벌 있었다)
 */
export function useSearch(rows, q) {
  return useMemo(() => filterByQuery(rows || [], q), [rows, q])
}
