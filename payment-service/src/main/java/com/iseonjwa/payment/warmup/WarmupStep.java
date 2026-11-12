package com.iseonjwa.payment.warmup;

/**
 * 웜업 한 단계. {@code action}은 읽기 전용 조각 하나를 한 번 실행한다. 예외를 던지면 그 단계는 거기서 그만둔다.
 */
public record WarmupStep(String name, Runnable action) {}
