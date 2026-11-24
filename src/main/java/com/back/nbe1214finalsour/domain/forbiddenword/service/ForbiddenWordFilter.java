package com.back.nbe1214finalsour.domain.forbiddenword.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * 금칙어 목록과 가리기 (계획 D12, D19, D20). 채팅 밖의 공통 모듈이라 방명록도 같은 목록과 규칙을 쓴다.
 *
 * <p>목록은 리소스 파일 {@value #RESOURCE}에서 앱이 뜰 때 한 번 읽는다. 대소문자를 가리지 않고 문장 안에 들어 있으면(부분 일치)
 * 그 부분을 {@value #MASK} 하나로 바꾼다. 겹치는 금칙어는 긴 것을 먼저 가린다. 띄어쓰기·특수문자를 섞은 우회는 막지 않는다
 * (고도화는 이후 단계, 계획 §7).
 */
@Component
public class ForbiddenWordFilter {

    static final String RESOURCE = "forbidden-words.txt";
    static final String MASK = "**";

    private final List<String> words;
    private final @Nullable Pattern pattern;

    public ForbiddenWordFilter() {
        this(new ClassPathResource(RESOURCE));
    }

    ForbiddenWordFilter(Resource resource) {
        this.words = read(resource);
        this.pattern = compile(this.words);
    }

    /** 파일에 적힌 순서의 금칙어 목록 (중복 제거) */
    public List<String> words() {
        return this.words;
    }

    public String mask(String text) {
        return mask(text, MASK);
    }

    /**
     * 대체 문자를 지정한다. 기존 채팅은 **, 방명록은 명세에 따라 ***를 사용한다.
     */
    public String mask(String text, String replacement) {
        if (this.pattern == null) {
            return text;
        }
        return this.pattern.matcher(text).replaceAll(Matcher.quoteReplacement(replacement));
    }

    private static List<String> read(Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .distinct()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("금칙어 목록을 읽지 못함: " + resource, e);
        }
    }

    private static @Nullable Pattern compile(List<String> words) {
        if (words.isEmpty()) {
            return null;
        }
        String alternation = words.stream()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .map(Pattern::quote)
                .collect(Collectors.joining("|"));
        return Pattern.compile(alternation, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }
}
