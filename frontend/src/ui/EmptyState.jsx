/** 표·카드 안의 빈 상태 한 줄. (같은 마크업이 네 곳에 복붙돼 있던 것을 모았다) */
export default function EmptyState({ children }) {
  return <div className="empty">{children}</div>
}

/** 카드째로 비어 있을 때 — 로딩·실패 화면. */
export function EmptyCard({ children }) {
  return <div className="card"><div className="empty">{children}</div></div>
}
