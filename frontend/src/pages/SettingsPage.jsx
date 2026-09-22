import { useState } from 'react'
import Icon from '../ui/Icon.jsx'
import { api } from '../api/endpoints.js'
import { useSession } from '../session/SessionContext.jsx'
import { LEADER_RANK, ROLES, isLeader } from '../domain/role.js'

/**
 * 마이페이지(설정).
 *
 * **고칠 수 있는 것은 SDE 리더의 정/부 하나뿐이다.** 팀·법인·역할은 여기서 열지 않는다 —
 * 그 셋은 조회 범위 그 자체라, 마이페이지에서 고칠 수 있게 하면 **자기 범위를 자기가 바꾸는 경로**가 된다.
 * 서버도 `PATCH /api/auth/me` 에서 `leaderRank` 만 받는다(둘 다 막아 둔다).
 */
export default function SettingsPage() {
  const { me: user, run, reloadMe } = useSession()
  const leader = isLeader(user.role)
  const [rank, setRank] = useState(user.leaderRank || '')
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState('')
  const [err, setErr] = useState('')

  const save = async (next) => {
    setRank(next); setBusy(true); setMsg(''); setErr('')
    try {
      await run(() => api.updateMe(next), { silent: true })
      setMsg('저장했습니다.')
      reloadMe()
    } catch (e) {
      // status 가 없으면 응답 자체를 못 받은 것이다(CORS·네트워크) — 서버가 거부한 것과 구분해서 알린다
      setErr(e.body?.message || (e.status ? `저장하지 못했습니다. (${e.status})` : '서버에 연결하지 못했습니다.'))
      setRank(user.leaderRank || '')
    } finally { setBusy(false) }
  }

  return (
    <>
      <div className="card pop">
        <div className="hd"><div className="hd-l"><h3>내 정보</h3>
          <span className="cnt">{ROLES[user.role]?.l || user.role}</span></div></div>
        <dl className="kv mypage">
          <dt>이름</dt><dd>{user.name}</dd>
          <dt>ITSM 계정</dt><dd className="mono sel">{user.loginId}</dd>
          <dt>역할</dt><dd>{ROLES[user.role]?.l || user.role}</dd>
          <dt>{user.role === 'SME' ? '소속 법인' : '소속 팀'}</dt><dd>{user.corpNm || user.team || '—'}</dd>
          <dt>조회 범위</dt><dd>{user.scopeLabel}</dd>
        </dl>
      </div>

      {/* 정/부는 리더에게만 있는 값이라 **섹션 자체를 감춘다**(사용자 지정 2026-09-09).
          "당신에겐 해당 없음" 을 보여주는 것도 화면을 채우는 소음이다. */}
      {leader && (
        <div className="card" style={{ marginTop: 22 }}>
          <div className="hd"><div className="hd-l"><h3>정 / 부</h3>
            <span className="cnt">SDE 리더 전용</span></div>
            {busy && <span className="cnt">저장 중…</span>}
            {msg && <span className="cnt">{msg}</span>}
            {err && <span className="x-err">{err}</span>}
          </div>
          <div style={{ padding: '4px 20px 18px' }}>
            <div className="rank-pick">
              {Object.entries(LEADER_RANK).map(([key, r]) => (
                <button key={key} type="button" className="rank-opt" aria-pressed={rank === key}
                  disabled={busy} onClick={() => save(key)}>{r.d}</button>
              ))}
            </div>
            <p className="src-note" style={{ margin: '12px 0 0' }}><Icon name="info" />
              <b>권한 차이는 없습니다.</b> 목록에서 누가 정/부인지 구분하기 위한 표시이며,
              로그인하면 좌측 하단 이름 옆에 나타납니다.</p>
          </div>
        </div>
      )}
    </>
  )
}
