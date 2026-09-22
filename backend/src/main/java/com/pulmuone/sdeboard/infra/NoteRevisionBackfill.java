package com.pulmuone.sdeboard.infra;

import com.pulmuone.sdeboard.domain.RequestNote;
import com.pulmuone.sdeboard.domain.RequestNoteRevision;
import com.pulmuone.sdeboard.repo.RequestNoteRepository;
import com.pulmuone.sdeboard.repo.RequestNoteRevisionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 기동 시 1회 — <b>임시저장 이전에 저장된 노트를 '1차 공유' 로 세운다.</b>
 *
 * <p>2026-09-09 이전의 노트는 저장 = 곧 공유였다. 그래서 이미 있는 행은 전부 <b>공유본</b>이다.
 * 그런데 {@code published_at} 과 이력이 비어 있으면 화면이 <i>"한 번도 공유된 적 없다"</i> 로 읽어
 * <b>[공유 이력] 버튼이 안 뜨고, 읽는 쪽에 보여줄 시각도 없어진다.</b>
 *
 * <p>그래서 {@code published_at = updated_at}(= 그때 저장한 시각) 으로 채우고 {@code seq=1} 이력을 만든다.
 * ⚠️ 변경 사유({@code publish_memo})는 <b>비워 둔다</b> — 그때는 받지 않은 값이라 지어내면 거짓말이 된다.
 * 화면은 비어 있으면 `(최초 공유)` 로 부른다.
 *
 * <p>이미 이력이 있는 요청은 건드리지 않으므로 몇 번을 돌려도 같은 결과다.
 */
@Component
@Order(3)                       // SchemaMigration(0) 이 컬럼·표를 만든 뒤
@RequiredArgsConstructor
@Slf4j
public class NoteRevisionBackfill implements ApplicationRunner {

    private final RequestNoteRepository noteRepo;
    private final RequestNoteRevisionRepository revRepo;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (noteRepo.count() == 0) return;

        int filled = 0;
        for (RequestNote n : noteRepo.findAll()) {
            // 내용이 없는 행은 공유된 적이 없는 것으로 둔다 — 빈 이력을 만들면 목록만 지저분해진다
            if (n.getBodyText() == null || n.getBodyText().isBlank()) continue;
            if (revRepo.countByReqNo(n.getReqNo()) > 0) continue;

            if (n.getPublishedAt() == null) n.setPublishedAt(n.getUpdatedAt());
            noteRepo.save(n);

            RequestNoteRevision r = new RequestNoteRevision();
            r.setReqNo(n.getReqNo());
            r.setSeq(1);
            r.setBodyDelta(n.getBodyDelta());
            r.setBodyText(n.getBodyText());
            r.setAuthorId(n.getAuthorId());
            r.setPublishedAt(n.getPublishedAt());
            revRepo.save(r);
            filled++;
            log.info("노트 공유 이력 백필 reqNo={} seq=1 publishedAt={}", n.getReqNo(), n.getPublishedAt());
        }
        if (filled > 0) log.info("노트 공유 이력 백필 완료 — {}건", filled);
    }
}
