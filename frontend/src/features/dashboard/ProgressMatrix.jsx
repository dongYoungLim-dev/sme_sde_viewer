import Avatar from '../../ui/Avatar.jsx'
import EmptyState from '../../ui/EmptyState.jsx'
import { MATRIX_ORDER, STATUS, distMapOf } from '../../domain/status.js'

/**
 * 진행 현황 — **행=담당자 / 열=상태** 한 표.
 *
 * <p>예전에는 카드 두 장이었다. `상태 분포`(전체를 상태로 쪼갠 것)와 `담당자별 현황`(사람을 상태로 쪼갠 것)인데,
 * **둘이 이미 같은 축을 공유**하고 있었다. 그래서 맨 윗줄이 곧 예전 상태 분포이고 아래가 담당자별이다.
 *
 * <p>⚠️ **프로그래스 바를 지운 자리를 숫자가 대신한다.** 예전 막대는 색만 보여 줬고, 라벨도 숫자도 없어
 * *"색으로만 4가지 상태를 보여 주는데 명확하지 않다"* 는 질문을 받았다(`UR-260910-3`).
 * 게다가 막대에는 `NEW` 가 빠져 있어 **숫자 5인데 막대가 텅 빈 행**이 실제로 셋이나 있었다.
 * 열로 세우면 그 종류의 어긋남이 생길 자리가 없다.
 *
 * <p><b>`완료` 는 합계 바깥에 둔다</b>(2026-09-11). 합계에 섞으면 누적 완료가 많은 사람일수록 크게 보여
 * 이 표가 답해야 할 "지금 몇 건 들고 있나" 를 못 읽는다. ⚠️ 완료만 있는 사람도 행이 남는다 —
 * 그래야 전체 줄의 완료 수와 어긋나지 않는다.
 *
 * <p><b>전부 0인 열은 뺀다.</b> 리더 화면에서는 `분석중·테스트·보류` 가 항상 0이지만
 * SME 화면에서는 `분석중` 이 크게 찬다(서비스요청 유형). **역할마다 열이 다르므로** 고정 목록이 아니라
 * 지금 데이터에서 정한다. 서버는 전 버킷을 그대로 실어 보낸다 — 여기서 지우는 것은 화면뿐이다.
 */
export default function ProgressMatrix({ stats }) {
  if (!stats) return <div className="matrix-wrap" />

  const dist = distMapOf(stats.dist)
  const cols = MATRIX_ORDER.filter(s => dist[s] > 0)
  const total = cols.reduce((n, s) => n + dist[s], 0)
  // 완료는 **합계 바깥**의 별도 축이다(사용자 요청 2026-09-11). 합계에 섞으면
  // 누적 완료가 많은 사람일수록 크게 보여 "지금 몇 건 들고 있나" 를 못 읽는다.
  const doneAll = dist.DONE || 0

  // SDE 는 자기 것만 보므로 담당자 행이 자기 하나뿐이다 — 전체 줄과 같은 수가 두 번 나온다.
  const holders = stats.role === 'sde' ? [] : (stats.team || [])

  if (!total && !holders.length) return <EmptyState>진행 중인 요청이 없습니다.</EmptyState>

  return (
    <div className="matrix-wrap">
      <table className="matrix">
        <thead>
          <tr>
            <th scope="col" className="mx-who">담당</th>
            {cols.map(s => (
              <th scope="col" key={s} className="num">
                <span className="pd" style={{ background: `var(${STATUS[s].v})` }} />{STATUS[s].l}
              </th>
            ))}
            <th scope="col" className="num mx-sum">합계</th>
            {doneAll > 0 && <th scope="col" className="num mx-done">완료</th>}
          </tr>
        </thead>
        <tbody>
          {/* 이 줄이 예전 '상태 분포' 카드다 */}
          <tr className="mx-all">
            <th scope="row" className="mx-who">전체</th>
            {cols.map(s => <Cell key={s} n={dist[s]} />)}
            <td className="num mx-sum">{total}</td>
            {doneAll > 0 && <td className="num mx-done">{doneAll}</td>}
          </tr>
          {holders.map(t => (
            <tr key={t.name}>
              {/* 셀 자체를 flex 로 만들면 table-cell 이 아니게 돼 열 정렬·sticky 가 깨진다 — 안에 한 겹 둔다 */}
              <th scope="row" className="mx-who"><span className="mx-p">
                <Avatar perId={t.perId} name={t.name} />
                <span className="mx-nm">{t.name}</span>
                {t.late > 0 && <span className="mx-late" title="마감일이 지난 건">지연 {t.late}</span>}
              </span></th>
              {cols.map(s => <Cell key={s} n={t.byStatus[s] || 0} />)}
              <td className="num mx-sum">{t.active || '-'}</td>
              {doneAll > 0 && <td className={'num mx-done' + (t.done ? '' : ' mx-zero')}>{t.done || '-'}</td>}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

/** 0 은 `0` 이 아니라 `-` 로 둔다 — 0 이 줄지어 서면 눈이 숫자를 못 고른다. */
function Cell({ n }) {
  return <td className={'num' + (n ? '' : ' mx-zero')}>{n || '-'}</td>
}
