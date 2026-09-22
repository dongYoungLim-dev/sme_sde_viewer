import Avatar from '../../ui/Avatar.jsx'

/** 담당자 칸 — 할당 단계에 따라 담당자 / 배정 대기 / SME 확인 대기. */
export default function AssignCell({ r }) {
  // 차수는 인력풀 배정에서 온다 — 그 건을 지금 들고 있는 SDE 의, **그 요청 법인 기준** 차수.
  // 배정이 없거나 담당 SDE 가 아직 가입하지 않았으면 서버가 null 을 주고, 배지는 그냥 생략된다.
  const tier = r.assigneeTier ? <b className="tier-badge" title="인력풀 담당 차수">{r.assigneeTier}차</b> : null

  if (r.assignStage === 'FINAL') return (
    <span className="person"><Avatar perId={r.assigneePerId || r.assigneeName} name={r.assigneeName} />
      <span className="pn">{r.assigneeName || '-'}</span>{tier}</span>
  )
  if (r.assignStage === 'LEADER') return (
    <span className="person"><Avatar perId={r.leaderPerId} name={r.leaderName} />
      <span className="pn" style={{ color: 'var(--st-test)', fontWeight: 700 }}>SDE 배정 대기</span></span>
  )
  return <span className="person unassigned"><span className="ava">?</span>
    <span className="pn" style={{ color: 'var(--ink-3)' }}>SME 확인 대기</span></span>
}
