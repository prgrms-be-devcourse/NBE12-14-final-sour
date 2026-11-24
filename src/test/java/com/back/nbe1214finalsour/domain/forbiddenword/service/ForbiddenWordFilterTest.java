package com.back.nbe1214finalsour.domain.forbiddenword.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

class ForbiddenWordFilterTest {

    private static ForbiddenWordFilter filter(String file) {
        return new ForbiddenWordFilter(new ByteArrayResource(file.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("빈 줄·주석은 건너뛰고 앞뒤 공백을 지우며, 중복은 한 번만 파일 순서대로 둔다")
    void readsWordList() {
        ForbiddenWordFilter filter = filter("# 주석\n\n  바보  \n멍청이\n바보\n");

        assertThat(filter.words()).containsExactly("바보", "멍청이");
    }

    @Test
    @DisplayName("문장 안의 금칙어를 단어 길이와 상관없이 ** 하나로 가린다")
    void masksEachOccurrence() {
        ForbiddenWordFilter filter = filter("바보\n멍청이\n");

        assertThat(filter.mask("너 바보야, 멍청이같이 바보바보")).isEqualTo("너 **야, **같이 ****");
    }

    @Test
    @DisplayName("대소문자를 가리지 않는다")
    void ignoresCase() {
        ForbiddenWordFilter filter = filter("badword\n");

        assertThat(filter.mask("BadWord badWORD")).isEqualTo("** **");
    }

    @Test
    @DisplayName("겹치는 금칙어는 긴 것을 먼저 가린다")
    void longerWordFirst() {
        ForbiddenWordFilter filter = filter("바보\n바보멍청이\n");

        assertThat(filter.mask("바보멍청이")).isEqualTo("**");
    }

    @Test
    @DisplayName("정규식 특수문자도 글자 그대로 찾는다")
    void treatsWordsLiterally() {
        ForbiddenWordFilter filter = filter("a.b\n");

        assertThat(filter.mask("a.b axb")).isEqualTo("** axb");
    }

    @Test
    @DisplayName("기존 채팅 **는 유지하고 방명록은 ***로 가린다")
    void customReplacementForGuestBook() {
        ForbiddenWordFilter filter = filter("바보\n");
        assertThat(filter.mask("바보")).isEqualTo("**");
        assertThat(filter.mask("바보", "***")).isEqualTo("***");
    }

    @Test
    @DisplayName("목록이 비면 본문을 그대로 둔다")
    void emptyList() {
        assertThat(filter("# 없음\n").mask("안녕")).isEqualTo("안녕");
    }

    @Test
    @DisplayName("배포 리소스 forbidden-words.txt를 읽는다")
    void loadsBundledResource() {
        assertThat(new ForbiddenWordFilter().words()).isNotEmpty().doesNotContain("").noneMatch(word -> word.startsWith("#"));
    }
}
