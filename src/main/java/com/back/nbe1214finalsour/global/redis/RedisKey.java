package com.back.nbe1214finalsour.global.redis;

public enum RedisKey {

    // 인증 (Auth) - emailHash는 EmailHasher.hash 값 (키에 이메일 원문을 넣지 않는다)
    /** 인증 코드. of(emailHash). field: code, attempts (attempts는 HINCRBY 대상). TTL 5분 */
    AUTH_EMAIL_CODE("auth:email:code:%s", Structure.HASH, ValueFormat.PLAIN),
    /** 인증 쿨다운. of(emailHash). SET NX + TTL 60초 */
    AUTH_EMAIL_COOLDOWN("auth:email:cooldown:%s", Structure.STRING, ValueFormat.PLAIN),
    /** 인증 일일 한도. of(emailHash, 한국 날짜 yyyyMMdd). INCR, 처음 만들 때 TTL 1일 */
    AUTH_EMAIL_DAILY("auth:email:daily:%s:%s", Structure.STRING, ValueFormat.PLAIN),
    /** 가입 토큰. of(signupToken). 값: 정규화한 이메일. TTL 30분 */
    AUTH_SIGNUP_TOKEN("auth:signup:%s", Structure.STRING, ValueFormat.PLAIN),
    /** 로그인 연속 실패 횟수. of(emailHash). INCR, 처음 만들 때 TTL 10분 */
    AUTH_LOGIN_FAIL("auth:login:fail:%s", Structure.STRING, ValueFormat.PLAIN),
    /** 로그인 잠금. of(emailHash). SET EX 10분 */
    AUTH_LOGIN_LOCK("auth:login:lock:%s", Structure.STRING, ValueFormat.PLAIN),
    /**
     * 현재 로그인 세션 (계정당 1기기). of(userId). 값: 세션 ID(UUID). TTL = {@code jwt.refresh-token-ttl}, 로그인·재발급 때 다시 설정.
     * 읽기·쓰기는 {@code LoginSessionStore}만 한다
     */
    AUTH_SESSION("auth:session:%s", Structure.STRING, ValueFormat.PLAIN),

    // 회원 (User)
    /**
     * 회원 상태 캐시. ACTIVE / SUSPENDED / BANNED / WITHDRAWN. 원본은 DB {@code users.status}, TTL은 {@code jwt.access-token-ttl}.
     * 읽기·쓰기는 {@code UserStatusCache}만 한다. 상태를 바꾸는 코드는 커밋 뒤 {@code UserStatusCache.put}을 부른다
     */
    USER_STATUS("user:%s:status", Structure.STRING, ValueFormat.PLAIN),
    /** 회원 클리커 레벨. INCR로 올릴 수 있어야 한다 */
    USER_CLICKER("user:%s:clicker", Structure.STRING, ValueFormat.PLAIN),
    /** 적용 중인 효과. field: itemId, multiplier, expiredAt */
    USER_EFFECT("user:%s:effect", Structure.HASH, ValueFormat.JSON),

    // 아이템 (Item)
    /** 아이템 효과 정의. field: multiplier */
    ITEM_EFFECT("item:%s", Structure.HASH, ValueFormat.JSON),

    // 순위 (ZSet)
    /** 학교 점수·순위. member: universityId, score: 학교 점수 */
    RANK_UNIV("rank:univ", Structure.ZSET, ValueFormat.PLAIN),
    /** 학과 점수·순위. member: departmentId, score: 학과 점수 */
    RANK_DEPT("rank:univ:%s:dept", Structure.ZSET, ValueFormat.PLAIN),
    /** 개인 기여도·순위. member: userId, score: 기여도 */
    RANK_USER("rank:univ:%s:user", Structure.ZSET, ValueFormat.PLAIN),
    /** 공격 점수. field: userId → 누적 공격 점수 (HINCRBY 대상) */
    SCORE_USER_ATTACK("score:user:attack", Structure.HASH, ValueFormat.PLAIN),
    /** 회원 부정행위 횟수 순위. member: userId, score: 부정행위 횟수 */
    RANK_USER_ABUSE("rank:user:abuse:%s", Structure.ZSET, ValueFormat.PLAIN),

    // 변경 추적 (Batch)
    DIRTY_UNIV("dirty:univ", Structure.SET, ValueFormat.PLAIN),
    DIRTY_DEPT("dirty:dept", Structure.SET, ValueFormat.PLAIN),
    DIRTY_USER("dirty:user", Structure.SET, ValueFormat.PLAIN),

    // 클릭 (Click)
    /** 직전 1초 클릭 수. of(universityId, epochSec), INCR */
    CLICKS_UNIV_SECOND("clicks:univ:%s:%s", Structure.STRING, ValueFormat.PLAIN),
    /** 중복 제출 방지. of(userId, submissionId), SET NX + TTL */
    SUBMIT_DEDUPE("submit:%s:%s", Structure.STRING, ValueFormat.PLAIN),

