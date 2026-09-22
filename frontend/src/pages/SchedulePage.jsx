import { useMemo, useState } from 'react'
import Icon from '../ui/Icon.jsx'
import EmptyState from '../ui/EmptyState.jsx'
import ScheduleModal from '../features/schedule/ScheduleModal.jsx'
import RequestPicker from '../features/schedule/RequestPicker.jsx'
import { api } from '../api/endpoints.js'
import { useAsyncData } from '../hooks/useAsyncData.js'
import { useSession } from '../session/SessionContext.jsx'
import { STATUS } from '../domain/status.js'

const WEEKDAY = ['일', '월', '화', '수', '목', '금', '토']
const VIEWS = [['month', '월'], ['week', '주'], ['day', '일']]

const pad = (n) => String(n).padStart(2, '0')
/** 이 앱은 날짜를 시간대 없이 다룬다(`AppTime`, 서울 고정) — 브라우저 Date 의 로컬 값을 그대로 문자열로 만든다. */
const toApiDT = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
const startOfDay = (d) => { const x = new Date(d); x.setHours(0, 0, 0, 0); return x }
const addDays = (d, n) => { const x = new Date(d); x.setDate(x.getDate() + n); return x }
const startOfWeek = (d) => addDays(startOfDay(d), -startOfDay(d).getDay())
const startOfMonth = (d) => new Date(d.getFullYear(), d.getMonth(), 1)
const sameDay = (a, b) => a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate()
const isToday = (d) => sameDay(d, new Date())

/** 조회 구간 [from, to) — month 는 앞뒤 빈 칸을 채운 6주 그리드까지 포함한다. */
function rangeOf(mode, anchor) {
  if (mode === 'day') return { from: startOfDay(anchor), to: addDays(startOfDay(anchor), 1) }
  if (mode === 'week') { const from = startOfWeek(anchor); return { from, to: addDays(from, 7) } }
  const from = startOfWeek(startOfMonth(anchor))
  return { from, to: addDays(from, 42) }
}

function labelOf(mode, anchor) {
  if (mode === 'day') return `${anchor.getFullYear()}.${pad(anchor.getMonth() + 1)}.${pad(anchor.getDate())} (${WEEKDAY[anchor.getDay()]})`
  if (mode === 'week') { const s = startOfWeek(anchor), e = addDays(s, 6)
    return `${s.getFullYear()}.${pad(s.getMonth() + 1)}.${pad(s.getDate())} ~ ${pad(e.getMonth() + 1)}.${pad(e.getDate())}` }
  return `${anchor.getFullYear()}년 ${anchor.getMonth() + 1}월`
}

function stepAnchor(mode, anchor, dir) {
  if (mode === 'day') return addDays(anchor, dir)
  if (mode === 'week') return addDays(anchor, dir * 7)
  return new Date(anchor.getFullYear(), anchor.getMonth() + dir, 1)
}

function fmtHM(iso) {
  if (!iso) return ''
  return iso.slice(11, 16)
}

/**
 * 스케줄 캘린더 — 신규 화면(`UR-260922-1`, 사용자 결정 §8-9). 일/주/월 세 뷰.
 *
 * 조회 범위는 서버가 정한다(`ScheduleService.calendar` — 내 조회 범위의 요청 + 내가 작업자·등록자인 것) —
 * 여기서 따로 거르지 않는다. 기존 일정을 열고 수정·취소하는 것은 목록·상세와 **같은 `ScheduleModal`**
 * 을 그대로 쓴다(칸을 클릭하면 그 일정이 열린다).
 *
 * **여기서 바로 새 일정을 등록**할 수도 있다(2026-09-22 추가) — 일정은 항상 특정 요청에 딸린 데이터라
 * 빈 칸을 바로 일정으로 바꿀 수는 없다. 그래서 날짜의 `+` 를 누르면 먼저 {@link RequestPicker} 로
 * 대기(WAITING) 상태 요청을 고르게 하고, 고른 다음에야 `ScheduleModal` 이 그 날 09~18시를 채운 채 열린다.
 */
