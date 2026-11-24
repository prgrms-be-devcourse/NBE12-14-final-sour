package com.back.nbe1214finalsour.global.time;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 현재 시각을 주입받도록 {@link Clock}을 빈으로 둔다. 테스트에서는 고정 시계로 바꿔 날짜 경계를 확인한다.
 *
 * <p>서비스 규칙의 "하루"는 한국 날짜다. 배포 컨테이너의 JVM 기본 시간대(UTC일 수 있음)에 기대지 말고
 * {@link #KST}로 날짜를 계산한다. 예: {@code LocalDate.ofInstant(clock.instant(), ClockConfig.KST)}
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
