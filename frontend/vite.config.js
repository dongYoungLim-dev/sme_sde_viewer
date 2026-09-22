import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// /api 요청을 백엔드로 프록시 → 브라우저 입장에선 같은 오리진이라 CORS 자체가 없다.
// Docker 실행 시 BACKEND_URL=http://backend:8080, 로컬 실행 시 기본 localhost:8080.
const backend = process.env.BACKEND_URL || 'http://localhost:8080'

// Vite 5.4.12+ 는 Host 헤더를 검사한다(DNS 리바인딩 방어).
// IP 주소는 기본 허용이지만 **호스트명은 차단**되므로 Tailscale MagicDNS(*.ts.net)로 접속하면
// "Blocked request. This host is not allowed." 403 이 뜬다. 브라우저에선 CORS 오류처럼 보인다.
//   VITE_ALLOWED_HOSTS=all              → 전부 허용(신뢰된 망에서만)
//   VITE_ALLOWED_HOSTS=a.ts.net,b.local → 지정 호스트만 허용
const env = process.env.VITE_ALLOWED_HOSTS
const allowedHosts = env === 'all' ? true
  : env ? env.split(',').map(s => s.trim()).filter(Boolean)
  : ['.ts.net']        // 기본: Tailscale MagicDNS 도메인 전체 허용

export default defineConfig({
  plugins: [react()],
  server: {
    host: true,       // 0.0.0.0 바인딩 (컨테이너/원격 접근 허용)
    port: 5173,
    allowedHosts,
    proxy: { '/api': backend }
  }
})
