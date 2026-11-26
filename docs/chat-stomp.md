# STOMP 실시간 채팅 명세

## 연결과 메시지

SockJS 없이 WebSocket `/ws`에 연결합니다. STOMP `CONNECT` 프레임에 `Authorization: Bearer <access 토큰>` 헤더를 넣습니다.

| 채팅방 | 구독 (`SUBSCRIBE`) | 전송 (`SEND`) |
| --- | --- | --- |
| 내 학교 | `/topic/chat/universities/{universityId}` | `/app/chat/university` |
| 현재 소속 연합 | `/topic/chat/alliances/{allianceId}` | `/app/chat/alliance` |

전송 본문은 `{"content":"메시지"}`입니다. `content`는 앞뒤 공백을 제거한 뒤 유니코드 코드 포인트 기준으로 1~200자여야 합니다. 전송할 방은 서버가 정하므로 본문에 방 ID를 넣지 않습니다. 이전 메시지는 Swagger의 **채팅** 항목에 있는 REST API에서 커서로 조회합니다.

## 오류 처리

두 경로 모두 오류 본문은 `code`와 `message`가 있는 JSON입니다.

### 프레임 인증·권한 오류

서버가 `ERROR` 프레임을 보낸 뒤 연결을 종료합니다.

| 코드 | 의미와 대응 |
| --- | --- |
| `COMMON_UNAUTHORIZED` | 인증이 필요합니다. 토큰을 넣어 연결합니다. |
| `COMMON_INVALID_TOKEN` | 토큰이 유효하지 않습니다. |
| `COMMON_EXPIRED_TOKEN` | 토큰이 만료되었습니다. 재발급 후 다시 연결합니다. |
| `AUTH_SESSION_REPLACED` | 세션이 교체되었습니다. 로그인 화면으로 이동합니다. |
| `CHAT_NOT_MEMBER` | 구독하려는 학교나 연합에 속하지 않았습니다. |
| `COMMON_FORBIDDEN` | 허용되지 않은 목적지입니다. |

### 메시지 처리 오류

연결을 유지하고 `/user/queue/errors`로 오류를 보냅니다. 이 큐를 구독해야 오류를 받을 수 있습니다.

| 코드 | 의미 |
| --- | --- |
| `CHAT_INVALID_CONTENT` | 메시지 본문이 비어 있거나 길이 제한을 벗어났습니다. |
| `CHAT_TOO_FREQUENT` | 메시지를 너무 자주 보냈습니다. |
| `USER_SUSPENDED` | 채팅 이용이 제한된 회원입니다. |
| `USER_NOT_FOUND` | 회원을 찾을 수 없습니다. |
| `CHAT_NOT_MEMBER` | 전송할 연합에 속하지 않았습니다. |
| `COMMON_INVALID_INPUT` | 메시지 본문 형식이 올바르지 않습니다. |
| `COMMON_INTERNAL_ERROR` | 서버에서 메시지를 처리하지 못했습니다. |
