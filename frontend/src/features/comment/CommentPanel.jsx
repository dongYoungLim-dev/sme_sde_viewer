import { useCallback, useEffect, useRef, useState } from 'react'
import Avatar from '../../ui/Avatar.jsx'
import EmptyState from '../../ui/EmptyState.jsx'
import { api } from '../../api/endpoints.js'
import { useSession } from '../../session/SessionContext.jsx'
import { fmtDT } from '../../domain/format.js'
import { ROLES } from '../../domain/role.js'

/**
 * 요청건 코멘트 — **되받을 자리**.
 *
 * 분석 노트(`NoteEditor`)는 SME 가 쓰고 나머지가 읽는 **단방향**이다. 그래서 SDE 가 읽다가
 * *"이 부분이 무슨 뜻입니까"* 를 물을 곳이 없었고, SME 가 리더에게 할 말도 메신저로 나갔다.
 * 여기가 그 자리다 — **정본(노트 본문)은 SME 소유로 두고, 되묻기만 이쪽으로** 온다.
 *
 * **탭 = 대화 축.** SME 는 두 개(`리더` · `담당 SDE`), 리더·SDE 는 각자 하나만 본다.
 * ⚠️ 서버가 **내가 볼 수 있는 채널만** 실어 준다 — 여기서 감추는 게 아니다.
 * 화면에서 거르는 방식이면 개발자도구로 그대로 보인다.
 *
 * **수정·삭제가 없다(append-only).** 그래서 초안·저장 충돌·동시편집이 아예 없고,
 * 노트가 그 셋 때문에 쓴 표 셋을 여기서는 안 쓴다.
 *
 * <h4>열어 둔 채로 자동 갱신 (2026-09-14 · `UR-260914-1`)</h4>
 * 상대가 방금 남긴 글은 **서버 폴링(`lastSyncAt`)과 무관**하다 — 우리 시스템 안에서 끝나는 일이라
 * `dataVersion` 갱신 신호를 안 탄다. 그래서 이 패널만 따로 15초 주기로 다시 읽는다.
 * ⚠️ 새로 온 글이 있어도 **화면을 강제로 스크롤하지 않는다** — 옛 대화를 읽던 사람이 튕겨나가면 안 된다
 * (자동 스크롤은 `send()`, 즉 내가 직접 남길 때만).
 */
export default function CommentPanel({ reqNo }) {
  const { run, refreshData } = useSession()
  const [data, setData] = useState(null)
  const [tab, setTab] = useState(null)
  const [text, setText] = useState('')
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState('')
  const listEnd = useRef(null)

  const load = useCallback(async (signal) => {
    const res = await api.comments(reqNo, signal)
    setData(res)
    // 처음 열 때는 **안 본 글이 있는 탭**을 고른다. 그게 이 화면에 온 이유다.
    setTab(t => t || (res.channels.find(c => c.unread > 0) || res.channels[0])?.channel || null)
  }, [reqNo])

  useEffect(() => {
    const ac = new AbortController()
    load(ac.signal).catch(() => { /* 배너에 이미 떴다 */ })
    return () => ac.abort()
  }, [load])

  /** 15초마다 조용히 다시 읽는다 — 실패해도 배너 없이 다음 주기에 다시 시도한다(폴링이라 되풀이될 것) */
  useEffect(() => {
    const t = setInterval(() => {
      const ac = new AbortController()
      load(ac.signal).catch(() => { /* 다음 주기에 다시 시도 */ })
    }, 15000)
    return () => clearInterval(t)
  }, [load])

  // 탭을 연 순간이 '읽음' 이다. ⚠️ 조회가 아니라 이 호출이 찍는다 —
  // 조회에서 찍으면 열지도 않은 반대쪽 탭의 배지까지 같이 꺼진다.
  useEffect(() => {
    if (!tab || !data) return
    const ch = data.channels.find(c => c.channel === tab)
    if (!ch || !ch.unread) return
    api.readComments(reqNo, tab)
      .then(() => refreshData())      // 목록의 `새 댓글` 배지도 같이 꺼진다
      .catch(() => { /* 배지가 한 번 더 뜰 뿐이다 */ })
  }, [tab, data, reqNo, refreshData])

  if (!data) return null
  if (!data.channels.length) return null          // 코멘트를 쓸 수 있는 역할이 아니다

  const active = data.channels.find(c => c.channel === tab) || data.channels[0]

  const send = async () => {
    const body = text.trim()
    if (!body || busy) return
    setBusy(true); setErr('')
    try {
      const res = await run(() => api.writeComment(reqNo, active.channel, body))
      setData(res)
      setText('')
      refreshData()
      requestAnimationFrame(() => listEnd.current?.scrollIntoView({ block: 'nearest' }))
    } catch (e) {
      setErr(e?.message || '남기지 못했습니다.')
    } finally { setBusy(false) }
  }

  return (
    <div className="card cmt">
      <div className="hd"><h3>코멘트</h3>
        <span className="cnt">{data.writable ? '수정·삭제 없음 · 남긴 순서대로' : '완료 · 읽기 전용'}</span></div>

      {/* 탭이 하나뿐인 역할(리더·SDE)에게도 띄운다 — 지금 어느 대화에 쓰고 있는지가 보여야 한다 */}
      <div className="cmt-tabs" role="tablist">
        {data.channels.map(c => (
          <button key={c.channel} role="tab" aria-selected={c.channel === active.channel}
            className={c.channel === active.channel ? 'on' : ''}
            onClick={() => { setTab(c.channel); setErr('') }}>
            {c.label}{c.unread > 0 && <b className="cmt-unread">{c.unread}</b>}
          </button>
        ))}
      </div>
      <p className="cmt-hint">{active.hint}</p>

      <div className="cmt-list">
        {!active.comments.length
          ? <EmptyState>{data.writable ? '아직 오간 말이 없습니다.' : '오간 말 없이 완료됐습니다.'}</EmptyState>
          : active.comments.map(c => (
            <div key={c.id} className={'cmt-row' + (c.mine ? ' mine' : '')}>
              <Avatar perId={c.authorName} name={c.authorName} />
              <div className="cmt-b">
                <div className="cmt-m"><b>{c.authorName}</b>
                  <span className="cmt-role">{ROLES[c.authorRole]?.l || c.authorRole}</span>
                  <span className="cmt-t">{fmtDT(c.createdAt)}</span></div>
                {/* 평문이다 — 서식도 마크업도 없어서 그리는 쪽에 주입 경로가 없다 */}
                <div className="cmt-x">{c.body}</div>
              </div>
            </div>
          ))}
        <div ref={listEnd} />
      </div>

      {/* ⚠️ **완료 여부를 화면이 다시 판단하지 않는다.** 서버가 `writable` 과 그 이유를 함께 준다 —
          여기서 계산하면 서버와 어긋나는 순간 쓸 수 있는 것처럼 보이다가 등록에서 실패한다. */}
      {!data.writable
        ? <p className="cmt-locked">{data.readOnlyReason}</p>
        : <div className="cmt-write">
        <textarea value={text} maxLength={2000} disabled={busy}
          placeholder={`${active.label} — 한 줄 남기기 (Ctrl+Enter 로 등록)`}
          onChange={e => setText(e.target.value)}
          onKeyDown={e => { if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) send() }} />
        <div className="cmt-actions">
          {err && <span className="cmt-err">{err}</span>}
          <span className="cmt-len">{text.length}/2000</span>
          <button className="btn-xlsx" onClick={send} disabled={busy || !text.trim()}>
            {busy ? '남기는 중…' : '남기기'}</button>
        </div>
        </div>}
    </div>
  )
}
