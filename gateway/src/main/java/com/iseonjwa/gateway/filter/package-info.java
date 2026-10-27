/**
 * Gateway 필터 자리. 인증 이슈에서 채운다 (SA 6-2, 7-3, 9-5).
 * <ul>
 *   <li>JWT 검증 후 {@code X-User-Id}·{@code X-User-Role} 전달. 클라이언트가 보낸 같은 헤더는 지운다</li>
 *   <li>로그아웃 블랙리스트 확인 (Redis, 타임아웃 100ms + 서킷 브레이커)</li>
 *   <li>입장 토큰 검증, 사용자 단위 요청 제한</li>
 * </ul>
 */
package com.iseonjwa.gateway.filter;
