package com.iseonjwa.payment.ops.application;

import java.util.List;

/**
 * 명령이 읽기만 해서 만든 계획. dry-run은 이것을 출력하고, {@code --apply}는 다시 만든 계획의 해시가 확인 값과 같을
 * 때만 적용한다.
 *
 * @param target      바꿀 대상
 * @param snapshot    현재 상태 스냅숏. 계획 뒤 바뀌면 해시가 달라지도록 판단에 쓴 값을 모두 담는다
 * @param pgEvidence  PG 근거 요약(JSON). PG를 조회하지 않는 명령은 null이다. 시각처럼 조회마다 달라지는 값은 넣지 않는다
 * @param transition  계획한 전이의 정규화 문자열(예: {@code FAILED -> PENDING})
 * @param fromStatus  감사 기록에 남길 전이의 앞 상태
 * @param toStatus    감사 기록에 남길 전이의 뒤 상태
 * @param events      발행할 이벤트. 없으면 빈 목록이다
 * @param externalRef 외부 조치 참조(환불 종결·콘솔 환불). 해당하지 않으면 null이다
 * @param details     dry-run에만 보여 주는 참고 줄. 해시에 들어가지 않는다
 */
public record OpsPlan(OpsTarget target, String snapshot, String pgEvidence, String transition, String fromStatus,
        String toStatus, List<String> events, String externalRef, List<String> details) {
    public OpsPlan {
        events = List.copyOf(events);
        details = List.copyOf(details);
    }
}
