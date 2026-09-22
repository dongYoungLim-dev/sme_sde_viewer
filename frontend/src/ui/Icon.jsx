import { ICONS } from './icons.js'

/**
 * 아이콘 한 개.
 *
 * ⚠️ `dangerouslySetInnerHTML` 을 쓰는 **이 앱의 유일한 곳**이다. 넣는 값은 `icons.js` 의
 * 정적 상수뿐이고, 서버·사용자 입력이 이 경로로 들어오지 않는다. 그 전제가 깨지면
 * (예: 아이콘을 API 로 받아오게 되면) 이 컴포넌트부터 바꿔야 한다.
 */
export default function Icon({ name }) {
  return <span style={{ display: 'contents' }} dangerouslySetInnerHTML={{ __html: ICONS[name] || '' }} />
}
