package com.pulmuone.sdeboard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * 분석 노트의 **저장 경계**를 고정한다.
 *
 * <p>리치 에디터가 붙는 순간 "사용자가 보낸 문서를 그대로 저장했다가 화면에 그린다" 가 된다.
 * 그래서 이 서비스는 들어온 Delta 를 <b>통과시키지 않고 다시 조립한다</b> — 모르는 것은 남지 않는다.
 * 여기가 무너지면 SME 가 쓴 노트를 SDE 가 여는 순간이 공격 지점이 된다.
 *
 * <p>임시저장·공유 이력의 <b>흐름</b>은 {@link RequestNoteDraftTest} 가 따로 고정한다.
 */
class RequestNoteServiceTest {

    private final RequestNoteService svc = new RequestNoteService(
            mock(com.pulmuone.sdeboard.repo.RequestNoteRepository.class),
            mock(com.pulmuone.sdeboard.repo.RequestNoteRevisionRepository.class),
            mock(com.pulmuone.sdeboard.repo.NoteReadRepository.class),
            mock(com.pulmuone.sdeboard.repo.ItsmRequestRepository.class),
            mock(com.pulmuone.sdeboard.repo.AppUserRepository.class),
            mock(DashboardService.class),
            new ObjectMapper());

    @Test
    void 아는_서식만_남기고_나머지는_버린다() {
        String in = """
            {"ops":[
              {"insert":"제목","attributes":{"bold":true,"header":2,"onclick":"evil()","style":"x"}},
              {"insert":"\\n"}
            ]}""";
        String out = svc.normalizeDelta(in);
        assertThat(out).contains("\"bold\":true").contains("\"header\":2");
        assertThat(out).doesNotContain("onclick").doesNotContain("style");
    }

    @Test
    void 링크는_http_https_mailto_만_남는다() {
        String bad = """
            {"ops":[{"insert":"클릭","attributes":{"link":"javascript:alert(1)"}}]}""";
        assertThat(svc.normalizeDelta(bad)).doesNotContain("javascript");
        assertThat(svc.normalizeDelta(bad)).doesNotContain("\"link\"");

        String good = """
            {"ops":[{"insert":"문서","attributes":{"link":"https://itsm.example.com/x"}}]}""";
        assertThat(svc.normalizeDelta(good)).contains("https://itsm.example.com/x");

        // 대소문자를 섞어도 통과하면 안 된다
        String tricky = """
            {"ops":[{"insert":"클릭","attributes":{"link":"JaVaScRiPt:alert(1)"}}]}""";
        assertThat(svc.normalizeDelta(tricky)).doesNotContain("link");
    }

    @Test
    void 임베드는_저장하지_않는다() {
        // 이미지 첨부는 1단계에서 뺐다 — 붙이는 순간 파일 저장·용량·백업이 따라온다
        String in = """
            {"ops":[{"insert":{"image":"data:image/png;base64,AAAA"}},{"insert":"본문"}]}""";
        String out = svc.normalizeDelta(in);
        assertThat(out).doesNotContain("image").doesNotContain("base64");
        assertThat(out).contains("본문");
    }

    @Test
    void 형식이_아니면_거부한다() {
        assertThatThrownBy(() -> svc.normalizeDelta("아무 텍스트"))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> svc.normalizeDelta("{\"ops\":\"배열이 아님\"}"))
                .isInstanceOf(ResponseStatusException.class);
        // 빈 입력은 오류가 아니라 '빈 노트'다 — 처음 여는 화면이 그 상태다
        assertThat(svc.normalizeDelta(null)).isEqualTo("{\"ops\":[]}");
        assertThat(svc.normalizeDelta("  ")).isEqualTo("{\"ops\":[]}");
    }

    @Test
    void 상한을_넘으면_거부한다() {
        String huge = "{\"ops\":[{\"insert\":\"" + "가".repeat(RequestNoteService.MAX_DELTA_BYTES) + "\"}]}";
        assertThatThrownBy(() -> svc.normalizeDelta(huge))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("너무 큽니다");
    }

    @Test
    void 평문은_서식을_버리고_글자만_남긴다() {
        String delta = svc.normalizeDelta("""
            {"ops":[{"insert":"발주 화면 ","attributes":{"bold":true}},{"insert":"수정\\n"}]}""");
        assertThat(svc.plainText(delta)).isEqualTo("발주 화면 수정");
    }
}
