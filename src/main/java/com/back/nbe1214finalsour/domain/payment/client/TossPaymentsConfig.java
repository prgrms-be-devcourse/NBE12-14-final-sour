package com.back.nbe1214finalsour.domain.payment.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

// 토스 승인 클라이언트 설정. 키가 없으면 ${TOSS_SECRET_KEY}를 풀지 못해 앱이 시작하지 않는다
// 타임아웃을 클라이언트 밖(여기)에서 정하는 이유: 테스트는 가짜 서버를 묶은 RestClient를 생성자로 넘기는데,
// 클라이언트 안에서 연결 설정을 바꾸면 그 가짜 서버 연결을 덮어쓴다
@Configuration(proxyBeanMethods = false)
public class TossPaymentsConfig {

    @Bean
    public TossPaymentsClient tossPaymentsClient(
            @Value("${toss.secret-key}") String secretKey,
            @Value("${toss.connect-timeout}") Duration connectTimeout,
            @Value("${toss.read-timeout}") Duration readTimeout
    ) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        RestClient restClient = RestClient.builder().requestFactory(requestFactory).build();
        return new TossPaymentsClient(restClient, secretKey);
    }
}