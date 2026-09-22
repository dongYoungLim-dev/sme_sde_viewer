import { useState } from 'react'
import Avatar from '../ui/Avatar.jsx'
import Icon from '../ui/Icon.jsx'
import Person from '../ui/Person.jsx'
import StatusPill from '../ui/StatusPill.jsx'
import { EmptyCard } from '../ui/EmptyState.jsx'
import NoteEditor from '../features/note/NoteEditor.jsx'
import CommentPanel from '../features/comment/CommentPanel.jsx'
import ScheduleModal from '../features/schedule/ScheduleModal.jsx'
import AttachmentPanel from '../features/attachment/AttachmentPanel.jsx'
import { api } from '../api/endpoints.js'
import { useRequestDetail } from '../hooks/useRequests.js'
import { useSession } from '../session/SessionContext.jsx'
import { useRouter } from '../router/RouterContext.jsx'
import { pathTo } from '../router/routes.js'
import { ASTAGE, STATUS } from '../domain/status.js'
import { todoTag, workTypeLabel } from '../domain/request.js'
import { ago, daysTo, fmtDT, fmtMD, fmtYMD } from '../domain/format.js'

const SCHED_KIND_LABEL = { REVISED: '수정', CANCELLED: '취소' }

/**
 * 요청 상세 — 드로어가 아니라 **페이지**다(2026-09-08).
 * 분석 노트(요구사항 정의서) 에디터가 들어오면서 드로어 폭으로는 모자랐다.
 * 왼쪽이 노트(주 작업물), 오른쪽이 ITSM 원본 정보다.
 *
 * 이제 URL 이 `/requests/{reqNo}` 라 **새로고침해도 이 화면이고, 링크를 그대로 줄 수 있다.**
 */
