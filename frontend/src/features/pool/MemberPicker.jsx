import { useEffect, useMemo, useRef, useState } from 'react'
import Avatar from '../../ui/Avatar.jsx'
import Icon from '../../ui/Icon.jsx'

/**
 * 담당자 선택 **팝업** — 검색해서 고른다.
 *
 * ⚠️ 예전에는 칸마다 드롭다운(`Select`)이었다. 팀원이 늘고 표가 길어지면서 목록이 화면 밖으로 나가
 * **스크롤해야 보이는 사람이 생겼다**(사용자 2026-09-10). 드롭다운은 여는 칸에 붙어 있어야 해서
 * 그 문제를 구조적으로 못 벗어난다 — 화면 한가운데 뜨는 팝업이라야 목록 전체가 늘 보인다.
 *
 * 검색은 **이름 · ITSM 계정 · 팀** 을 함께 본다. 이름만 보면 동명이인을 가릴 수 없다.
 */
export default function MemberPicker({ title, members, current, exclude, allowClear, onPick, onClear, onClose }) {
  const [q, setQ] = useState('')
  const [hi, setHi] = useState(0)
  const listRef = useRef(null)

  const rows = useMemo(() => {
    const key = q.trim().toLowerCase()
    return members
      .filter(m => !exclude?.has(m.userId))
      .filter(m => !key || [m.name, m.loginId, m.team].some(v => (v || '').toLowerCase().includes(key)))
  }, [members, exclude, q])

  // 검색을 고치면 강조가 목록 밖을 가리킬 수 있다 — 맨 위로 되돌린다
  useEffect(() => { setHi(0) }, [q])

  useEffect(() => {
    const onKey = (e) => {
      if (e.key === 'Escape') { e.preventDefault(); onClose(); return }
      if (e.key === 'ArrowDown') { e.preventDefault(); setHi(h => Math.min(h + 1, rows.length - 1)) }
      else if (e.key === 'ArrowUp') { e.preventDefault(); setHi(h => Math.max(h - 1, 0)) }
      else if (e.key === 'Enter' && rows[hi]) { e.preventDefault(); onPick(rows[hi].userId) }
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [rows, hi, onPick, onClose])

  // 키보드로 내려간 항목이 목록 밖에 있으면 따라 내려간다
  useEffect(() => {
    listRef.current?.querySelector('.mp-item.hi')?.scrollIntoView({ block: 'nearest' })
  }, [hi])

  return (
    <div className="modal-back" onMouseDown={(e) => { if (e.target === e.currentTarget) onClose() }}>
      <div className="modal mp" role="dialog" aria-label={title}>
        <div className="hd">
          <div className="hd-l"><h3>담당자 선택</h3><span className="cnt">{title}</span></div>
          <button type="button" className="modal-x" onClick={onClose} aria-label="닫기">×</button>
        </div>

        <div className="mp-search">
          <Icon name="search" />
          <input autoFocus value={q} onChange={(e) => setQ(e.target.value)}
            placeholder="이름 · ITSM 계정 · 팀으로 검색" aria-label="담당자 검색" />
          <span className="cnt">{rows.length}명</span>
        </div>

        <ul className="mp-list" ref={listRef}>
          {!rows.length && <li className="nh-empty">
            {members.length ? '검색 결과가 없습니다.' : '배정할 팀원이 없습니다 — SDE 가 먼저 가입해야 합니다.'}</li>}
          {rows.map((m, i) => (
            <li key={m.userId}>
              <button type="button"
                className={'mp-item' + (i === hi ? ' hi' : '') + (m.userId === current ? ' on' : '')}
                onMouseEnter={() => setHi(i)} onClick={() => onPick(m.userId)}>
                <Avatar perId={m.loginId} name={m.name} />
                <span className="mp-l">
                  <span className="mp-nm">{m.name}
                    {m.role === 'SDE_LEADER' && <span className="rank-badge">리더</span>}
                    {m.userId === current && <span className="rank-badge">현재</span>}</span>
                  {/* 지금 맡고 있는 자리 — 한 사람에게 몰리는 것을 고르기 전에 보라고 붙인다 */}
                  <span className="mp-sub">{m.loginId}{m.team ? ' · ' + m.team : ''}
                    {m.corps.length > 0 && <em className="m-cur"> {m.corps.join(' · ')}</em>}</span>
                </span>
                {!m.linked && <span className="qty">미연동</span>}
              </button>
            </li>
          ))}
        </ul>

        <div className="mp-foot">
          {allowClear && <button type="button" className="lnk danger" onClick={onClear}>이 칸 비우기</button>}
          <button type="button" className="lnk" onClick={onClose}>닫기</button>
        </div>
      </div>
    </div>
  )
}
