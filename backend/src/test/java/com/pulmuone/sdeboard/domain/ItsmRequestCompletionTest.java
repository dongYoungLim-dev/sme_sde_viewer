package com.pulmuone.sdeboard.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ItsmRequest#onDroppedFromTodo()} — 마지막 소유자까지 ITSM To-Do 에서 사라졌을 때의 다음 상태.
 * `UR-260922-1`: 스케줄을 잡았으면 완료, 안 잡았으면 **추적불가**(추정하지 않는다). 예전의 서비스요청 완료 추정을 대체한다.
 */
class ItsmRequestCompletionTest {

    private static ItsmRequest req(String workStatus) {
        ItsmRequest r = new ItsmRequest();
        r.setWorkStatus(workStatus);
        return r;
    }

    @Test
    void 작업중이던_건은_사라지면_작업완료가_된다() {
        ItsmRequest r = req(BoardStatus.IN_PROGRESS);
        assertThat(r.onDroppedFromTodo()).isTrue();
        assertThat(r.getWorkStatus()).isEqualTo(BoardStatus.DONE);
    }

    @Test
    void 대기였던_건은_사라지면_추적불가가_된다() {
        ItsmRequest r = req(BoardStatus.WAITING);
        assertThat(r.onDroppedFromTodo()).isTrue();
        assertThat(r.getWorkStatus()).isEqualTo(BoardStatus.UNTRACKED);
    }

    @Test
    void 상태가_비어_있던_건도_추적불가다() {
        ItsmRequest r = req(null);
        assertThat(r.onDroppedFromTodo()).isTrue();
        assertThat(r.getWorkStatus()).isEqualTo(BoardStatus.UNTRACKED);
    }

    @Test
    void 이미_끝난_건은_다시_건드리지_않는다() {
        ItsmRequest done = req(BoardStatus.DONE);
        assertThat(done.onDroppedFromTodo()).isFalse();
        assertThat(done.getWorkStatus()).isEqualTo(BoardStatus.DONE);

        ItsmRequest untracked = req(BoardStatus.UNTRACKED);
        assertThat(untracked.onDroppedFromTodo()).isFalse();
        assertThat(untracked.getWorkStatus()).isEqualTo(BoardStatus.UNTRACKED);
    }

    @Test
    void 요청유형은_상관없다() {
        // 예전에는 서비스요청만 완료로 추정했다. 이제 유형이 아니라 스케줄 유무가 정한다.
        ItsmRequest r = req(BoardStatus.WAITING);
        r.setReqTypNm("서비스요청");
        r.onDroppedFromTodo();
        assertThat(r.getWorkStatus()).isEqualTo(BoardStatus.UNTRACKED);
    }
}
