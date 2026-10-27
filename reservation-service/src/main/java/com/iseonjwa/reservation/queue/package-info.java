/**
 * 대기열(Redis) 모듈. 서비스 분리 근거가 쌓이면 이 패키지 단위로 떼어 낸다(결정 기록 #9). 다른 모듈의 domain·infrastructure를 직접 참조하지 않고 application을 통해 호출한다.
 */
package com.iseonjwa.reservation.queue;
