import { useSession } from './SessionContext.jsx'
import Icon from '../ui/Icon.jsx'

/**
 * 화면 공통 실패 배너.
 *
 * 이게 없던 시절 `doSync` 의 `catch` 는 **완전히 비어 있어서**, ITSM 재인증이 필요한 상황에도
 * 동기화 시각만 갱신되고 아무 말이 없었다. 실패는 어디서 나든 사용자가 볼 수 있어야 한다.
 */
export default function ErrorBanner() {
  const { error, clearError } = useSession()
  if (!error) return null
  return (
    <div className="app-error" role="alert">
      <Icon name="warn" />
      <span>{error}</span>
      <button type="button" className="ae-x" onClick={clearError} aria-label="닫기">×</button>
    </div>
  )
}
