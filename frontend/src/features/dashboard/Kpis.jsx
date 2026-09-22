/**
 * 역할별 현황 숫자 — **세로 컴팩트 목록**(2026-09-10, `UR-260910-4`).
 *
 * 예전에는 아이콘 칩이 붙은 큰 카드 5칸이 가로로 늘어섰다. 진행 현황·최근 변화와 **한 줄에 세우려면**
 * 왼쪽 열이 그 높이를 감당하지 못한다. 그래서 `라벨 — 숫자` 한 줄씩으로 접었다.
 *
 * ⚠️ **설명줄은 지운 게 아니라 툴팁(`title`)으로 내려갔다.** '진행중'이 어느 상태를 세는지 같은 것은
 * 화면에서 사라지면 다시 물어보게 되는 종류의 정보다.
 *
 * ⚠️ 2026-09-22(`UR-260922-1`) — ITSM 11단계가 4단계(대기/작업중/작업완료/추적불가)로 바뀌면서
 * `신규`(예전 `dist.NEW`, ITSM 상태 기준)가 없어졌다. 가장 가까운 개념인 **`대기`(`k.waiting`,
 * 스케줄 미등록)로 바꿔치기했다** — 서버가 필드로 직접 주므로 `dist` 를 거칠 필요도 없어졌다.
 *
 * SDE 는 자기에게 할당된 것만 보므로 '배정 대기' 같은 관리 지표를 넣지 않는다.
 */
export default function Kpis({ roleKey, stats }) {
  if (!stats) return <div className="kpis" />
  const k = stats.kpis
  const rows = (CARDS[roleKey] || CARDS.sme)(k)

  return <div className="kpis">{rows.map(c => (
    <div className={'kpi' + (c.warn ? ' bad' : '')} key={c.l} title={c.s}>
      <span className="k-l">{c.l}</span><span className="k-v num">{c.v}</span>
    </div>
  ))}</div>
}

const DONE_HINT = '누적 — 진행 현황 표에는 들어가지 않습니다'
const WAITING_HINT = '스케줄이 아직 등록되지 않음'

const CARDS = {
  sde: k => [
    { l: '대기', v: k.waiting, s: WAITING_HINT },
    { l: '내 작업', v: k.open, s: '진행 중인 내 건 (완료 · 할 일 종료 제외)' },
    { l: '작업중', v: k.inProgress, s: '스케줄이 등록된 건' },
    { l: '추적불가', v: k.untracked, s: '스케줄 없이 ITSM 목록에서 사라짐 — 확인 필요', warn: k.untracked > 0 },
    { l: '지연', v: k.late, s: k.late ? '마감일이 지났습니다' : '마감일이 지난 건 없음', warn: k.late > 0 },
    { l: '마감 임박', v: k.soon, s: '2일 이내' },
    { l: '완료', v: k.done, s: DONE_HINT }
  ],
  lead: k => [
    { l: '대기', v: k.waiting, s: WAITING_HINT },
    { l: '작업중', v: k.inProgress, s: '스케줄이 등록된 건' },
    { l: '추적불가', v: k.untracked, s: '스케줄 없이 ITSM 목록에서 사라짐 — 확인 필요', warn: k.untracked > 0 },
    { l: 'SDE 배정 대기', v: k.leaderPending, s: '공정할당요청 · 담당 미확인', warn: k.leaderPending > 0 },
    { l: '지연', v: k.late, s: k.late ? '조치 필요' : '마감일이 지난 건 없음', warn: k.late > 0 },
    { l: '완료', v: k.done, s: DONE_HINT }
  ],
  sme: k => [
    { l: '대기', v: k.waiting, s: WAITING_HINT },
    { l: '작업중', v: k.inProgress, s: '내가 전달한 건 — 스케줄이 등록된 건' },
    { l: '추적불가', v: k.untracked, s: '스케줄 없이 ITSM 목록에서 사라짐 — 확인 필요', warn: k.untracked > 0 },
    { l: '미할당', v: k.intake, s: '변경접수 · 담당 배정 전', warn: k.intake > 0 },
    { l: 'SDE 배정 대기', v: k.leaderPending, s: '공정할당요청 · 담당 미확인' },
    { l: '지연', v: k.late, s: k.late ? '확인 요망' : '마감일이 지난 건 없음', warn: k.late > 0 },
    { l: '완료', v: k.done, s: DONE_HINT }
  ]
}
