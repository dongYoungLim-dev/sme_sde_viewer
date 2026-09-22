import { useCallback, useEffect, useMemo, useRef } from 'react'
import { useRouter } from '../router/RouterContext.jsx'

/** 한 페이지 건수. **첫 값이 기본값이자 "페이저를 띄울 최소 건수"** 다. */
export const PAGE_SIZES = [20, 50, 100]

/**
 * 목록 페이징 — 상태는 **URL 에 있다**(`?p=3&size=50`).
 *
 * <h4>자르는 쪽이 화면인 이유(클라이언트 페이징)</h4>
 * 상단 검색·상태 필터·내보내기가 지금 전부 <b>받아 둔 전체 행</b> 위에서 돈다
 * (`useSearch` 는 `domain/request.js` 의 규칙으로 브라우저에서 거른다).
 * 서버가 잘라 주기 시작하면 그 셋이 다 서버로 따라가야 하고, "검색 결과 3페이지" 같은
 * 조합마다 API 가 하나씩 늘어난다. 미러링 대상은 <b>내 할 일</b>이라 행 수가 수십~수백 건이고,
 * 여기서 잘라 얻는 것은 전송량이 아니라 <b>화면 가독성</b>이다. 목록이 수천 건이 되면
 * 그때 서버 페이징으로 옮기면 되고, 그 신호는 이 훅이 아니라 응답 크기가 먼저 알려 준다.
 *
 * <h4>페이지 번호를 URL 에 두는 이유</h4>
 * 이 앱의 규칙이 "URL 이 곧 화면" 이다(`router/routes.js`). 목록에서 [보기]로 상세에 들어갔다
 * [← 목록] 으로 돌아오는 흐름이 가장 잦은데, 화면 state 로 두면 <b>그때마다 1페이지로 떨어진다.</b>
 *
 * ⚠️ `page` 는 state 가 아니라 <b>파생값</b>이다. 검색으로 행이 줄면 그 자리에서 마지막 페이지로
 * 접힌다 — effect 로 고치면 <b>빈 표가 한 번 그려진 뒤</b> 고쳐진다.
 */
export function usePaging(rows, { resetKey = '' } = {}) {
  const { query, setQuery } = useRouter()
  const total = rows.length

  const askedSize = Number(query.get('size'))
  const size = PAGE_SIZES.includes(askedSize) ? askedSize : PAGE_SIZES[0]
  const pageCount = Math.max(1, Math.ceil(total / size))
  const askedPage = Math.trunc(Number(query.get('p'))) || 1
  const page = Math.min(Math.max(1, askedPage), pageCount)

  /**
   * 접힌 결과를 URL 에도 반영한다 — 어긋난 채로 두면 그 주소를 복사해 준 사람은 다른 화면을 본다.
   *
   * ⚠️ **행이 0건이면 손대지 않는다.** 목록은 불러오기 전에도 0건이라, 이 조건이 없으면
   * 화면이 뜨는 순간 `?p=2` 를 "없는 페이지" 로 보고 지운다 — 상세에 들어갔다 [← 목록] 으로
   * 돌아오면 1페이지로 떨어졌다(2026-09-10, 브라우저에서 확인). 0건은 '틀린 페이지' 가 아니라
   * '아직 모른다' 이다.
   */
  useEffect(() => {
    if (total && askedPage !== page) setQuery({ p: page === 1 ? null : page })
  }, [total, askedPage, page, setQuery])

  // 검색어·상태 필터가 바뀌면 **다른 목록**이다. 3페이지에 남아 있을 이유가 없다.
  const lastKey = useRef(resetKey)
  useEffect(() => {
    if (lastKey.current === resetKey) return
    lastKey.current = resetKey
    if (askedPage !== 1) setQuery({ p: null })
  }, [resetKey, askedPage, setQuery])

  const goto = useCallback((n) => setQuery({ p: n <= 1 ? null : n }), [setQuery])

  // 건수를 바꾸면 "몇 페이지였는지" 는 뜻을 잃는다 — 함께 1페이지로 돌린다
  const setSize = useCallback(
    (n) => setQuery({ size: n === PAGE_SIZES[0] ? null : n, p: null }), [setQuery])

  const pageRows = useMemo(
    () => rows.slice((page - 1) * size, page * size), [rows, page, size])

  return {
    pageRows, page, pageCount, size, sizes: PAGE_SIZES, total,
    from: total ? (page - 1) * size + 1 : 0,
    to: Math.min(total, page * size),
    goto, setSize
  }
}
