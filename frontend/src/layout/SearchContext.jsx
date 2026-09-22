import { createContext, useContext, useState } from 'react'

/**
 * 상단 검색창의 값. **입력은 상단바에, 사용은 각 목록 화면에** 있어서 컨텍스트로 둔다.
 * 필터 규칙 자체는 `domain/request.js` 하나다(여기는 값만 나른다).
 */
const Ctx = createContext({ q: '', setQ: () => {} })

export const useSearchQuery = () => useContext(Ctx)

export function SearchProvider({ children }) {
  const [q, setQ] = useState('')
  return <Ctx.Provider value={{ q, setQ, needle: q.trim().toLowerCase() }}>{children}</Ctx.Provider>
}
