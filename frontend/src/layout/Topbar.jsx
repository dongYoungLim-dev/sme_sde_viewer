import { useState } from 'react'
import Icon from '../ui/Icon.jsx'
import { useSearchQuery } from './SearchContext.jsx'
import { useSync } from '../hooks/useRequests.js'
import { useRouter } from '../router/RouterContext.jsx'
import { clockOf } from '../domain/format.js'
import { routeTitle } from '../router/routes.js'

/** 상단바 — 경로 표시 · 검색 · 동기화 · 테마. */
export default function Topbar({ user, pageTitle }) {
  const { route } = useRouter()
  const { q, setQ } = useSearchQuery()
  const { syncNow, busy } = useSync()
  const [theme, setTheme] = useState(document.documentElement.getAttribute('data-theme') || 'system')

  const curTheme = () => theme !== 'system' ? theme : (matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light')
  const toggleTheme = () => {
    const next = curTheme() === 'dark' ? 'light' : 'dark'
    document.documentElement.setAttribute('data-theme', next)
    setTheme(next)
  }

  return (
    <div className="topbar">
      <div><div className="crumb">SDE 보드 · <b>{routeTitle(route)}</b></div><h2 className="pagetitle">{pageTitle}</h2></div>
      <div className="tb-right">
        <div className="search"><Icon name="search" />
          <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="법인·요청번호·제목·담당자" />
          {q && <button className="sx" onClick={() => setQ('')} aria-label="검색어 지우기">×</button>}</div>
        <div className="sync" title={user.syncMessage || ''}>
          <span className="dot-live" style={user.syncState === 'AUTH_FAILED' ? { background: 'var(--danger)' } : undefined} />
          {/* 서버가 ITSM 을 마지막으로 읽은 시각이다 — 브라우저 시계가 아니다.
              세션 폴링(1분)이 갱신하므로 가만히 두어도 5~6분마다 바뀐다. */}
          {user.syncState === 'AUTH_FAILED'
            ? 'ITSM 재인증 필요'
            : <>동기화 <b className="mono">{clockOf(user.lastSyncAt)}</b></>}
        </div>
        <button className="refresh" onClick={syncNow}>
          <span className={busy ? 'spin' : ''} style={{ display: 'inline-flex' }}><Icon name="refresh" /></span>동기화</button>
        <button className="iconbtn" onClick={toggleTheme} aria-label="테마 전환">
          <Icon name={curTheme() === 'dark' ? 'sun' : 'moon'} /></button>
      </div>
    </div>
  )
}
