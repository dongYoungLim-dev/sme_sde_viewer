import Select from './Select.jsx'

/** 현재 페이지 좌우로 함께 보여줄 번호 개수. 1 이면 번호칸이 최대 7개(`1 … 4 5 6 … 12`)다. */
const AROUND = 1

/** 번호칸 — 처음·끝·현재 주변만 남기고 끊긴 자리는 `…` 하나로 접는다(머리가 한 줄을 넘지 않게). */
function items(page, count) {
  const keep = new Set([1, count])
  for (let n = page - AROUND; n <= page + AROUND; n++) if (n >= 1 && n <= count) keep.add(n)
  const ns = [...keep].sort((a, b) => a - b)
  const out = []
  ns.forEach((n, i) => {
    if (i && n - ns[i - 1] > 1) out.push({ gap: true, key: 'g' + n })
    out.push({ n, key: n })
  })
  return out
}

/**
 * 목록 페이저 — 표 아래 한 줄. 상태는 갖지 않는다(`hooks/usePaging.js` 가 URL 로 들고 있다).
 *
 * ⚠️ **한 페이지에 다 들어오는 목록에는 뜨지 않는다**(`total <= sizes[0]`).
 * 12건짜리 목록 아래의 페이저는 정보가 아니라 소음이다.
 * 반대로 <b>페이지가 한 장뿐이어도 건수는 줄일 수 있어야</b> 하므로 `pageCount` 로 판단하지 않는다 —
 * 100건씩 보기로 해 둔 뒤에는 페이지가 한 장이라, 그걸로 숨기면 20건씩 보기로 되돌아갈 문이 사라진다.
 */
export default function Pager({ page, pageCount, size, sizes, total, from, to, onPage, onSize }) {
  if (total <= sizes[0]) return null
  return (
    <nav className="pager" aria-label="목록 페이지 이동">
      <span className="pg-range num">{from}–{to}<span className="pg-of"> / {total}건</span></span>
      <div className="pg-nums">
        <button type="button" className="pg-b" onClick={() => onPage(page - 1)}
          disabled={page <= 1} aria-label="이전 페이지">‹</button>
        {items(page, pageCount).map(it => it.gap
          ? <span key={it.key} className="pg-gap" aria-hidden="true">…</span>
          : <button key={it.key} type="button" className="pg-b num"
            aria-current={it.n === page ? 'page' : undefined}
            aria-label={`${it.n}페이지`} onClick={() => onPage(it.n)}>{it.n}</button>)}
        <button type="button" className="pg-b" onClick={() => onPage(page + 1)}
          disabled={page >= pageCount} aria-label="다음 페이지">›</button>
      </div>
      {/* 드롭다운은 이 앱에 하나뿐이다(`ui/Select.jsx`). 표 아래라 위로 열린다(`up`). */}
      <Select options={sizes.map(n => ({ key: String(n), label: `${n}건` }))}
        value={String(size)} onChange={(k) => onSize(Number(k))}
        caption="페이지당" ariaLabel="페이지당 표시 건수" up />
    </nav>
  )
}
