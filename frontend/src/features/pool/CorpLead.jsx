import { useState } from 'react'
import Avatar from '../../ui/Avatar.jsx'
import Icon from '../../ui/Icon.jsx'
import MemberPicker from './MemberPicker.jsx'
import { fmtDT } from '../../domain/format.js'

/**
 * **법인담당SDE** — 이 법인을 맡는 SDE **명단**.
 *
 * ⚠️ **차수가 없다**(사용자 2026-09-10). 순번이 아니라 명단이라 **인원 제한도 없다** —
 * "5명만 등록된다는 보장도 없다". 그래서 차수 격자가 아니라 사람 칩을 늘어놓는다.
 *
 * 시스템별 차수 표와 **일부러 다르게 생겼다.** 묻는 것이 다르기 때문이다.
 * - 여기(칩) = *"이 법인은 누가 맡나"*
 * - 아래(표) = *"이 시스템의 n차는 누구인가"*
 */
export default function CorpLead({ corpNm, leads, members, editable, onAdd, onRemove, meUserId }) {
  const [open, setOpen] = useState(false)
  const [drop, setDrop] = useState(null)          // 빼기 확인 중인 userId
  // 이미 명단에 있는 사람은 고를 수 없다 — 중복은 서버도 막지만, 보이면 누르게 된다
  const taken = new Set(leads.map(l => l.userId))

  return (
    <div className="corp-lead">
      <div className="corp-lead-hd">
        <span className="corp-lead-lb">법인담당SDE</span>
        <span className="corp-lead-cnt">{leads.length}명</span>
        <span className="corp-lead-note"><Icon name="info" />
          이 법인을 맡는 담당자 명단입니다 — 차수는 아래 시스템별로 정합니다.</span>
      </div>

      <div className="lead-cells">
        {!leads.length && <span className="lead-empty">아직 등록된 담당자가 없습니다.</span>}
        {leads.map(l => (
          <span key={l.userId} className={'lead-chip' + (l.userId === meUserId ? ' mine' : '')}
            title={l.updatedByName ? `${l.updatedByName} · ${fmtDT(l.updatedAt)}` : undefined}>
            <Avatar perId={l.loginId} name={l.name} />
            <span className="pn">{l.name}</span>
            {l.userId === meUserId && <span className="rank-badge">나</span>}
            {editable && (drop === l.userId
              // 확인은 브라우저 confirm 이 아니라 그 자리에서 받는다
              ? <span className="corp-drop">
                  <button type="button" className="lnk danger"
                    onClick={() => { onRemove(corpNm, l.userId); setDrop(null) }}>뺀다</button>
                  <button type="button" className="lnk" onClick={() => setDrop(null)}>취소</button>
                </span>
              : <button type="button" className="corp-x" title="이 담당자를 명단에서 뺀다"
                  onClick={() => setDrop(l.userId)}>×</button>)}
          </span>
        ))}
        {editable && (
          <button type="button" className="lead-add" onClick={() => setOpen(true)}>+ 담당자 추가</button>
        )}
      </div>

      {open && (
        <MemberPicker
          title={`${corpNm} · 법인담당SDE`}
          members={members} current={null}
          exclude={taken}
          onPick={(userId) => { setOpen(false); onAdd(corpNm, userId) }}
          onClose={() => setOpen(false)} />
      )}
    </div>
  )
}
