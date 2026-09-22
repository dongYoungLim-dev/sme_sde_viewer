import { useEffect, useState } from 'react'
import DatePicker, { registerLocale } from 'react-datepicker'
import { ko } from 'date-fns/locale'
import Icon from '../../ui/Icon.jsx'
import Select from '../../ui/Select.jsx'
import { api } from '../../api/endpoints.js'
import { useSession } from '../../session/SessionContext.jsx'
import { fmtDT, toApiDT, toInputDT } from '../../domain/format.js'
import { ROLES } from '../../domain/role.js'

registerLocale('ko', ko)

const KIND_LABEL = { REVISED: '수정', CANCELLED: '취소' }

/** 새 일정의 기본 시간대 — 그날 09:00~18:00(통상 근무 시간). 사용자가 그대로 바꿀 수 있다. */
function defaultRangeOf(date) {
  if (!date) return { start: '', end: '' }
  const y = date.getFullYear(), mo = String(date.getMonth() + 1).padStart(2, '0'), d = String(date.getDate()).padStart(2, '0')
  return { start: `${y}-${mo}-${d}T09:00`, end: `${y}-${mo}-${d}T18:00` }
}

function todayStr() {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

/**
 * "YYYY-MM-DDTHH:mm" → `Date`, 그 반대.
 *
 * ⚠️ `react-datepicker` 는 `selected` 에 `Date` 객체를 요구한다. 이 앱은 서버 시각을 문자열째
 * 다루고 브라우저 타임존 변환을 절대 거치지 않는 원칙이다(`domain/format.js` 상단 주석 참고) —
 * 여기서도 `new Date(iso)`(UTC 해석 위험)나 `toISOString()`(타임존 이동) 대신, 연/월/일/시/분을
 * **로컬 컴포넌트로만** 넣고 빼서 값이 화면에 떠 있는 동안 절대 안 흔들리게 한다.
 */
function strToDate(value) {
  if (!value) return null
  const [datePart, timePart] = value.split('T')
  const [y, mo, d] = (datePart || '').split('-').map(Number)
  if (!y || !mo || !d) return null
  const [h, mi] = (timePart || '09:00').split(':').map(Number)
  return new Date(y, mo - 1, d, h || 0, mi || 0)
}
function dateParts(d) {
  const p = (n) => String(n).padStart(2, '0')
  return { date: `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`, time: `${p(d.getHours())}:${p(d.getMinutes())}` }
}

/**
 * 시작·종료 일시 입력 — **날짜 칸 + 시간 칸을 나눈다.** 값은 그대로 "YYYY-MM-DDTHH:mm" 한 문자열이라
 * (`toApiDT`/`toInputDT` 와 그대로 호환) 각 칸은 `strToDate`/`dateParts` 로만 변환해 들어오고 나간다.
 *
 * ⚠️ 브라우저 기본 `<input type="datetime-local">` 하나로 두면 연/월/일/시/분이 한 줄에 붙어 있는
 * 조작 단자가 되어 복잡해 보인다는 지적(사용자, 2026-09-22)이 있어 애초에 두 칸으로 나눴다.
 * 이번엔 그 각 칸을 네이티브 피커(OS·브라우저마다 모양이 다르고 시:분은 스크롤이 번거로움) 대신
 * `react-datepicker` 로 바꿔 달력 그리드·15분 간격 시간 목록을 직접 클릭해 고르게 한다
 * (`UR-260922-2`). 팝업은 `.modal{overflow:hidden}` 에 잘리지 않게 `portalId` 로 body 에 띄운다.
 */
function DateTimeField({ label, value, onChange }) {
  const selected = strToDate(value)
  const setDate = (d) => {
    if (!d) return
    const time = value.slice(11, 16) || '09:00'
    onChange(`${dateParts(d).date}T${time}`)
  }
  const setTime = (d) => {
    if (!d) return
    const date = value.slice(0, 10) || todayStr()
    onChange(`${date}T${dateParts(d).time}`)
  }
  const id = label.replace(/\s+/g, '-')
  return (
    <div className="field">
      <label>{label}</label>
      <div className="dt-row">
        <div className="dt-col">
          <label className="dt-sublabel" htmlFor={`${id}-date`}>날짜</label>
          <DatePicker id={`${id}-date`} selected={selected} onChange={setDate} locale="ko" dateFormat="yyyy-MM-dd (EEE)"
            placeholderText="예: 2026-09-22"
            className="dt-date" portalId="dt-portal" popperPlacement="bottom-start" autoComplete="off" />
        </div>
        <div className="dt-col">
          <label className="dt-sublabel" htmlFor={`${id}-time`}>시간</label>
          <DatePicker id={`${id}-time`} selected={selected} onChange={setTime} locale="ko"
            showTimeSelect showTimeSelectOnly timeIntervals={5} timeCaption="시간" dateFormat="HH:mm"
            placeholderText="예: 09:00"
            className="dt-time" portalId="dt-portal" popperPlacement="bottom-start" autoComplete="off" />
        </div>
      </div>
    </div>
  )
}

/**
 * 처리 일정 등록·수정·취소 + 추적불가 확정 팝업 (`UR-260922-1`).
 *
 * ⚠️ **담당자 표시(ITSM 값)는 여기서 바꾸지 않는다**(사용자 결정 §8-5). 이 팝업이 바꾸는 건
 * 요청의 4단계 상태(대기↔작업중)와, 캘린더·이 팝업 안에서만 쓰는 "작업자" 값뿐이다.
 *
 * `schedule` 은 두 가지 모양이 올 수 있다 — 목록/상세의 `ScheduleInfo`(가벼움) 또는
 * 캘린더의 `ScheduleView`(`createdByName` 등 더 있음). 있는 필드만 보여준다.
 *
 * `workStatus` 에 따라 화면이 통째로 갈린다:
 *  - `UNTRACKED` — 스케줄 없이 ITSM 목록에서 사라짐. 등록이 아니라 **작업완료 확정**만 가능.
 *  - 일정 없음(`WAITING`) — 등록 폼. `initialDate` 가 있으면(캘린더에서 날짜를 골라 등록할 때)
 *    그 날 09~18시로 미리 채운다.
 *  - 일정 있음(`IN_PROGRESS`) — 현재 일정 표시 + 수정/취소(둘 다 **사유 필수**).
 */
export default function ScheduleModal({ reqNo, title, workStatus, schedule, initialDate, onClose, onSaved }) {
  const { run } = useSession()
  const [assignees, setAssignees] = useState(null)
  const [mode, setMode] = useState(schedule?.id ? 'view' : 'create')   // create|view|edit|cancel
  const initRange = defaultRangeOf(initialDate)
  const [start, setStart] = useState(schedule?.start ? toInputDT(schedule.start) : initRange.start)
  const [end, setEnd] = useState(schedule?.end ? toInputDT(schedule.end) : initRange.end)
  const [assigneeId, setAssigneeId] = useState(schedule?.assigneeId ? String(schedule.assigneeId) : '')
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState('')

  useEffect(() => {
    let alive = true
    run(() => api.scheduleAssignees(), { silent: true })
      .then(list => {
        if (!alive) return
        setAssignees(list)
        setAssigneeId(cur => cur || String(list.find(a => a.me)?.id || list[0]?.id || ''))
      })
      .catch(() => { if (alive) setAssignees([]) })
    return () => { alive = false }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    const onKey = (e) => { if (e.key === 'Escape' && !busy) onClose() }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [busy, onClose])

  const options = (assignees || []).map(a =>
    ({ key: String(a.id), label: `${a.name}${a.me ? ' (나)' : ''} · ${ROLES[a.role]?.l || a.role}` }))

  const close = () => { if (!busy) onClose() }

  async function submit(fn, okMessage) {
    setErr('')
    setBusy(true)
    try {
      await run(fn, { silent: true })
      onSaved()
      onClose()
    } catch (e) {
      setErr(e?.body?.message || e?.message || okMessage)
    } finally {
      setBusy(false)
    }
  }

  const doCreate = () => {
    if (!start || !end) { setErr('시작·종료 일시를 모두 입력하세요.'); return }
    submit(() => api.createSchedule(reqNo, toApiDT(start), toApiDT(end), assigneeId ? Number(assigneeId) : null),
      '등록에 실패했습니다.')
  }
  const doEdit = () => {
    if (!start || !end) { setErr('시작·종료 일시를 모두 입력하세요.'); return }
    if (!reason.trim()) { setErr('수정 사유를 입력하세요.'); return }
    submit(() => api.updateSchedule(schedule.id, toApiDT(start), toApiDT(end),
      assigneeId ? Number(assigneeId) : null, reason.trim()), '수정에 실패했습니다.')
  }
  const doCancel = () => {
    if (!reason.trim()) { setErr('취소 사유를 입력하세요.'); return }
    submit(() => api.cancelSchedule(schedule.id, reason.trim()), '취소에 실패했습니다.')
  }
  const doComplete = () => submit(() => api.completeUntracked(reqNo), '확정에 실패했습니다.')

  const startEdit = () => {
    setStart(toInputDT(schedule.start)); setEnd(toInputDT(schedule.end))
    setAssigneeId(String(schedule.assigneeId)); setReason(''); setMode('edit')
  }

  return (
    <div className="modal-back" onMouseDown={(e) => { if (e.target === e.currentTarget) close() }}>
      <div className="modal sched" role="dialog" aria-label="처리 일정">
        <div className="hd">
          <div className="hd-l"><h3>처리 일정</h3><span className="cnt mono">{reqNo}</span></div>
          <button type="button" className="modal-x" onClick={close} aria-label="닫기">×</button>
        </div>
        {title && <p className="sched-title">{title}</p>}
        {err && <div className="dw-note done-note"><Icon name="warn" /><span>{err}</span></div>}

        {workStatus === 'UNTRACKED' ? (
          <div className="sched-body">
            <div className="dw-note"><Icon name="warn" /><span>
              스케줄 없이 ITSM 목록에서 사라졌습니다 — 이 보드를 쓰지 않고 처리됐을 수 있습니다.
              처리가 끝난 건이면 작업완료로 확정하세요.</span></div>
            <div className="sched-foot">
              <button type="button" className="btn-view" onClick={close}>닫기</button>
              <button type="button" className="btn-xlsx" disabled={busy} onClick={doComplete}>작업완료로 확정</button>
            </div>
          </div>
        ) : mode === 'view' && schedule ? (
          <div className="sched-body">
            {workStatus === 'DONE' && <div className="dw-note done-note"><Icon name="done" /><span>작업완료된 요청입니다 — 이 일정은 처리 기록으로만 남습니다.</span></div>}
            <dl className="kv">
              <dt>일시</dt><dd>{fmtDT(schedule.start)} ~ {fmtDT(schedule.end)}</dd>
              <dt>작업자</dt><dd>{schedule.assigneeName || '-'}</dd>
              {schedule.createdByName && <><dt>등록자</dt><dd>{schedule.createdByName}</dd></>}
              {schedule.changes > 0 &&
                <><dt>변경 이력</dt><dd>{schedule.changes}회 · 최근 {KIND_LABEL[schedule.lastKind] || schedule.lastKind}
                  {schedule.lastReason ? ` · "${schedule.lastReason}"` : ''} · {fmtDT(schedule.lastChangedAt)}</dd></>}
            </dl>
            <div className="sched-foot">
              <button type="button" className="btn-view" onClick={close}>닫기</button>
              {workStatus !== 'DONE' && <>
                <button type="button" className="btn-view" onClick={startEdit}>수정</button>
                <button type="button" className="btn-view danger" onClick={() => { setReason(''); setMode('cancel') }}>일정 취소</button>
              </>}
            </div>
          </div>
        ) : mode === 'cancel' ? (
          <div className="sched-body">
            <p className="sched-note">일정을 취소하면 이 요청은 다시 <b>대기</b> 상태로 돌아갑니다.</p>
            <div className="field"><label>취소 사유</label>
              <textarea value={reason} onChange={e => setReason(e.target.value)} rows={3}
                placeholder="예: 다른 담당자에게 넘김" /></div>
            <div className="sched-foot">
              <button type="button" className="btn-view" onClick={() => setMode('view')}>돌아가기</button>
              <button type="button" className="btn-xlsx danger" disabled={busy} onClick={doCancel}>취소 확정</button>
            </div>
          </div>
        ) : (
          <div className="sched-body">
            <DateTimeField label="시작 일시" value={start} onChange={setStart} />
            <DateTimeField label="종료 일시" value={end} onChange={setEnd} />
            <div className="field"><label>작업자</label>
              <Select options={options} value={assigneeId} onChange={setAssigneeId}
                ariaLabel="작업자 선택" emptyText="불러오는 중…" /></div>
            {mode === 'edit' && (
              <div className="field"><label>수정 사유</label>
                <textarea value={reason} onChange={e => setReason(e.target.value)} rows={3}
                  placeholder="예: 일정 연기" /></div>
            )}
            <div className="sched-foot">
              <button type="button" className="btn-view"
                onClick={() => mode === 'edit' ? setMode('view') : close()}>{mode === 'edit' ? '돌아가기' : '취소'}</button>
              <button type="button" className="btn-xlsx" disabled={busy}
                onClick={mode === 'edit' ? doEdit : doCreate}>{mode === 'edit' ? '저장' : '등록'}</button>
            </div>
          </div>
        )}
      </div>
    </div>
  )
}