export default function SchedulePage() {
  const { refreshData } = useSession()
  const [mode, setMode] = useState('month')
  const [anchor, setAnchor] = useState(() => startOfDay(new Date()))
  const [picked, setPicked] = useState(null)
  const [pickingFor, setPickingFor] = useState(null)   // 새 일정을 등록할 날짜(Date) — 요청 선택 팝업이 열려 있는 동안
  const [creating, setCreating] = useState(null)        // 요청을 고른 뒤 — { reqNo, title, workStatus, date }

  const { from, to } = useMemo(() => rangeOf(mode, anchor), [mode, anchor])
  const fromApi = useMemo(() => toApiDT(from), [from])
  const toApi = useMemo(() => toApiDT(to), [to])
  const { data, loading } = useAsyncData(sig => api.calendar(fromApi, toApi, sig), [fromApi, toApi])
  const items = data?.items || []

  const days = useMemo(() => {
    const n = mode === 'day' ? 1 : mode === 'week' ? 7 : 42
    return Array.from({ length: n }, (_, i) => addDays(from, i))
  }, [from, mode])

  /** month/week 뿐 — 7칸씩 묶어 주 단위 행으로. */
  const weeks = useMemo(() => {
    const out = []
    for (let i = 0; i < days.length; i += 7) out.push(days.slice(i, i + 7))
    return out
  }, [days])

  const itemsOn = (day) => {
    const dayStart = day, dayEnd = addDays(day, 1)
    return items
      .filter(it => new Date(it.start) < dayEnd && new Date(it.end) > dayStart)
      .sort((a, b) => a.start.localeCompare(b.start))
  }

  return (
    <div className="card pop">
      <div className="hd">
        <div className="hd-l">
          <h3>스케줄</h3>
          <span className="cnt">{loading ? '불러오는 중…' : `${items.length}건`}</span>
        </div>
        <div className="hd-tools">
          <button type="button" className="btn-xlsx" onClick={() => setPickingFor(anchor)}>
            <Icon name="calendar" /> 새 일정</button>
          <button type="button" className="btn-view soft" onClick={() => setAnchor(startOfDay(new Date()))}>오늘</button>
          <button type="button" className="cal-nav" onClick={() => setAnchor(a => stepAnchor(mode, a, -1))} aria-label="이전"><Icon name="chev" /></button>
          <span className="cal-label">{labelOf(mode, anchor)}</span>
          <button type="button" className="cal-nav next" onClick={() => setAnchor(a => stepAnchor(mode, a, 1))} aria-label="다음"><Icon name="chev" /></button>
          <span className="cal-views">
            {VIEWS.map(([k, l]) => (
              <button type="button" key={k} className={'cal-view' + (mode === k ? ' on' : '')} onClick={() => setMode(k)}>{l}</button>
            ))}
          </span>
        </div>
      </div>

      {mode === 'day' ? (
        <div className="cal-day">
          <button type="button" className="btn-view soft" style={{ marginBottom: 10 }}
            onClick={() => setPickingFor(anchor)}>+ 이 날짜에 일정 등록</button>
          {(() => {
            const rows = itemsOn(anchor)
            return rows.length
              ? rows.map(it => <ScheduleChip key={it.id} item={it} onClick={() => setPicked(it)} />)
              : <EmptyState>이 날짜에 등록된 일정이 없습니다.</EmptyState>
          })()}
        </div>
      ) : (
        <div className={'cal-weeks ' + mode}>
          {mode === 'month' && (
            <div className="cal-wdrow">
              {WEEKDAY.map((w, i) => <div className={'cal-wd' + weekendCls(i)} key={w}>{w}</div>)}
            </div>
          )}
          {weeks.map((week, wi) => (
            <div className="cal-week" key={wi}>
              <div className="cal-week-bg">
                {week.map(day => <div key={day.toISOString()} className={'cal-col-bg' + dayCls(day, anchor, mode)} />)}
              </div>
              <div className="cal-week-hd">
                {week.map(day => (
                  <div key={day.toISOString()} className={'cal-daynum-cell' + dayCls(day, anchor, mode)}>
                    <span className="cal-daynum">{mode === 'week' ? `${WEEKDAY[day.getDay()]} ${day.getDate()}` : day.getDate()}</span>
                    <button type="button" className="cal-add" onClick={() => setPickingFor(day)}
                      aria-label={`${day.getMonth() + 1}월 ${day.getDate()}일 일정 등록`} title="일정 등록">+</button>
                  </div>
                ))}
              </div>
              <div className="cal-week-bars">
                {layoutWeek(week, items).map(b => (
                  <button type="button" key={b.item.id}
                    className={'cal-bar' + (b.item.reqStatus === 'DONE' ? ' done' : '')
                      + (b.contStart ? ' cont-l' : '') + (b.contEnd ? ' cont-r' : '')}
                    style={{ gridColumn: `${b.col0 + 1} / ${b.col1 + 1}`, gridRow: b.lane + 1 }}
                    onClick={() => setPicked(b.item)}
                    title={`${b.item.reqNo} · ${b.item.title || ''} · ${b.item.assigneeName || ''}${b.item.reqStatus === 'DONE' ? ` · ${STATUS.DONE.l}` : ''}`}>
                    {b.contStart && <span className="cal-bar-cont">◂</span>}
                    {b.col1 - b.col0 === 1 && !b.contStart && !b.contEnd &&
                      <span className="cal-bar-t">{fmtHM(b.item.start)}~{fmtHM(b.item.end)}</span>}
                    <span className="cal-bar-n">{b.item.title || b.item.reqNo}</span>
                    {b.contEnd && <span className="cal-bar-cont">▸</span>}
                  </button>
                ))}
              </div>
            </div>
          ))}
        </div>
      )}

      {picked && <ScheduleModal reqNo={picked.reqNo} title={picked.title} workStatus={picked.reqStatus}
        schedule={picked} onClose={() => setPicked(null)} onSaved={refreshData} />}

      {pickingFor && <RequestPicker onClose={() => setPickingFor(null)}
        onPick={(r) => { setCreating({ reqNo: r.reqNo, title: r.title, workStatus: r.workStatus, date: pickingFor }); setPickingFor(null) }} />}

      {creating && <ScheduleModal reqNo={creating.reqNo} title={creating.title} workStatus={creating.workStatus}
        schedule={null} initialDate={creating.date} onClose={() => setCreating(null)} onSaved={refreshData} />}
    </div>
  )
}

