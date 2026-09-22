import StatusFilterSelect from '../dashboard/StatusFilterSelect.jsx'
import ExportButton from './ExportButton.jsx'
import NewCommentFilter from './NewCommentFilter.jsx'

/** 목록 카드 머리 — 제목·건수는 왼쪽, 상태 셀렉트와 내보내기는 우측 상단. */
export default function ListHead({ title, rows, q, stats, filter, setFilter, extra }) {
  return (
    <div className="hd">
      <div className="hd-l"><h3>{title}</h3>
        <span className="cnt">{rows.length}건{extra ? ` · ${extra}` : ''}{q ? ` · "${q}" 검색` : ''}</span></div>
      <div className="hd-tools">
        {/* ⚠️ 셀렉트 **왼쪽**이다. 접히지 않는 자리라야 "열어 보기 전에 알아채는" 일을 한다 */}
        <NewCommentFilter stats={stats} filter={filter} setFilter={setFilter} />
        <StatusFilterSelect stats={stats} filter={filter} setFilter={setFilter} />
        <ExportButton filter={filter} q={q} />
      </div>
    </div>
  )
}
