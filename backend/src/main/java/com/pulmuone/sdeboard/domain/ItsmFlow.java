package com.pulmuone.sdeboard.domain;

import com.pulmuone.sdeboard.config.ItsmProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * ITSM 상태명 → **순번** 조회. 딱 하나의 일만 한다: 할당 단계(INTAKE/LEADER/FINAL) 판정의 근거.
 *
 * <p>2026-09-22(`UR-260922-1`) — 11단계 진행률·버킷·상세 화면의 단계 표시를 **폐기**했다(사용자 결정).
 * 화면에 보이는 상태는 이제 {@link BoardStatus} 4단계다. 다만 "ITSM 이 공정 할당을 시작했는가"
 * (= SME 단계를 벗어났는가, 최초 진입점 판정)는 ITSM 상태에서만 알 수 있어 **순번 표는 남긴다.**
 * `application.yml` 의 `itsm.flow` 는 이제 seq·code·name 만 쓴다(bucket·progress 는 읽지 않는다).
 */
@Component
@Slf4j
public class ItsmFlow {

    /** 파이프라인 한 단계. code(staCd) 는 확인된 것만 채우고, 매칭은 name(staNm) 으로도 한다. */
    public record Step(int seq, String code, String name) {}

    private final ItsmProperties props;
    private final List<Step> steps;

    public ItsmFlow(ItsmProperties props) {
        this.props = props;
        List<Step> s = new ArrayList<>();
        for (ItsmProperties.FlowStep f : props.getFlow()) s.add(new Step(f.getSeq(), f.getCode(), f.getName()));
        s.sort(Comparator.comparingInt(Step::seq));
        this.steps = List.copyOf(s);
        if (steps.isEmpty()) log.warn("itsm.flow 가 비어 있다 — 할당 단계(INTAKE/LEADER) 판정이 담당자 필드에만 기댄다");
    }

    /** 담당 배정이 시작된 것으로 보는 순번(기본 2 = 공정할당요청). */
    public int assignFromSeq() { return props.getAssignFromSeq(); }

    /** staCd 우선, 없으면 상태명으로 단계를 찾는다. 못 찾으면 null(보류·반려·서비스요청 등). */
    Step resolve(String staCd, String staNm) {
        if (staCd != null && !staCd.isBlank())
            for (Step st : steps) if (staCd.equals(st.code())) return st;

        String key = norm(staNm);
        if (key.isEmpty()) return null;
        for (Step st : steps) if (norm(st.name()).equals(key)) return st;
        Step best = null;
        for (Step st : steps) {
            String n = norm(st.name());
            if (!n.isEmpty() && key.contains(n) && (best == null || n.length() > norm(best.name()).length())) best = st;
        }
        return best;
    }

    /** 순번. 못 찾으면 0. */
    public int seqOf(String staCd, String staNm) {
        Step st = resolve(staCd, staNm);
        return st == null ? 0 : st.seq();
    }

    private static String norm(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "");
    }
}
