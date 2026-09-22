// 브라우저에 남기는 것 — **세션 ID 와 아이디뿐이다.**

const KEY = 'sde.session'
const ID_KEY = 'sde.loginId'

export function getSession() { return localStorage.getItem(KEY) || '' }
export function setSession(id) { id ? localStorage.setItem(KEY, id) : localStorage.removeItem(KEY) }

/**
 * '아이디 저장' — **ITSM 계정 ID만** 브라우저에 남긴다.
 * ⚠️ 비밀번호는 저장하지 않는다. 서버도 DB 에 남기지 않는 값이라(세션 메모리 전용),
 * 브라우저에 두면 그 원칙이 여기서 무너진다.
 */
export function getSavedLoginId() { return localStorage.getItem(ID_KEY) || '' }
export function setSavedLoginId(id) { id ? localStorage.setItem(ID_KEY, id) : localStorage.removeItem(ID_KEY) }

/**
 * 다른 탭에서 로그아웃(또는 로그인)하면 알려준다.
 * localStorage 는 React 밖의 상태라, 구독하지 않으면 이 탭만 옛 세션을 붙들고 있게 된다.
 */
export function onSessionChanged(fn) {
  const h = (e) => { if (e.key === KEY) fn(e.newValue || '') }
  window.addEventListener('storage', h)
  return () => window.removeEventListener('storage', h)
}
