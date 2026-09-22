package com.pulmuone.sdeboard.security;

import com.pulmuone.sdeboard.config.ItsmProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 세션 저장소의 **지우기**만 고정한다.
 *
 * <p>로그아웃은 세션이 이미 사라진 뒤에도 눌린다 — 서버 재기동 직후, 만료 후, 두 번 누를 때.
 * 그때 예외가 나면 <b>로그아웃이 실패하고 브라우저에 세션이 남는다.</b>
 * 지우려는 요청이 지울 게 없어서 실패하는 것은 말이 안 된다.
 */
class SessionRegistryTest {

    private final SessionRegistry registry = new SessionRegistry(new ItsmProperties());

    @Test
    void 세션이_없어도_로그아웃은_조용히_끝난다() {
        // 2026-09-09 실제로 500 을 냈다 — ConcurrentHashMap.remove(null) 은 NPE 다
        assertThatCode(() -> registry.remove(null)).doesNotThrowAnyException();
        assertThatCode(() -> registry.remove("")).doesNotThrowAnyException();
        assertThatCode(() -> registry.remove("이미-사라진-세션")).doesNotThrowAnyException();
    }

    @Test
    void 만든_세션은_지워진다() {
        UserSession s = registry.create(1L, "u1", "홍길동", "SME", "at", "rt", "pw".toCharArray());
        assertThat(registry.active()).hasSize(1);

        registry.remove(s.getSessionId());

        assertThat(registry.active()).isEmpty();
        assertThatCode(() -> registry.remove(s.getSessionId())).doesNotThrowAnyException();  // 두 번 눌러도 안전
    }
}
