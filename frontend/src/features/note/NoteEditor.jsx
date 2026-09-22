import { useEffect, useRef, useState } from 'react'
import Quill from 'quill'
import 'quill/dist/quill.snow.css'
import { api } from '../../api/endpoints.js'
import { useSession } from '../../session/SessionContext.jsx'
import { useAsyncData } from '../../hooks/useAsyncData.js'
import { fmtDT } from '../../domain/format.js'
import { EmptyCard } from '../../ui/EmptyState.jsx'
import NoteRead from './NoteRead.jsx'
import NoteHistory from './NoteHistory.jsx'

/**
 * 요청 분석 노트 — SME 가 쓰는 **요구사항 정의서**, SDE 가 읽는다.
 *
 * 마크다운을 쓰지 않는 이유: 현업·SME 가 문법을 모르는 경우가 많다(사용자 결정 2026-09-08).
 *
 * ⚠️ **본문은 HTML 이 아니라 Quill Delta(JSON) 로 오간다.** 리치 에디터의 HTML 을 저장했다가
 * 그리면 `dangerouslySetInnerHTML` + 서버 HTML 새니타이저가 필요해지고, 그 자체가 주입 경로가 된다.
 * 구조화 포맷만 오가면 그 경로가 없다 — 이 파일 어디에도 **본문을 마크업으로 넣는 곳이 없는** 이유다.
 *
 * <h4>2026-09-09 · 임시저장 · 공유 이력 (`UR-260909-6`)</h4>
 * 저장이 둘로 갈렸다 — **[임시저장]**(나만 본다) / **[공유]**(SDE·리더가 본다).
 * 화면 상태는 셋뿐이고 **서버가 `note.state` 로 알려준다**(`EMPTY`·`READ`·`EDIT`).
 * 화면이 초안 유무를 다시 판정하지 않는 이유: 초안은 편집 권한자에게만 응답에 실리므로
 * 판정 규칙이 두 벌이 되면 **권한과 화면이 어긋난다.**
 *
 * ⚠️ **공유하면 초안이 비워진다**(서버). 안 그러면 "초안 있으면 편집 상태" 규칙에 걸려
 * 공유 직후 다시 편집기로 튄다.
 */

// 이미지·비디오는 1단계에서 뺐다 — 붙이는 순간 파일 저장·용량·백업이 따라온다
const TOOLBAR = [
  [{ header: [1, 2, 3, false] }],
  ['bold', 'italic', 'underline', 'strike'],
  [{ list: 'ordered' }, { list: 'bullet' }, { indent: '-1' }, { indent: '+1' }],
  ['blockquote', 'code-block', 'link'],
  ['clean']
]

