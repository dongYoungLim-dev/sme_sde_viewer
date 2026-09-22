import Avatar from './Avatar.jsx'

/** 아바타 + 이름 한 줄. */
export default function Person({ perId, name }) {
  return <span className="person"><Avatar perId={perId} name={name} /><span className="pn">{name || '-'}</span></span>
}
