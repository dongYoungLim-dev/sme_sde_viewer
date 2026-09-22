// 백엔드 REST 호출의 **전송 계층**. 엔드포인트 목록은 endpoints.js 에 있다.
// 인증: 로그인 시 받은 sessionId 를 X-Session 헤더로 보낸다(세션은 서버 메모리 보관).

import { getSession, setSession } from '../session/storage.js'

export class ApiError extends Error {
  constructor(status, body) {
    super(body?.message || `HTTP ${status}`)
    this.status = status
    this.body = body
  }
}

function headers(extra = {}) {
  const h = { ...extra }
  const s = getSession()
  if (s) h['X-Session'] = s
  return h
}

async function parse(res) {
  const text = await res.text()
  let body = null
  try { body = text ? JSON.parse(text) : null } catch { body = { message: text } }
  if (!res.ok) {
    if (res.status === 401) setSession('')       // 만료 → 재로그인 유도
    throw new ApiError(res.status, body)
  }
  return body
}

export async function get(path, signal) {
  return parse(await fetch(path, { headers: headers(), signal }))
}

export async function post(path, body, signal) {
  return parse(await fetch(path, {
    method: 'POST', signal,
    headers: headers({ 'Content-Type': 'application/json' }),
    body: JSON.stringify(body ?? {})
  }))
}

export async function put(path, body, signal) {
  return parse(await fetch(path, {
    method: 'PUT', signal,
    headers: headers({ 'Content-Type': 'application/json' }),
    body: JSON.stringify(body ?? {})
  }))
}

export async function patch(path, body, signal) {
  return parse(await fetch(path, {
    method: 'PATCH', signal,
    headers: headers({ 'Content-Type': 'application/json' }),
    body: JSON.stringify(body ?? {})
  }))
}

export async function del(path, signal) {
  return parse(await fetch(path, { method: 'DELETE', headers: headers(), signal }))
}

/**
 * 파일 업로드(multipart/form-data). `Content-Type` 을 직접 안 붙인다 — 브라우저가
 * `boundary` 를 채워야 해서, 여기서 지정하면 서버가 못 읽는다.
 */
export async function postForm(path, formData, signal) {
  return parse(await fetch(path, { method: 'POST', signal, headers: headers(), body: formData }))
}

/**
 * 파일 내려받기. 세션은 헤더로만 보내므로(쿼리스트링 노출 금지) `<a href>` 대신 fetch → blob 이다.
 * 파일명은 서버가 Content-Disposition(RFC 5987, UTF-8)으로 준 것을 그대로 쓴다 — 한글 파일명 유지.
 */
export async function download(path, fallbackName) {
  const res = await fetch(path, { headers: headers() })
  if (!res.ok) {
    if (res.status === 401) setSession('')
    let body = null
    try { body = JSON.parse(await res.text()) } catch { body = null }
    throw new ApiError(res.status, body)
  }
  const cd = res.headers.get('Content-Disposition') || ''
  const m = /filename\*=UTF-8''([^;]+)/i.exec(cd)
  const name = m ? decodeURIComponent(m[1]) : fallbackName
  const url = URL.createObjectURL(await res.blob())
  const a = document.createElement('a')
  a.href = url; a.download = name
  document.body.appendChild(a); a.click(); a.remove()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
  return name
}
