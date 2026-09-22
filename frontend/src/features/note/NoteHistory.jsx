import { useCallback, useEffect, useState } from 'react'
import Icon from '../../ui/Icon.jsx'
import { api } from '../../api/endpoints.js'
import { useSession } from '../../session/SessionContext.jsx'
import { fmtDT } from '../../domain/format.js'
import NoteRead from './NoteRead.jsx'

/**
 * 공유 이력 팝업 — **왼쪽 목록 / 오른쪽 상세(읽기 전용)**.
 *
 * ⚠️ **본문 화면을 과거본으로 갈아끼우지 않는 것이 이 설계의 핵심이다**(사용자 결정 2026-09-09).
 * 처음 스펙은 이력을 고르면 본문 화면 자체가 그 내용으로 바뀌는 것이었는데, 그러면
 *  · 이전 공유본을 본 채 [수정]→[공유] 하면 **옛 내용이 최신 공유본이 되고**
 *  · SDE 가 그 화면을 **최신으로 착각**한다.
 * 과거본이 이 팝업 안에서만, `n차 공유` 라벨을 달고 존재하면 두 경로가 아예 없어진다.
 *
 * `onLoad` 가 있으면(= SME 편집 가능) 하단에 **[불러오기]** 가 뜬다 — 오클릭 방지 확인을 거친다.
 * SDE·리더에게는 넘기지 않으므로 목록·상세·닫기뿐이다.
 */
export default function NoteHistory({ reqNo, onLoad, onClose, canEditNow }) {
  const { run } = useSession()
  const [rows, setRows] = useState(null)
  const [seq, setSeq] = useState(null)
  const [detail, setDetail] = useState(null)
  const [confirming, setConfirming] = useState(false)
  const [err, setErr] = useState('')

  // 목록 — 열면 **최신(=현재 공유본)이 선택된 상태**로 시작한다
  useEffect(() => {
    let alive = true
    run(() => api.noteRevisions(reqNo), { silent: true })
      .then(list => { if (!alive) return; setRows(list); setSeq(list[0]?.seq ?? null) })
      .catch(e => { if (alive && e?.name !== 'AbortError') setErr('공유 이력을 불러오지 못했습니다.') })
    return () => { alive = false }
  }, [reqNo, run])

  // 상세 — 고른 것만 본문을 읽는다(목록은 미리보기만 갖고 있다)
  useEffect(() => {
    if (seq == null) return
    let alive = true
    setDetail(null)
    run(() => api.noteRevision(reqNo, seq), { silent: true })
      .then(d => { if (alive) setDetail(d) })
      .catch(e => { if (alive && e?.name !== 'AbortError') setErr('공유 내용을 불러오지 못했습니다.') })
    return () => { alive = false }
  }, [reqNo, seq, run])

  // Esc 로 닫힌다 — 확인 중이면 확인만 먼저 닫는다
  const onKey = useCallback((e) => {
    if (e.key !== 'Escape') return
    if (confirming) setConfirming(false); else onClose()
  }, [confirming, onClose])
  useEffect(() => {
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [onKey])

  const picked = rows?.find(r => r.seq === seq)

  return (
    <div className="modal-back" onMouseDown={(e) => { if (e.target === e.currentTarget) onClose() }}>
      <div className="modal nh" role="dialog" aria-label="분석 노트 공유 이력">
        <div className="hd">
          <div className="hd-l"><h3>공유 이력</h3>
            {rows && <span className="cnt">{rows.length}회 공유</span>}</div>
          <button type="button" className="modal-x" onClick={onClose} aria-label="닫기">×</button>
        </div>

        {err && <div className="dw-note done-note"><Icon name="info" /><span>{err}</span></div>}

        <div className="nh-body">
          <ul className="nh-list">
            {!rows && <li className="nh-empty">불러오는 중…</li>}
            {rows?.length === 0 && <li className="nh-empty">아직 공유된 적이 없습니다.</li>}
            {rows?.map(r => (
              <li key={r.seq}>
                <button type="button" className={'nh-item' + (r.seq === seq ? ' on' : '')}
                  onClick={() => setSeq(r.seq)}>
                  <span className="nh-top">
                    <b>{r.seq}차</b>
                    <span className="nh-dt">{fmtDT(r.publishedAt)}</span>
                    {r.current && <span className="rank-badge">현재</span>}
                  </span>
                  <span className="nh-memo">{r.publishMemo || (r.seq === 1 ? '(최초 공유)' : '(사유 없음)')}</span>
                  <span className="nh-prev">{r.preview}</span>
                </button>
              </li>
            ))}
          </ul>

          <div className="nh-detail">
            {/* ⚠️ 어느 것을 보고 있는지가 화면에서 사라지면 안 된다 — 착각이 여기서 생긴다 */}
            {detail ? (
              <>
                <div className="nh-head">
                  <b>{detail.seq}차 공유</b> · {fmtDT(detail.publishedAt)} · {detail.authorName || '작성자 미상'}
                  {detail.current
                    ? <span className="rank-badge">현재 공유본</span>
                    : <span className="rank-badge unset">최신 아님</span>}
                  {detail.publishMemo && <div className="nh-head-memo">“{detail.publishMemo}”</div>}
                </div>
                <NoteRead delta={detail.bodyDelta} />
              </>
            ) : <div className="nh-empty">{seq == null ? '왼쪽에서 공유 건을 고르세요.' : '불러오는 중…'}</div>}
          </div>
        </div>

        <div className="nh-foot">
          {/* 오클릭 방지 — 확인을 그 자리에서 받는다. 문구가 곧 기능이다 */}
          {confirming ? (
            <span className="nh-confirm">
              <b>{picked?.seq}차 공유본</b>({fmtDT(picked?.publishedAt)})을 편집기로 불러옵니다.
              {' '}{canEditNow ? '작성 중인 내용은 사라집니다.' : '수정 상태로 전환됩니다.'}
              <button type="button" className="btn-xlsx"
                onClick={() => { onLoad(detail.bodyDelta, detail.seq); onClose() }}>예, 불러옵니다</button>
              <button type="button" className="btn-view" onClick={() => setConfirming(false)}>취소</button>
            </span>
          ) : (
            <>
              {onLoad && <button type="button" className="btn-xlsx" disabled={!detail}
                onClick={() => setConfirming(true)}>불러오기</button>}
              <button type="button" className="btn-view" onClick={onClose}>닫기</button>
            </>
          )}
        </div>
      </div>
    </div>
  )
}
