import AssignCell from './AssignCell.jsx'
import ProgressCell from './ProgressCell.jsx'
import EmptyState from '../../ui/EmptyState.jsx'
import { fmtDT, shortName } from '../../domain/format.js'
import { todoTag } from '../../domain/request.js'

const KIND_LABEL = { REVISED: '수정', CANCELLED: '취소' }

/**
 * 요청 목록 표 — **대시보드·요청목록·완료목록이 같은 컬럼을 쓴다.**
 *  법인 · 요청번호 · 요청제목 · 요청자 · 담당자 · 진행 현황 · [일정] [보기]
 *
 * **행 클릭으로는 상세가 열리지 않는다 — 오직 [보기] 버튼이다.**
 * 표의 모든 칸이 드래그 복사 대상이라(요청번호·제목을 복사해 쓰는 일이 잦다),
 * 행 클릭을 살려 두면 "선택 중인가" 를 매번 추측해야 했다. 여는 방법을 하나로 고정했다.
 *
 * `onSchedule` 이 있으면(대시보드·요청목록) 행마다 [일정] 버튼이 붙는다(`UR-260922-1`,
 * 사용자 결정 §8-9 — 목록에서 바로 등록 가능해야 한다). 완료 건에는 안 보인다 — 더 잡을 일정이 없다.
 */
export default function RequestTable({ rows, onOpen, onSchedule, doneCol = false }) {
  if (!rows.length) return <EmptyState>표시할 요청이 없습니다.</EmptyState>
  return <div className="tbl-scroll"><table className="req-tbl">
    <thead><tr>
      <th style={{ minWidth: 104 }}>법인</th>
      <th style={{ minWidth: 132 }}>요청번호</th>
      <th style={{ minWidth: 240 }}>요청제목</th>
      <th style={{ minWidth: 88 }}>요청자</th>
      <th style={{ minWidth: 132 }}>담당자</th>
      <th style={{ minWidth: 196 }}>진행 현황</th>
      {doneCol && <th style={{ minWidth: 132 }} title="이 보드가 완료를 처음 관측한 시각">완료일 <span className="th-note">관측</span></th>}
      <th style={{ width: onSchedule ? 128 : 62 }}><span className="sr">상세</span></th>
    </tr></thead>
    <tbody>{rows.map(r => {
      const tag = todoTag(r)
      const sched = r.schedule
      return (
        <tr key={r.reqNo} className={(r.late ? 'delayed ' : '') + (r.fresh ? 'fresh ' : '') + (r.inTodo ? '' : 'closed')}>
          <td className="c-corp sel">{r.reqCompNm || r.dept}</td>
          <td><span className="rn mono sel">{r.reqNo}</span></td>
          <td><div className="req-cell">
            <span className="rt sel">{r.title}</span>
            <span className="rsub">{r.reqType}
              {r.fresh && <b className="tag-fresh" title={r.firstSeen ? `${fmtDT(r.firstSeen)} 유입` : '최근 유입'}>신규</b>}
              {/* `갱신` 은 **내가 본 뒤에 다시 공유됐다**는 뜻이다 — 열어 보면 꺼진다(note_read).
                  한 번도 안 본 노트는 갱신이 아니라 그냥 `노트` 다. */}
              {r.hasNote && (r.noteUpdated
                ? <b className="tag-note upd" title="내가 본 뒤에 분석 노트가 다시 공유됐습니다 — 내용이 바뀌었을 수 있습니다">노트 갱신</b>
                : <b className="tag-note" title="SME 분석 노트가 작성돼 있습니다">노트</b>)}
              {/* 내가 볼 수 있는 채널만 센다 — 리더 목록에서 SME↔SDE 대화가 세어지면
                  내용은 못 보면서 "무슨 말이 오갔다" 는 사실만 새어 나간다 */}
              {r.unreadComments > 0 &&
                <b className="tag-cmt" title="아직 안 본 코멘트가 있습니다">새 댓글 {r.unreadComments}</b>}
              {/* 스케줄 변경(수정·취소) 이력 — 열지 않아도 알 수 있어야 한다(사용자 결정 §8-8) */}
              {sched?.changes > 0 &&
                <b className="tag-note upd" title={`최근 ${KIND_LABEL[sched.lastKind] || sched.lastKind}${sched.lastReason ? ` · "${sched.lastReason}"` : ''}`}>
                  일정 {KIND_LABEL[sched.lastKind] || '변경'}됨</b>}
              {r.fileCount > 0 && <b className="tag-note" title={`SME 첨부 ${r.fileCount}건`}>첨부 {r.fileCount}</b>}
              {tag && <b className={'tag-closed ' + tag.k} title={tag.t}>{tag.l}</b>}</span>
          </div></td>
          <td className="c-req sel" title={r.requester || ''}>{r.requesterName || shortName(r.requester)}</td>
          <td><AssignCell r={r} /></td>
          <td><ProgressCell r={r} /></td>
          {doneCol && <td className="c-done mono num">{fmtDT(r.doneAt) || '-'}</td>}
          <td className="c-actions">
            {onSchedule && r.workStatus !== 'DONE' &&
              <button className="btn-view soft" onClick={() => onSchedule(r)}
                title={`${r.reqNo} 일정 등록·확인`}>
                {sched ? '일정' : r.workStatus === 'UNTRACKED' ? '확인 필요' : '일정 등록'}</button>}
            <button className="btn-view" onClick={() => onOpen(r.reqNo)}
              title={`${r.reqNo} 상세 보기`}>보기</button>
          </td>
        </tr>
      )
    })}</tbody>
  </table></div>
}
