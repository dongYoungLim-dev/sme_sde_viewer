import { useState } from 'react'
import Select from '../../ui/Select.jsx'

const MANUAL = '__manual__'

/**
 * 법인 블록 안에서 **시스템 줄 추가**.
 *
 * 법인을 고르는 칸이 없다 — 어느 법인인지는 **이 블록이 이미 말하고 있다**(사용자 요청 2026-09-10).
 * 예전에는 화면 맨 아래에서 법인과 시스템을 함께 골랐는데, 법인마다 블록이 생긴 뒤로는
 * 고른 법인과 줄이 설 자리가 멀어져 어디에 붙는지 눈으로 확인할 수 없었다.
 */
export default function SystemAdder({ corpNm, systemOptions, taken, onAdd }) {
  const [manual, setManual] = useState('')      // '' = 직접 입력창 닫힘

  const options = [
    ...systemOptions.filter(s => !taken.has(s)).map(s => ({ key: s, label: s })),
    { key: MANUAL, label: '직접 입력…', divider: true }
  ]

  return (
    <div className="sys-add">
      <Select
        options={options} value="" up
        ariaLabel={`${corpNm} 시스템 추가`}
        emptyText="추가할 시스템이 없습니다"
        onChange={(key) => (key === MANUAL ? setManual(' ') : onAdd(corpNm, key))}
        button={() => <>+ 시스템 추가</>}
        renderOption={(o) => <span className="cs-l">{o.label}</span>}
      />
      {manual !== '' && (
        <form className="corp-manual" onSubmit={(e) => { e.preventDefault(); onAdd(corpNm, manual.trim()); setManual('') }}>
          <input autoFocus value={manual.trim()} onChange={(e) => setManual(e.target.value || ' ')}
            placeholder="시스템명" />
          <button type="submit" className="btn-add">추가</button>
          <button type="button" className="lnk" onClick={() => setManual('')}>취소</button>
        </form>
      )}
    </div>
  )
}