/** 일(0)·토(6) 만 다른 색으로 — 나머지 요일은 그대로. */
function weekendCls(dow) {
  return dow === 0 ? ' sun' : dow === 6 ? ' sat' : ''
}

/** 주말·오늘·(월 뷰 한정)이달 밖 — 배경 레이어·날짜 헤더 레이어가 같은 판정을 공유한다. */
function dayCls(day, anchor, mode) {
  return weekendCls(day.getDay()) + (isToday(day) ? ' today' : '')
    + (mode === 'month' && day.getMonth() !== anchor.getMonth() ? ' out' : '')
}

/** 자정이면 그 날은 포함하지 않는다(09~18시 같은 하루짜리 일정이 다음 날 칸으로 새지 않게). */
function endDayExclusive(iso) {
  const e = new Date(iso)
  return (e.getHours() === 0 && e.getMinutes() === 0) ? startOfDay(e) : addDays(startOfDay(e), 1)
}
const daysBetween = (a, b) => Math.round((b - a) / 86400000)

/**
 * 한 주(7칸) 안에서 겹치는 일정을 칸 범위(0~7, 끝은 배타적)로 자르고 **레인**을 배정한다 —
 * 여러 날에 걸친 일정이 칸 경계에서 끊기지 않고 한 막대로 이어지게 한다(사용자 요청, `UR-260922-3`).
 * 주가 바뀌면(막대가 다음 행으로 넘어가면) 막대 자체를 이을 수는 없어 `contStart`/`contEnd`
 * 화살표로만 "이어짐"을 표시한다. 겹치는 막대는 레인을 나눠 쌓는다(그리디 — 비어 있는 첫 레인에).
 */
function layoutWeek(weekDays, allItems) {
  const weekStart = weekDays[0]
  const weekEnd = addDays(weekStart, 7)
  const bars = allItems
    .filter(it => new Date(it.start) < weekEnd && new Date(it.end) > weekStart)
    .map(it => {
      const s = new Date(it.start)
      const col0 = Math.max(0, daysBetween(weekStart, startOfDay(s)))
      const col1 = Math.min(7, daysBetween(weekStart, endDayExclusive(it.end)))
      return { item: it, col0, col1: Math.max(col1, col0 + 1), contStart: s < weekStart, contEnd: endDayExclusive(it.end) > weekEnd }
    })
    .sort((a, b) => a.col0 - b.col0 || (b.col1 - b.col0) - (a.col1 - a.col0))
  const laneEnds = []
  for (const b of bars) {
    let lane = laneEnds.findIndex(end => end <= b.col0)
    if (lane === -1) { lane = laneEnds.length; laneEnds.push(0) }
    laneEnds[lane] = b.col1
    b.lane = lane
  }
  return bars
}

function ScheduleChip({ item, onClick }) {
  const done = item.reqStatus === 'DONE'
  return (
    <button type="button" className={'cal-chip' + (done ? ' done' : '')} onClick={onClick}
      title={`${item.reqNo} · ${item.title || ''} · ${item.assigneeName || ''}${done ? ` · ${STATUS.DONE.l}` : ''}`}>
      <span className="cal-chip-t">{fmtHM(item.start)}~{fmtHM(item.end)}</span>
      <span className="cal-chip-n">{done && <span className="cal-chip-check">✓</span>}{item.title || item.reqNo}</span>
      <span className="cal-chip-a">{item.assigneeName}{done && ` · ${STATUS.DONE.l}`}</span>
    </button>
  )
}
