package com.pulmuone.sdeboard.domain;


import com.pulmuone.sdeboard.config.ItsmProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 application.yml 의 itsm.flow 로 **순번 조회**를 검증한다.
 * 2026-09-22 — 진행률·버킷은 폐기됐다(`UR-260922-1`). 남은 쓰임은 할당 단계(INTAKE/LEADER) 판정의 근거인 순번뿐이다.
 */
class ItsmFlowTest {

    private final ItsmFlow flow = load();

    private static ItsmFlow load() {
        try {
            PropertySource<?> ps = new YamlPropertySourceLoader()
                    .load("app", new ClassPathResource("application.yml")).get(0);
            Binder binder = new Binder(ConfigurationPropertySources.from(ps));
            ItsmProperties props = new ItsmProperties();
            props.setFlow(binder.bind("itsm.flow", Bindable.listOf(ItsmProperties.FlowStep.class)).get());
            props.setAssignFromSeq(binder.bind("itsm.assign-from-seq", Bindable.of(Integer.class)).orElse(2));
            return new ItsmFlow(props);
        } catch (Exception e) {
            throw new IllegalStateException("application.yml 의 itsm.flow 바인딩 실패", e);
        }
    }

    @Test
    void 대기는_배포요청승인_직전_단계다() {
        assertThat(flow.seqOf("00581", null)).isEqualTo(6);
        assertThat(flow.seqOf(null, "대기")).isEqualTo(6);
        // 이름이 짧아도 더 긴 단계명이 우선한다 — '…대기' 류를 잘못 흡수하면 안 된다
        assertThat(flow.seqOf(null, "배포요청승인 대기")).isEqualTo(7);
    }

    @Test
    void 상태코드가_상태명보다_우선한다() {
        assertThat(flow.seqOf("00566", "엉뚱한 이름")).isEqualTo(1);
        assertThat(flow.seqOf("00584", null)).isEqualTo(8);
        assertThat(flow.seqOf("00622", null)).isEqualTo(3);
        assertThat(flow.seqOf("00635", null)).isEqualTo(11);
    }

    @Test
    void 상태명_공백_차이는_무시한다() {
        assertThat(flow.seqOf(null, "변경완료 확인")).isEqualTo(11);
        assertThat(flow.seqOf(null, "배포접수및준비")).isEqualTo(8);
        // 배포승인 이 배포요청승인 을 잘못 흡수하면 안 된다 (실제 ITSM 은 "배포요청 승인"으로 띄운다)
        assertThat(flow.seqOf(null, "배포요청승인")).isEqualTo(7);
        assertThat(flow.seqOf(null, "배포요청 승인")).isEqualTo(7);
        assertThat(flow.seqOf(null, "배포승인")).isEqualTo(9);
    }

    @Test
    void 파이프라인_밖_상태는_순번이_0이다() {
        assertThat(flow.seqOf(null, "처리보류")).isZero();
        assertThat(flow.seqOf(null, "요청검토")).isZero();      // 서비스요청 상태는 이 표에 없다
    }

    @Test
    void 공정할당요청부터_할당이_시작된_것으로_본다() {
        assertThat(flow.seqOf(null, "공정할당요청")).isGreaterThanOrEqualTo(flow.assignFromSeq());
        assertThat(flow.seqOf(null, "변경접수")).isLessThan(flow.assignFromSeq());
    }
}
