import RequestTable from './RequestTable.jsx'

/**
 * 목록 한 묶음 — 머리(제목·건수·설명) + 표. 대시보드가 '신규 유입/진행 중'으로 나눌 때 쓴다.
 *
 * ⚠️ **페이징하는 묶음은 `rows` 가 이 페이지분뿐이다.** 그래서 머리 숫자는 `count` 로 따로 받는다 —
 * 세어 놓은 값이 화면에 보이는 줄 수와 같다고 두면 "진행 중 20건" 처럼 <b>줄어든 숫자</b>가 뜬다.
 * `headRef` 는 페이지를 넘길 때 이 머리를 화면 위로 올리기 위한 것이다(카드 머리가 아니라 여기다).
 */
export default function RequestSection({ title, note, rows, onOpen, onSchedule, cls = '', count, headRef }) {
  if (!rows.length) return null
  return <>
    <div className={'sec-hd ' + cls} ref={headRef}>
      <h4>{title}</h4><span className="qty num">{count ?? rows.length}</span>
      {note && <span className="sec-note">{note}</span>}
    </div>
    <RequestTable rows={rows} onOpen={onOpen} onSchedule={onSchedule} />
  </>
}
