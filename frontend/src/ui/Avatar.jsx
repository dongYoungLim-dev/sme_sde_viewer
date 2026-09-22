import { colorFor } from '../domain/format.js'

/** 이름 첫 글자 동그라미. 색은 perId 해시라 같은 사람은 늘 같은 색이다. */
export default function Avatar({ perId, name, cls = '' }) {
  return <span className={'ava ' + cls} style={{ background: colorFor(perId) }}>{name ? name[0] : '?'}</span>
}