    // 요청 한도 (RateLimit)
    /** 행동 요청 한도. INCR */
    RATELIMIT_ACTION("ratelimit:action:%s", Structure.STRING, ValueFormat.PLAIN),
    /** 클릭 수 한도. of(userId), INCRBY */
    RATELIMIT_CLICK("ratelimit:click:%s", Structure.STRING, ValueFormat.PLAIN),
    /** 방명록 한도. SET NX + TTL */
    RATELIMIT_GUESTBOOK("ratelimit:guestbook:%s", Structure.STRING, ValueFormat.PLAIN),
    /** 채팅 한도. SET NX + TTL */
    RATELIMIT_CHAT("ratelimit:chat:%s", Structure.STRING, ValueFormat.PLAIN),
    /** 비회원 문의 한도. of(ip), INCR */
    RATELIMIT_SUPPORT("ratelimit:support:%s", Structure.STRING, ValueFormat.PLAIN),

    // 학교 (University)
    /** 커스텀 로고 이미지 URL */
    UNIV_CUSTOM_LOGO("univ:%s:custom-logo", Structure.STRING, ValueFormat.PLAIN),
    /** 소속 연합. allianceId */
    UNIV_ALLIANCE("univ:%s:alliance", Structure.STRING, ValueFormat.PLAIN),
    /** 진행 중 대결. {matchId}:{A|B} */
    UNIV_MATCH("univ:%s:match", Structure.STRING, ValueFormat.PLAIN),

    // 대결·연합 (Match, Alliance)
    /** 대결 점수. field: A, B */
    MATCH_SCORE("match:%s:score", Structure.HASH, ValueFormat.PLAIN),
    /** 연합 점수. field: universityId → 점수 */
    ALLIANCE_SCORE("alliance:%s:score", Structure.HASH, ValueFormat.PLAIN),

    // 캐시 (Cache)
    /** 학교 회원 수 캐시 */
    CACHE_UNIV_MEMBER_COUNT("cache:univ:%s:member-count", Structure.STRING, ValueFormat.PLAIN),
    /** 지도 캐시. 상위 학교 목록 JSON */
    CACHE_MAP_UNIVERSITIES("cache:map:universities", Structure.STRING, ValueFormat.JSON),

    // 채팅 (Chat) - 키가 아니라 Pub/Sub 채널 이름
    /** 학교 채팅 채널. 메시지 JSON */
    CHAT_UNIV_CHANNEL("chat:univ:%s", Structure.CHANNEL, ValueFormat.JSON),
    /** 연합 채팅 채널. 메시지 JSON */
    CHAT_ALLIANCE_CHANNEL("chat:alliance:%s", Structure.CHANNEL, ValueFormat.JSON),
    ;

    private final String pattern;
    private final Structure structure;
    private final ValueFormat valueFormat;
    private final int argCount;

    RedisKey(String pattern, Structure structure, ValueFormat valueFormat) {
        this.pattern = pattern;
        this.structure = structure;
        this.valueFormat = valueFormat;
        this.argCount = countArgs(pattern);
    }

    public String of(Object... args) {
        if (args.length != argCount) {
            throw new IllegalArgumentException(
                    "%s 키는 인자 %d개가 필요합니다. 받은 개수: %d".formatted(name(), argCount, args.length)
            );
        }

        for (Object arg : args) {
            String value = String.valueOf(arg);
            if (arg == null || value.isBlank()) {
                throw new IllegalArgumentException("%s 키 인자에 빈 값을 쓸 수 없습니다.".formatted(name()));
            }
            // 네임스페이스 구분자가 값에 섞이면 다른 키와 충돌할 수 있다
            if (value.indexOf(':') >= 0) {
                throw new IllegalArgumentException(
                        "%s 키 인자에 ':'를 쓸 수 없습니다. 값: %s".formatted(name(), value)
                );
            }
        }

        return pattern.formatted(args);
    }

    public String pattern() {
        return pattern;
    }

    public Structure structure() {
        return structure;
    }

    public ValueFormat valueFormat() {
        return valueFormat;
    }

    private static int countArgs(String pattern) {
        int count = 0;
        for (int index = pattern.indexOf("%s"); index >= 0; index = pattern.indexOf("%s", index + 2)) {
            count++;
        }
        return count;
    }

    // Redis 자료구조. CHANNEL은 키가 아니라 Pub/Sub 채널
    public enum Structure {
        STRING, HASH, ZSET, SET, CHANNEL
    }

    public enum ValueFormat {
        // 평문
        PLAIN,
        // JSON 객체를 그대로 담는 값
        JSON
    }
}
