import { useEffect, useRef, useState } from 'react'
import Icon from './Icon.jsx'

/**
 * 커스텀 셀렉트(listbox) — **이 앱의 드롭다운은 전부 이것 하나다.**
 *
 * 네이티브 `<select>` 는 옵션에 건수·상태색·아바타를 넣을 수 없어 직접 만든다.
 *
 * ⚠️ 예전에는 같은 `.csel` 마크업이 **세 벌**이었다(상태 필터 · 인력풀 배정 · 법인 추가).
 * 키보드 조작과 '바깥 클릭으로 닫기' 는 상태 필터에만 있어서, **인력풀 드롭다운은
 * Esc 로도 바깥 클릭으로도 닫히지 않았다.** 같은 CSS 를 쓰는데 동작이 달랐던 것이다.
 * 그래서 동작을 여기 한 곳에 두고, 생김새만 `button`/`renderOption` 으로 갈아 끼운다.
 *
 * 옵션 한 개: `{ key, label, count, dotVar, divider, disabled, hint }`
 *  - `divider` = 위에 구분선(원래의 `sep`)
 *  - `disabled` = 고를 수 없음(이미 다른 팀이 담당하는 법인 등)
 */
export default function Select({
  options,
  value,
  onChange,
  ariaLabel,
  caption,
  button,
  renderOption,
  up = false,
  emptyText = null,
  className = ''
}) {
  const [open, setOpen] = useState(false)
  const [hi, setHi] = useState(0)
  const ref = useRef(null)

  const idx = Math.max(0, options.findIndex(o => o.key === value))
  const current = options[idx] || options[0]

  // 바깥을 누르면 닫는다. (세 벌이던 시절 두 곳에는 이 처리가 없었다)
  useEffect(() => {
    if (!open) return
    setHi(idx)
    const away = (e) => { if (ref.current && !ref.current.contains(e.target)) setOpen(false) }
    document.addEventListener('pointerdown', away)
    return () => document.removeEventListener('pointerdown', away)
  }, [open])

  const pick = (opt) => {
    if (!opt || opt.disabled) return
    setOpen(false)
    onChange(opt.key, opt)
  }

  const move = (step) => {
    if (!options.length) return
    let n = hi
    for (let i = 0; i < options.length; i++) {
      n = (n + step + options.length) % options.length
      if (!options[n].disabled) break
    }
    setHi(n)
  }

  const onKey = (e) => {
    if (e.key === 'Escape') { setOpen(false); return }
    if (!open) {
      if (e.key === 'Enter' || e.key === ' ' || e.key === 'ArrowDown') { e.preventDefault(); setOpen(true) }
      return
    }
    if (e.key === 'ArrowDown') { e.preventDefault(); move(1) }
    else if (e.key === 'ArrowUp') { e.preventDefault(); move(-1) }
    else if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); pick(options[hi]) }
  }

  const defaultButton = () => (
    <>
      {caption && <span className="cs-cap">{caption}</span>}
      {current?.dotVar ? <span className="pd" style={{ background: `var(${current.dotVar})` }} /> : null}
      <span className="cs-l">{current?.label ?? ''}</span>
      {current?.count != null && <span className="qty num">{current.count}</span>}
      <span className="cs-arw"><Icon name="chev" /></span>
    </>
  )

  const defaultOption = (o) => (
    <>
      <span className={'pd' + (o.dotVar ? '' : ' ghost')}
        style={o.dotVar ? { background: `var(${o.dotVar})` } : undefined} />
      <span className="cs-l">{o.label}</span>
      {o.count != null && <span className="qty num">{o.count}</span>}
    </>
  )

  return (
    <div className={('csel ' + className).trim() + (open ? ' open' : '')} ref={ref} onKeyDown={onKey}>
      <button type="button" className={button ? 'pool-btn' : 'csel-btn'}
        aria-haspopup="listbox" aria-expanded={open} aria-label={ariaLabel}
        onClick={() => setOpen(o => !o)}>
        {button ? button({ open, current }) : defaultButton()}
      </button>
      {open && (
        <ul className={'csel-list' + (up ? ' up' : '')} role="listbox" aria-label={ariaLabel}>
          {!options.length && emptyText && <li className="none">{emptyText}</li>}
          {options.map((o, i) => (
            <li key={o.key} role="option" aria-selected={o.key === value}
              className={[o.divider ? 'sep' : '', i === hi ? 'hi' : '', o.key === value ? 'on' : '',
                o.disabled ? 'taken' : ''].filter(Boolean).join(' ')}
              onMouseEnter={() => setHi(i)}
              onClick={() => pick(o)}>
              {renderOption ? renderOption(o) : defaultOption(o)}
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
