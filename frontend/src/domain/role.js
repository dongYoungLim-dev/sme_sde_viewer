// 역할과 정/부. 화면의 권한 판정은 **오직 role** 로 한다.

export const ROLES = {
  SME:        { k: 'sme',  l: 'SME',      d: '법인 선택' },
  SDE_LEADER: { k: 'lead', l: 'SDE 리더', d: '팀 선택' },
  SDE:        { k: 'sde',  l: 'SDE',      d: '팀 선택' }
}

export function roleKeyOf(role) { return ROLES[role]?.k || 'sme' }

export function isLeader(role) { return role === 'SDE_LEADER' }

/**
 * SDE 리더의 **정/부**. 서버는 `MAIN`/`SUB` 로만 주고, 리더가 아니면 null 이다.
 * ⚠️ 권한 차이가 없는 **표시용 구분**이라 `role` 과 섞지 않는다.
 */
export const LEADER_RANK = {
  MAIN: { l: '정', d: '정 리더' },
  SUB:  { l: '부', d: '부 리더' }
}
