import { useState } from 'react'
import { api } from '../api/endpoints.js'
import { useAsyncData } from '../hooks/useAsyncData.js'
import { useSession } from '../session/SessionContext.jsx'
import { getSavedLoginId, setSavedLoginId, setSession } from '../session/storage.js'
import { LEADER_RANK, ROLES } from '../domain/role.js'

/**
 * 로그인 · 회원가입.
 * 인증은 **ITSM 계정**으로 한다. ITSM 비밀번호는 서버에 저장되지 않는다(로그인 세션 메모리 전용).
 */
export default function AuthPage() {
  const { reloadMe } = useSession()
  const [tab, setTab] = useState('login')
  // 저장해 둔 아이디가 있으면 채워 두고 체크박스도 켠 상태로 연다
  const saved = getSavedLoginId()
  const [remember, setRemember] = useState(!!saved)
  const [f, setF] = useState({ itsmUsername: saved, itsmPassword: '', name: '', email: '', role: 'SME', corpNm: '', team: '', leaderRank: '' })
  const [msg, setMsg] = useState('')
  const [busy, setBusy] = useState(false)
  const { data: meta } = useAsyncData(sig => api.meta(sig), [], { silent: true })
  const corps = meta?.corps || []
  const teams = meta?.teams || []
  const set = (k) => (e) => setF({ ...f, [k]: e.target.value })

  const submit = async (e) => {
    e.preventDefault(); setMsg(''); setBusy(true)
    try {
      const loginId = f.itsmUsername.trim()
      const r = tab === 'login'
        ? await api.login(loginId, f.itsmPassword)
        : await api.signup({ ...f, itsmUsername: loginId })
      setSavedLoginId(remember ? loginId : '')     // 아이디만 — 비밀번호는 어디에도 남기지 않는다
      setSession(r.sessionId)
      reloadMe()
    } catch (err) {
      const b = err.body || {}
      setMsg(b.message || '요청에 실패했습니다.')
      if (b.needSignup) setTab('signup')
    } finally { setBusy(false) }
  }

  return (
    <div id="login">
      <form className="login-card" onSubmit={submit}>
        <div className="login-brand"><div className="lb-logo">S</div>
          <div><h1>SDE 작업현황 보드</h1><p>내 ITSM 할 일을 미러링합니다</p></div></div>

        <div className="role-pick" style={{ marginBottom: 4 }}>
          <button type="button" className="role-opt" aria-pressed={tab === 'login'} onClick={() => { setTab('login'); setMsg('') }}>
            <span className="ro-n">로그인</span><span className="ro-r">ITSM 계정</span></button>
          <button type="button" className="role-opt" aria-pressed={tab === 'signup'} onClick={() => { setTab('signup'); setMsg('') }}>
            <span className="ro-n">회원가입</span><span className="ro-r">소속 등록</span></button>
        </div>

        <div className="field"><label>ITSM 계정</label>
          <input value={f.itsmUsername} onChange={set('itsmUsername')} placeholder="p_meta.hong" autoComplete="username" required /></div>
        <div className="field"><label>ITSM 비밀번호</label>
          <input type="password" value={f.itsmPassword} onChange={set('itsmPassword')} autoComplete="current-password" required /></div>

        {tab !== 'signup' && <>
          <label className="remember">
            <input type="checkbox" checked={remember} onChange={(e) => setRemember(e.target.checked)} />
            아이디 저장 <span style={{ color: 'var(--ink-3)', fontWeight: 500 }}>· 비밀번호는 저장하지 않습니다</span>
          </label>
        </>}

        {tab === 'signup' && <>
          <div className="field"><label>이름</label>
            <input value={f.name} onChange={set('name')} placeholder="홍길동" required /></div>
          <label style={{ fontSize: 12, fontWeight: 600, color: 'var(--ink-2)', display: 'block', margin: '4px 0 2px' }}>역할</label>
          <div className="role-pick three">
            {Object.entries(ROLES).map(([key, r]) => (
              <button key={key} type="button" className="role-opt" aria-pressed={f.role === key}
                onClick={() => setF({ ...f, role: key })}>
                <span className="ro-n">{r.l}</span><span className="ro-r">{r.d}</span></button>
            ))}
          </div>
          {f.role === 'SME'
            ? <div className="field"><label>소속 법인</label>
                <input list="corp-list" value={f.corpNm} onChange={set('corpNm')} placeholder="풀무원푸드앤컬처" required />
                <datalist id="corp-list">{corps.map(c => <option key={c} value={c} />)}</datalist>
                {/* 조회 범위를 법인명 문자열로 맞추므로(ITSM 이 법인코드를 주지 않는다) 손으로 다르게 적으면 조용히 0건이 된다 */}
                <p className="field-hint">ITSM 에 표기된 법인명과 <b>똑같이</b> 골라 주세요 — 목록에서 고르는 것이 가장 안전합니다.</p></div>
            : <div className="field"><label>소속 팀</label>
                <input list="team-list" value={f.team} onChange={set('team')} placeholder="SDE1" required />
                <datalist id="team-list">{teams.map(t => <option key={t} value={t} />)}</datalist>
                {f.role === 'SDE' && <p className="field-hint">담당 법인·차수는 <b>팀 리더가 인력풀 화면에서 배정</b>합니다.</p>}</div>}

          {/* 정/부는 리더에게만 묻는다. 권한 차이가 없는 표시용 구분이라 역할과 따로 받는다. */}
          {f.role === 'SDE_LEADER' && (
            <div className="field"><label>정 / 부</label>
              <div className="rank-pick">
                {Object.entries(LEADER_RANK).map(([key, r]) => (
                  <button key={key} type="button" className="rank-opt" aria-pressed={f.leaderRank === key}
                    onClick={() => setF({ ...f, leaderRank: key })}>{r.d}</button>
                ))}
              </div>
              <p className="field-hint">권한 차이는 없습니다 — 목록에서 누가 정/부인지 구분하기 위한 표시입니다.
                나중에 <b>설정</b> 화면에서 바꿀 수 있습니다.</p></div>
          )}
        </>}

        {msg && <p style={{ color: '#c56b78', fontSize: 12, fontWeight: 600, margin: '2px 0 0' }}>{msg}</p>}
        <button className="btn-grad" type="submit" disabled={busy}>
          {busy ? '확인 중…' : (tab === 'login' ? '로그인' : '가입하고 시작하기')}</button>
        <p className="login-hint">
          {tab === 'login'
            ? 'ITSM 계정으로 인증합니다. 비밀번호는 저장되지 않고, 로그인한 동안에만 내 할 일을 가져옵니다.'
            : 'ITSM 로그인으로 계정을 확인합니다. SDE 는 가입·로그인만 하면 되고, ITSM 에서 일하는 방식은 그대로입니다.'}
        </p>
      </form>
    </div>
  )
}
