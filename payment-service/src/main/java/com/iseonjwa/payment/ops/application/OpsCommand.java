package com.iseonjwa.payment.ops.application;

import java.util.Set;

/**
 * 상태를 바꾸는 Ops 명령 하나(ADR 0028). 명령은 이름, 4-eyes 여부, 계획 만들기(읽기 전용), 적용하기(트랜잭션)만
 * 선언하고, 인자 해석·허용 목록·승인자·dry-run·해시 비교·감사·종료 코드는 {@link OpsRunner}가 맡는다.
 * 새 명령은 이 인터페이스를 구현한 빈 하나를 더하면 된다.
 */
public interface OpsCommand {
    /** 명령 이름. 두 단어일 수 있다(예: {@code outbox requeue}). */
    String name();

    /** 돈이나 이벤트가 움직이는 명령이면 true다. {@code --approver}가 필요하다. */
    boolean requiresApprover();

    /** 이 명령이 받는 옵션 이름. 공통 옵션(operator, approver, apply, confirm, reason)은 제외한다. */
    Set<String> options();

    /** 인자만으로 대상을 정한다. 계획을 만들 수 없는 거부도 이 대상에 기록한다. */
    OpsTarget target(OpsArguments arguments);

    /**
     * 현재 DB(와 필요하면 PG)를 읽어 계획을 만든다. 상태를 바꾸지 않는다.
     *
     * @throws OpsRejection 대상이 없거나 근거가 맞지 않아 계획을 만들 수 없을 때
     */
    OpsPlan plan(OpsArguments arguments);

    /**
     * 계획을 적용한다. 호출한 쪽({@link OpsRunner})이 연 트랜잭션 안에서 실행하며, 감사 APPLIED 기록도 같은
     * 트랜잭션에 쓴다. 조건부 UPDATE가 1행이 아니면 {@link OpsRejection}을 던져 롤백한다.
     *
     * @return 실제로 적용한 계획. 대개 받은 계획 그대로다. 적용해 봐야 전이가 정해지는 명령(결제 resolve의 늦은 승인
     *         방지)은 실제 전이와 PG 근거를 담은 계획을 돌려주고, 실행기는 그것을 감사 기록과 출력에 쓴다. 계획 해시는
     *         dry-run이 보인 원래 계획으로 비교하므로 바뀌지 않는다
     */
    OpsPlan apply(OpsPlan plan, OpsApplyContext context);
}
