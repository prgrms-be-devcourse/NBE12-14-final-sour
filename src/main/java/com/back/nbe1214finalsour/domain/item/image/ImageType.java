package com.back.nbe1214finalsour.domain.item.image;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Optional;

// 업로드를 허용하는 이미지 형식. 확장자와 Content-Type은 클라이언트 값이 아니라 여기서 정한 값을 쓴다
@Getter
@RequiredArgsConstructor
public enum ImageType {
    PNG("png", "image/png"),
    JPEG("jpg", "image/jpeg"),
    WEBP("webp", "image/webp");

    // 형식 판별에 필요한 최대 바이트 수 (WEBP: RIFF 4 + 크기 4 + WEBP 4)
    public static final int HEADER_SIZE = 12;

    private final String extension;
    private final String contentType;

    // 파일 앞부분(매직 넘버)으로 실제 형식을 판별. 허용하지 않는 형식이면 empty
    public static Optional<ImageType> detect(byte[] header) {
        if (startsWith(header, 0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
            return Optional.of(PNG);
        }
        if (startsWith(header, 0, 0xFF, 0xD8, 0xFF)) {
            return Optional.of(JPEG);
        }
        if (startsWith(header, 0, 'R', 'I', 'F', 'F') && startsWith(header, 8, 'W', 'E', 'B', 'P')) {
            return Optional.of(WEBP);
        }
        return Optional.empty();
    }

    // header[offset]부터 expected와 같은지. 바이트가 모자라면 false
    private static boolean startsWith(byte[] header, int offset, int... expected) {
        if (header.length < offset + expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if ((header[offset + i] & 0xFF) != expected[i]) {
                return false;
            }
        }
        return true;
    }
}