export default function RequestDetailPage() {
  const { params, back } = useRouter()
  const { refreshData } = useSession()
  const { detail } = useRequestDetail(params.reqNo)

  const [schedOpen, setSchedOpen] = useState(false)

  const r = detail?.request
  if (!r) return <EmptyCard>불러오는 중…</EmptyCard>

  const priColor = r.priority === '긴급' ? 'var(--danger)' : r.priority === '높음' ? 'var(--warn)' : 'var(--ink-3)'
  const dtl = daysTo(r.dueDate)
  const tag = todoTag(r)
  const schedHistory = (detail.schedules || []).filter(s => s.status !== 'ACTIVE')

  return (
    <>
      <div className="detail-hd">
        <button className="btn-view" onClick={() => back(pathTo.list())}>← 목록</button>
        <div><div className="rn mono sel">{r.reqNo} · {r.reqCompNm || r.dept}</div>
          <h2 className="sel">{r.title}</h2></div>
        {tag && <div className={'tag-closed ' + tag.k} title={tag.t}>
          {tag.l}{r.closedAt ? ` · ${fmtDT(r.closedAt)}` : ''}</div>}
      </div>

      <div className="detail-grid">
        <div className="detail-main">
          {/* 노트를 저장하면 목록의 '노트' 배지도 달라진다 — 판을 올려 다른 화면까지 맞춘다 */}
          <NoteEditor reqNo={params.reqNo} onSaved={refreshData} />
          {/* 노트 **아래**다. 정본(노트)을 읽다가 되묻는 자리라 순서가 뜻을 만든다 —
              위가 SME 가 쓴 요구사항, 아래가 그것을 두고 오간 말이다. */}
          <CommentPanel reqNo={params.reqNo} />
        </div>
        <div className="detail-side">
          <div className="dw-note"><Icon name="info" /><span>현업이 ITSM에 입력한 <b>요청 상세를 그대로 미러링</b>해 보여줍니다. SME는 할당 후 ITSM에서 이 건을 조회할 수 없으므로, 이 화면이 상세 확인 수단입니다. (읽기 전용)</span></div>

          <div><div className="sect-t">할당 단계</div>
            <div className="assign-path">
              <Avatar perId={r.reqNo} name={r.requesterName} /><span>{r.requesterName} <em>요청자</em></span><span className="ap-arw">→</span>
              {r.leaderName
                ? <><Avatar perId={r.leaderPerId} name={r.leaderName} /><span>{r.leaderName} <em>리더</em></span></>
                : <span className="ap-wait">리더 미확인</span>}
              <span className="ap-arw">→</span>
              {r.assigneeName
                ? <><Avatar perId={r.assigneePerId || r.assigneeName} name={r.assigneeName} /><span>{r.assigneeName} <em>담당</em></span></>
                : <span className="ap-wait">담당자 배정 대기</span>}
            </div>
          </div>

          <div><div className="sect-t">요청 상세 · 현업이 ITSM에 입력한 내용</div>
            <div className="itsm-detail">
              <dl className="kv">
                <dt>요청번호</dt><dd className="mono sel">{r.reqNo}</dd>
                <dt>요청 제목</dt><dd className="sel">{r.title}</dd>
                <dt>법인</dt><dd>{r.reqCompNm || r.dept}</dd>
                <dt>요청 유형</dt><dd>{r.reqType} <span style={{ color: 'var(--ink-3)' }}>· {workTypeLabel(r)}</span></dd>
                <dt>요청자</dt><dd>{r.requester || r.requesterName}</dd>
                <dt>요청 분류</dt><dd>{r.targetSystem}</dd>
                <dt>우선순위</dt><dd><b style={{ color: priColor }}>{r.priority}</b></dd>
                <dt>등록일</dt><dd className="mono num">{fmtYMD(r.reqDt)}</dd>
                <dt>희망 완료일</dt><dd className="mono num">{fmtYMD(r.dueDate)}</dd>
              </dl>
              <div className="detail-body"><div className="db-label">상세 내용</div>
                <p>{r.body || '목록 API에는 요청 원문이 없습니다 — 상세 API 연동 후 표시됩니다.'}</p></div>
            </div>
          </div>

          <div><div className="sect-t">처리·할당 현황 · 우리 시스템</div>
            <dl className="kv">
              <dt>작업 상태</dt><dd><StatusPill st={r.workStatus} label={r.itsmStaNm} /> <span className="mono" style={{ color: 'var(--ink-3)', fontSize: 11 }}>{r.itsmStaCd ? `ITSM ${r.itsmStaCd}` : ''}</span></dd>
              <dt>할당 상태</dt><dd><b style={{ color: r.assignStage === 'FINAL' ? 'var(--good)' : 'var(--st-test)' }}>{ASTAGE[r.assignStage]?.l}</b> · {ASTAGE[r.assignStage]?.d}</dd>
              <dt>담당 리더</dt><dd>{r.leaderPerId ? <Person perId={r.leaderPerId} name={r.leaderName} /> : <span style={{ color: 'var(--ink-3)' }}>미지정</span>}</dd>
              <dt>담당자</dt><dd>{r.assigneeName ? <Person perId={r.assigneePerId || r.assigneeName} name={r.assigneeName} /> : <span style={{ color: 'var(--ink-3)' }}>배정 대기</span>}</dd>
              <dt>기한</dt><dd><span className={'due ' + (r.late ? 'late' : '')}>{fmtMD(r.dueDate)} {r.workStatus === 'DONE' ? '(완료)' : (r.late ? `· ${-dtl}일 지연` : `· ${dtl === 0 ? '오늘' : dtl + '일 남음'}`)}</span></dd>
            </dl>
          </div>

          <div><div className="sect-t">처리 일정</div>
            {r.schedule ? (
              <dl className="kv">
                <dt>일시</dt><dd>{fmtDT(r.schedule.start)} ~ {fmtDT(r.schedule.end)}</dd>
                <dt>작업자</dt><dd>{r.schedule.assigneeName || '-'}</dd>
              </dl>
            ) : r.workStatus === 'UNTRACKED' ? (
              <div className="dw-note"><Icon name="warn" /><span>
                스케줄 없이 ITSM 목록에서 사라졌습니다 — 처리가 끝난 건이면 작업완료로 확정하세요.</span></div>
            ) : <p className="att-empty">등록된 일정이 없습니다.</p>}
            {schedHistory.length > 0 &&
              <ul className="att-list" style={{ marginTop: 8 }}>
                {schedHistory.map(s => (
                  <li className="att" key={s.id} style={{ alignItems: 'flex-start' }}>
                    <Icon name="clock" />
                    <span style={{ flex: 1, fontSize: 11.5, fontWeight: 500, color: 'var(--ink-2)', lineHeight: 1.5 }}>
                      <b>{SCHED_KIND_LABEL[s.status] || s.status}</b> · {fmtDT(s.start)} ~ {fmtDT(s.end)} · {s.assigneeName}
                      {s.reason && <><br />“{s.reason}”</>}
                      <br /><span className="as">{s.closedByName} · {fmtDT(s.closedAt)}</span>
                    </span>
                  </li>
                ))}
              </ul>}
            {r.workStatus !== 'DONE' &&
              <button type="button" className="btn-view soft" style={{ marginTop: 8 }}
                onClick={() => setSchedOpen(true)}>
                {r.schedule ? '일정 관리' : r.workStatus === 'UNTRACKED' ? '확인하기' : '일정 등록'}</button>}
          </div>

          <div><div className="sect-t">상태 변화 타임라인</div>
            <div className="tl" style={{ padding: 0 }}>
              {(detail.history.length ? detail.history : [{ toStatus: r.workStatus, toStaNm: r.itsmStaNm, actorName: r.assigneeName, observedAt: null }]).map((h, i) => (
                <div className="tl-item" key={i}><span className="tl-dot" style={{ background: `var(${STATUS[h.toStatus]?.v})` }} />
                  <div><div className="tl-t">{h.fromStatus
                    ? <><StatusPill st={h.fromStatus} label={h.fromStaNm} /> <span className="arrow">→</span> <StatusPill st={h.toStatus} label={h.toStaNm} /></>
                    : <><StatusPill st={h.toStatus} label={h.toStaNm} /> 등록</>}</div>
                    <div className="tl-m"><b>{h.actorName || '시스템'}</b> · {ago(h.observedAt) || '기록'}</div></div></div>
              ))}
            </div>
          </div>

          {/* SME 가 이 보드에 직접 올린 첨부(`UR-260922-1`) — 아래 ITSM 첨부와는 출처가 다르다 */}
          <div><div className="sect-t">첨부파일</div>
            <AttachmentPanel reqNo={params.reqNo} onChanged={refreshData} /></div>

          <div><div className="sect-t">ITSM 첨부 {detail.attachments.length ? `(${detail.attachments.length})` : ''}</div>
            {detail.attachments.length
              ? detail.attachments.map(a => (
                <a className="att" key={a.id} href={api.downloadUrl(a.id)} target="_blank" rel="noreferrer" style={{ textDecoration: 'none', color: 'inherit' }}>
                  <Icon name="file" /><span className="an">{a.fileName}</span><span className="as mono">{a.fileSize ? Math.round(a.fileSize / 1024) + ' KB' : ''}</span></a>))
              : <p className="att-empty">ITSM 원본 첨부 없음 · 첨부 API 제공 여부는 사내망 확인 후 확정</p>}
          </div>

        </div>
      </div>

      {schedOpen && <ScheduleModal reqNo={r.reqNo} title={r.title} workStatus={r.workStatus} schedule={r.schedule}
        onClose={() => setSchedOpen(false)} onSaved={refreshData} />}
    </>
  )
}
