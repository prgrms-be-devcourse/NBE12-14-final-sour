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
     * IP별 로그인 실패 횟수 (진행 중인 시도 포함). of(ipHash, {@code EmailHasher.hashClientIp} 값). INCR,
     * 처음 만들 때 TTL {@code auth.ip-limit.login-window}. 비밀번호가 맞으면 1 줄인다
     */
    AUTH_IP_LOGIN_FAIL("auth:ip:login:fail:%s", Structure.STRING, ValueFormat.PLAIN),
    /** IP별 인증 코드 요청 횟수. of(ipHash). INCR, 처음 만들 때 TTL {@code auth.ip-limit.email-code-window} */
    AUTH_IP_EMAIL_CODE("auth:ip:email:code:%s", Structure.STRING, ValueFormat.PLAIN),
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
    /** 적용 중인 효과. field: itemId, multiplier, expiredAt, purchaseId (물약 구매 Lua가 쓴다) */
    USER_EFFECT("user:%s:effect", Structure.HASH, ValueFormat.JSON),

    // 아이템 (Item)
    /** 아이템 효과 정의. field: multiplier */
    ITEM_EFFECT("item:%s", Structure.HASH, ValueFormat.JSON),
    /**
     * 물약 구매 멱등 키. of(userId, idempotencyKey). 값 "1", 구매 Lua가 잔액 검사 통과 후 SET PX 5분, 보상 Lua가 DEL
     */
    PURCHASE_DEDUPE("purchase:%s:%s", Structure.STRING, ValueFormat.PLAIN),
    /**
     * 물약 구매 취소 기록. of(userId). field: 취소된 purchaseId → 그 구매가 꺼낸 이전 효과(JSON 또는 ""). 보상 Lua만 쓰고 읽는다. TTL 5분(쓸 때마다 다시 설정)
     */
    USER_EFFECT_VOIDED("user:%s:effect:voided", Structure.HASH, ValueFormat.JSON),

    // 순위 (ZSet)
    /** 학교 점수·순위. member: universityId, score: 학교 점수 */
    RANK_UNIV("rank:univ", Structure.ZSET, ValueFormat.PLAIN),
    /**
     * 숨김 학교(운영팀용 와플대학교)의 학교 점수. RANK_UNIV와 같은 형식 (SCRUM-236).
     * 숨김 학교 점수를 RANK_UNIV에 넣지 않아야 순위·지도·어제 순위에 나오지 않고, 다른 학교 순위도 밀리지 않는다
     */
    RANK_UNIV_HIDDEN("rank:hidden:univ", Structure.ZSET, ValueFormat.PLAIN),
    /** 학과 점수·순위. member: departmentId, score: 학과 점수 */
    RANK_DEPT("rank:univ:%s:dept", Structure.ZSET, ValueFormat.PLAIN),
    /** 개인 기여도·순위. member: userId, score: 기여도 */
    RANK_USER("rank:univ:%s:user", Structure.ZSET, ValueFormat.PLAIN),
    /** 공격 점수. field: userId → 누적 공격 점수 (HINCRBY 대상) */
    SCORE_USER_ATTACK("score:user:attack", Structure.HASH, ValueFormat.PLAIN),
    /** 회원 부정행위 횟수 순위. member: userId, score: 부정행위 횟수 */
    RANK_USER_ABUSE("rank:user:abuse:%s", Structure.ZSET, ValueFormat.PLAIN),
    /** 매일 00:00 KST의 학교 순위 복사본 (순위 변동 기준). RANK_UNIV와 같은 형식 */
    RANK_UNIV_SNAPSHOT("rank:snapshot:univ", Structure.ZSET, ValueFormat.PLAIN),
    /** 매일 00:00 KST의 학과 순위 복사본. RANK_DEPT와 같은 형식 */
    RANK_DEPT_SNAPSHOT("rank:snapshot:univ:%s:dept", Structure.ZSET, ValueFormat.PLAIN),
    /** 매일 00:00 KST의 개인 순위 복사본. RANK_USER와 같은 형식 */
    RANK_USER_SNAPSHOT("rank:snapshot:univ:%s:user", Structure.ZSET, ValueFormat.PLAIN),
    /** 마지막으로 순위 복사본을 다 만든 날짜(KST, yyyy-MM-dd). 오늘 날짜일 때만 변동을 계산한다 */
    RANK_SNAPSHOT_DATE("rank:snapshot:date", Structure.STRING, ValueFormat.PLAIN),

    // 변경 추적 (Batch)
    DIRTY_UNIV("dirty:univ", Structure.SET, ValueFormat.PLAIN),
    DIRTY_DEPT("dirty:dept", Structure.SET, ValueFormat.PLAIN),
    DIRTY_USER("dirty:user", Structure.SET, ValueFormat.PLAIN),
    DIRTY_CLICKER("dirty:clicker", Structure.SET, ValueFormat.PLAIN),
    DIRTY_ALLIANCE("dirty:alliance", Structure.SET, ValueFormat.PLAIN),
    DIRTY_MATCH("dirty:match", Structure.SET, ValueFormat.PLAIN),

    // 클릭 (Click)
    /** 클릭 직전 1초 클릭 수. of(universityId, epochSec), INCR */
    CLICKS_UNIV_SECOND("clicks:univ:%s:%s", Structure.STRING, ValueFormat.PLAIN),
    /** 공격 직전 1초 클릭 수. of(targetUniversityId, epochSec), INCR */
    ATTACKS_UNIV_SECOND("attacks:univ:%s:%s", Structure.STRING, ValueFormat.PLAIN),
    /** 지원 직전 1초 클릭 수. of(targetUniversityId, epochSec), INCR */
    ASSISTS_UNIV_SECOND("assists:univ:%s:%s", Structure.STRING, ValueFormat.PLAIN),
    /** 중복 제출 방지. of(userId, submissionId), SET NX + TTL */
    SUBMIT_DEDUPE("submit:%s:%s", Structure.STRING, ValueFormat.PLAIN),
    /** 클릭 크리티컬 인덱스. of(userId), SET TTL */
    CLICKS_CRITICAL("clicks:user:%s:critical", Structure.STRING, ValueFormat.PLAIN),
    /** 공격 크리티컬 인덱스. of(userId), SET TTL */
    ATTACKS_CRITICAL("attacks:user:%s:critical", Structure.STRING, ValueFormat.PLAIN),
    /** 지원 크리티컬 인덱스. of(userId), SET TTL */
    ASSISTS_CRITICAL("assists:user:%s:critical", Structure.STRING, ValueFormat.PLAIN),
    /** 클릭 데미지 비율 인덱스. of(userId), SET TTL */
    CLICKS_DAMAGE_RATE("clicks:user:%s:damage-rate", Structure.STRING, ValueFormat.PLAIN),
    /** 공격 데미지 비율 인덱스. of(userId), SET TTL */
    ATTACKS_DAMAGE_RATE("attacks:user:%s:damage-rate", Structure.STRING, ValueFormat.PLAIN),
    /** 지원 데미지 비율 인덱스. of(userId), SET TTL */
    ASSISTS_DAMAGE_RATE("assists:user:%s:damage-rate", Structure.STRING, ValueFormat.PLAIN),

    // 요청 한도 (RateLimit)
    /** 행동 요청 한도. INCR */
    RATELIMIT_ACTION("ratelimit:action:%s", Structure.STRING, ValueFormat.PLAIN),
    /** 클릭 수 한도. of(userId), INCRBY */
    RATELIMIT_CLICK("ratelimit:click:%s", Structure.STRING, ValueFormat.PLAIN),
    /** 방명록 한도. SET NX + TTL */
    RATELIMIT_GUESTBOOK("ratelimit:guestbook:%s", Structure.STRING, ValueFormat.PLAIN),
    /** 채팅 한도. SET NX + TTL */
    RATELIMIT_CHAT("ratelimit:chat:%s", Structure.STRING, ValueFormat.PLAIN),
    /** 회원 문의 등록 한도 ({@code SupportRateLimiter}). of(userId). INCR, 처음 만들 때 TTL {@code support.rate-limit.window} */
    RATELIMIT_SUPPORT_USER("ratelimit:support:user:%s", Structure.STRING, ValueFormat.PLAIN),
    /** 비회원 문의 등록 한도. of(ipHash, {@code EmailHasher.hashClientIp} 값). INCR, TTL은 회원과 같다 */
    RATELIMIT_SUPPORT_IP("ratelimit:support:ip:%s", Structure.STRING, ValueFormat.PLAIN),
    /** 클릭 봇 확인 API 한도 ({@code AbuseVerificationService}). of(userId). INCR, 처음 만들 때 TTL {@code abuse.verification.attempt-window} */
    RATELIMIT_ABUSE_VERIFY("ratelimit:abuse:verify:%s", Structure.STRING, ValueFormat.PLAIN),

    // 어뷰징 (Abuse)
    /**
     * 클릭 어뷰징 의심 표시 (SCRUM-118). of(userId). 값 "1", TTL {@code abuse.verification.flag-ttl}.
     * 클릭 한도를 넘으면 생기고, 봇 확인을 통과하면 지운다. 있는 동안 클릭은 403 {@code ABUSE_VERIFICATION_REQUIRED}
     */
    ABUSE_VERIFY_USER("abuse:verify:user:%s", Structure.STRING, ValueFormat.PLAIN),

    // 학교 (University)
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

    // 락
    /** 점수 1분 배치 락 */
    LOCK_SCORE_FLUSH("lock:score-flush", Structure.STRING, ValueFormat.PLAIN),
    /** 순위 복사본 만들기 락. 여러 서버가 같은 날 한 번만 만든다 */
    LOCK_RANK_SNAPSHOT("lock:rank-snapshot", Structure.STRING, ValueFormat.PLAIN),

    // 레디스 복구
    /** Redis가 준비되었다는 표시. 없으면 Redis 복구를 실행한다. */
    REDIS_READY("redis:ready", Structure.STRING, ValueFormat.PLAIN),
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

    /**
     * 학교 점수 키. 숨김 학교(운영팀용)는 {@link #RANK_UNIV_HIDDEN}, 나머지는 {@link #RANK_UNIV} (SCRUM-236, SCRUM-238).
     * 숨김 학교 점수는 어느 경로로 바뀌든 RANK_UNIV에 쓰지 않는다
     */
    public static String univRank(boolean hidden) {
        return (hidden ? RANK_UNIV_HIDDEN : RANK_UNIV).of();
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