export default function NoteEditor({ reqNo, onSaved }) {
  const { run } = useSession()
  const { data: loaded } = useAsyncData(sig => api.note(reqNo, sig), [reqNo])
  const [saved, setSaved] = useState(null)      // 저장으로 갱신된 최신본
  const [editing, setEditing] = useState(false) // [수정]/[작성] 으로 연 임시 편집(초안 저장 전)
  const [history, setHistory] = useState(false)
  const [publishing, setPublishing] = useState(false)  // 변경 사유 입력 중
  const [memo, setMemo] = useState('')
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState('')
  const [msgBad, setMsgBad] = useState(true)   // 같은 자리에 안내와 실패를 함께 쓴다
  const host = useRef(null)
  const quill = useRef(null)
  // 이력에서 불러온 본문이 대기하는 자리. 편집기가 아직 없을 때(텍스트 상태에서 눌렀을 때)
  // setTimeout 으로 밀어 넣으면 **에디터 생성 effect 보다 먼저 뛸 수 있다** — 그래서 ref 로 넘긴다.
  const pending = useRef(null)

  const note = saved || loaded
  // 서버가 준 상태가 기준이다. `editing` 은 **아직 초안이 없는 동안만** 편집기를 여는 임시 스위치다.
  const mode = note?.state === 'EDIT' || editing ? 'EDIT' : (note?.state || 'EMPTY')
  const hasHistory = (note?.revisionCount || 0) > 0

  // 요청이 바뀌면 편집 상태와 지난 저장본을 버린다(다른 건의 노트를 이어서 쓰지 않게)
  useEffect(() => {
    setSaved(null); setEditing(false); setHistory(false); setPublishing(false); setMemo(''); setMsg('')
    pending.current = null
  }, [reqNo])

  // 편집 모드에 들어갈 때만 에디터를 만든다(읽기만 하는 사람에겐 툴바가 뜨지 않는다).
  // 초안이 있으면 **초안을**, 없으면 공유본을 이어서 쓴다.
  useEffect(() => {
    if (mode !== 'EDIT' || !host.current) return
    host.current.replaceChildren()
    const holder = document.createElement('div')
    host.current.appendChild(holder)
    const q = new Quill(holder, {
      theme: 'snow',
      placeholder: '현업 요청을 분석한 내용을 적습니다 — 무엇을, 어디를, 어떤 조건에서 바꿔야 하는지.',
      modules: { toolbar: TOOLBAR }
    })
    // 불러오기로 대기 중인 본문 > 초안 > 공유본 순서로 이어서 쓴다
    const start = pending.current || note?.draftDelta || note?.bodyDelta || '{"ops":[]}'
    pending.current = null
    try { q.setContents(JSON.parse(start)) } catch { /* 무시 */ }
    quill.current = q
    return () => { quill.current = null }
  }, [mode, reqNo])

  const write = async (fn, fallback) => {
    setBusy(true); setMsg(''); setMsgBad(true)
    try {
      const next = await run(fn, { silent: true })
      setSaved(next); onSaved?.(next)
      return next
    } catch (e) {
      // 409 = 내가 열어 둔 사이에 다른 SME 가 저장했다
      if (e?.name !== 'AbortError') { setMsgBad(true); setMsg(e.body?.message || fallback) }
      return null
    } finally { setBusy(false) }
  }

  // getContents() 는 Delta 객체다 — HTML 로 바꾸지 않고 그대로 보낸다
  const currentDelta = () => JSON.stringify(quill.current.getContents())

  const saveDraft = async () => {
    if (!quill.current) return
    await write(() => api.saveNote(reqNo, currentDelta(), note?.updatedAt ?? null, 'DRAFT'),
      '임시저장하지 못했습니다.')
    setEditing(false)          // 이제 초안이 있으므로 서버 상태(EDIT)가 편집기를 유지한다
  }

  const publish = async () => {
    if (!quill.current) return
    // 2차 공유부터는 변경 사유가 필수다 — 읽는 사람이 바뀐 줄 모르면 이전 내용으로 작업하게 된다
    const next = await write(
      () => api.saveNote(reqNo, currentDelta(), note?.updatedAt ?? null, 'PUBLISH', memo.trim() || null),
      '공유하지 못했습니다.')
    if (!next) return
    setEditing(false); setPublishing(false); setMemo('')
  }

  const discard = async () => {
    await write(() => api.discardNoteDraft(reqNo), '초안을 삭제하지 못했습니다.')
    setEditing(false)
  }

  /**
   * 이력에서 불러오기 — **편집기 버퍼만** 바꾼다. DB 초안은 [임시저장]/[공유] 전까지 그대로다.
   * 그래서 잘못 불러왔으면 **저장하지 말고 새로고침**하면 초안이 그대로 돌아온다.
   */
  const loadRevision = (delta, seq) => {
    const body = delta || '{"ops":[]}'
    if (quill.current) {
      try { quill.current.setContents(JSON.parse(body)) } catch { /* 무시 */ }
    } else {
      // 텍스트 상태에서 눌렀다 → 수정 상태로 전환한다(확인창이 그렇게 말했다).
      // 넣는 것은 에디터를 만드는 effect 가 `pending` 에서 집어 간다 — 순서 다툼이 없다.
      pending.current = body
      setEditing(true)
    }
    setMsgBad(false)
    setMsg(`${seq}차 공유본을 불러왔습니다 — 저장하기 전까지 반영되지 않습니다.`)
  }

  if (!note) return <EmptyCard>노트를 불러오는 중…</EmptyCard>

  return (
    <div className="card pop note-card">
      <div className="hd">
        <div className="hd-l"><h3>분석 노트 · 요구사항 정의서</h3>
          <span className="cnt">
            {note.hasContent
              ? <>{note.authorName || '작성자 미상'} · {fmtDT(note.publishedAt || note.updatedAt)} 공유</>
              : '아직 작성되지 않았습니다'}</span></div>
        <div className="hd-tools">
          {msg && <span className={msgBad ? 'x-err' : 'cnt'}>{msg}</span>}
          {hasHistory && (
            <button className="btn-view" onClick={() => setHistory(true)}>공유 이력</button>)}
          {mode === 'EDIT' && note.editable && (
            <>
              {note.state === 'EDIT' && (
                <button className="btn-view" onClick={discard} disabled={busy}>초안 삭제</button>)}
              {note.state !== 'EDIT' && (
                <button className="btn-view" onClick={() => { setEditing(false); setMsg('') }}
                  disabled={busy}>취소</button>)}
              <button className="btn-view" onClick={saveDraft} disabled={busy}>
                {busy ? '저장 중…' : '임시저장'}</button>
              <button className="btn-xlsx" onClick={() => setPublishing(true)} disabled={busy}>공유</button>
            </>
          )}
          {mode !== 'EDIT' && note.editable && (
            <button className="btn-xlsx" onClick={() => setEditing(true)}>
              {note.hasContent ? '수정' : '작성하기'}</button>
          )}
        </div>
      </div>

      {/* 초안이 있다는 사실은 항상 보여야 한다 — 안 그러면 "고쳐서 저장했는데 SDE 가 못 봤다" 가 재발한다 */}
      {note.state === 'EDIT' && (
        <div className="dw-note note-draft">
          <span>✎ 공유되지 않은 작성 중인 내용입니다 — SDE·리더에게는 아직
            {note.hasContent ? ' 이전 공유본이 보입니다.' : ' 아무것도 보이지 않습니다.'}</span>
          <span className="nd-at">임시저장 {fmtDT(note.draftUpdatedAt)}</span>
        </div>
      )}

      {/* 공유 = 되돌릴 수 없는 순간이다. 2차부터는 무엇을 바꿨는지 한 줄을 여기서 받는다 */}
      {publishing && (
        <div className="dw-note note-publish">
          <label htmlFor="note-memo">
            {hasHistory ? '무엇을 바꿨는지 한 줄 남겨 주세요 (필수)' : '이 내용을 SDE·리더에게 공유합니다'}
          </label>
          {hasHistory && (
            <input id="note-memo" className="inp" maxLength={300} autoFocus value={memo}
              placeholder="예) 조회 테이블 TB_A → TB_B 로 변경"
              onChange={e => setMemo(e.target.value)}
              onKeyDown={e => { if (e.key === 'Enter' && memo.trim()) publish() }} />
          )}
          <button className="btn-xlsx" onClick={publish} disabled={busy || (hasHistory && !memo.trim())}>
            {busy ? '공유 중…' : '공유'}</button>
          <button className="btn-view" onClick={() => { setPublishing(false); setMsg('') }}
            disabled={busy}>취소</button>
        </div>
      )}

      {!note.editable && !note.hasContent && (
        <div className="empty">SME 가 아직 분석 내용을 적지 않았습니다.</div>
      )}
      {mode !== 'EDIT' && note.hasContent && <NoteRead delta={note.bodyDelta} />}
      {mode === 'EDIT' && <div className="note-edit" ref={host} />}

      {history && (
        <NoteHistory reqNo={reqNo} canEditNow={mode === 'EDIT'}
          onLoad={note.editable ? loadRevision : null}
          onClose={() => setHistory(false)} />
      )}
    </div>
  )
}
