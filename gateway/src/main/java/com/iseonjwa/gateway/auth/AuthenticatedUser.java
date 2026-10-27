package com.iseonjwa.gateway.auth;

/**
 * 검증을 통과한 Access 토큰의 내용.
 *
 * @param userId  {@code sub}. users.id (BIGINT)
 * @param role    {@code role}. USER 또는 ADMIN (SA 12-2)
 * @param tokenId {@code jti}. 로그아웃 블랙리스트 키 (SA 12-8)
 */
public record AuthenticatedUser(String userId, String role, String tokenId) {
}
