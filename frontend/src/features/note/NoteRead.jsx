import { useEffect, useRef } from 'react'
import Quill from 'quill'

/**
 * 노트 본문 **읽기 전용 렌더러**. 편집기와 같은 Quill 을 툴바 없이 쓴다.
 *
 * ⚠️ **본문을 마크업으로 넣는 곳이 여기에도 없다.** Delta(JSON)를 `setContents` 로 넣을 뿐이라
 * `dangerouslySetInnerHTML` 도, HTML 새니타이저도 필요 없다 — 그게 Delta 를 저장하는 이유다.
 *
 * 편집기(`NoteEditor`)와 공유 이력 팝업(`NoteHistory`)이 **같은 것을 쓴다** —
 * 그래서 이력 팝업의 오른쪽 상세가 새로 만들 것 없이 공짜로 생겼다.
 */
export default function NoteRead({ delta }) {
  const box = useRef(null)
  useEffect(() => {
    if (!box.current) return
    box.current.replaceChildren()          // 다시 그리기 전 비우기 (마크업 주입 아님)
    const holder = document.createElement('div')
    box.current.appendChild(holder)
    const q = new Quill(holder, { readOnly: true, modules: { toolbar: false }, theme: 'snow' })
    try { q.setContents(JSON.parse(delta || '{"ops":[]}')) } catch { /* 형식이 깨졌으면 빈 화면 */ }
  }, [delta])
  return <div className="note-read" ref={box} />
}
