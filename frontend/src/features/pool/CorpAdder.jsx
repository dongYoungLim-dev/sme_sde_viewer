import { useState } from 'react'
import Icon from '../../ui/Icon.jsx'
import Select from '../../ui/Select.jsx'

const MANUAL = '__manual__'

/**
 * 담당 **법인** 추가 — 우리 팀이 맡는 법인 블록을 하나 세운다.
 *
 * ⚠️ 시스템은 여기서 고르지 않는다(2026-09-10 변경). 법인 블록이 선 뒤 **그 블록 안에서**
 * 고른다(`SystemAdder`) — 한 법인에 시스템이 여러 개 붙기 때문에 한 번의 등록으로 끝나지 않는다.
 * 이미 표에 있는 법인은 후보에서 빠진다(`inTable`).
 */
export default function CorpAdder({ corpOptions, onAdd }) {
  const [manual, setManual] = useState('')      // '' = 직접 입력창 닫힘

  const options = [
    ...corpOptions.filter(o => !o.inTable).map(o => ({
      key: o.corpNm, label: o.corpNm, disabled: !!o.takenByTeam, opt: o
    })),
    { key: MANUAL, label: '직접 입력…', divider: true }
  ]

  return (
    <div className="corp-add">
      <Select
        options={options} value="" up
        ariaLabel="담당 법인 추가"
        emptyText="추가할 법인이 없습니다"
        onChange={(key) => (key === MANUAL ? setManual(' ') : onAdd(key, null))}
        button={() => <>+ 법인 추가</>}
        renderOption={(o) => o.key === MANUAL
          ? <span className="cs-l">{o.label}</span>
          : (
            <>
              <span className="cs-l">{o.label}
                {/* ITSM 요청에서 이 이름이 관측된 적이 없다 = 표기가 다를 수 있다는 신호 */}
                {!o.opt.observed && <em className="m-cur"> 미관측</em>}</span>
              {o.opt.takenByTeam && <span className="qty">{o.opt.takenByTeam} 담당</span>}
            </>
          )}
      />
      {manual !== '' && (
        <form className="corp-manual" onSubmit={(e) => { e.preventDefault(); onAdd(manual.trim(), null); setManual('') }}>
          <input autoFocus value={manual.trim()} onChange={(e) => setManual(e.target.value || ' ')}
            placeholder="ITSM 에 표기된 법인명 그대로" />
          <button type="submit" className="btn-add">추가</button>
          <button type="button" className="lnk" onClick={() => setManual('')}>취소</button>
        </form>
      )}
      <p className="src-note"><Icon name="info" />
        우리 팀이 담당하는 법인만 올립니다. 시스템은 법인을 올린 뒤 <b>그 법인 안에서</b> 추가합니다.
        <b> 직접 입력할 때는 ITSM 표기와 똑같이</b> 적어 주세요 — 글자가 다르면 같은 법인이 두 줄로
        갈라지고 차수 배지가 붙지 않습니다.</p>
    </div>
  )
}
