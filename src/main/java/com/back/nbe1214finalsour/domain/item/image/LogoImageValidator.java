package com.back.nbe1214finalsour.domain.item.image;

import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

// 로고 이미지 파일 검증: 비어 있지 않고, 5MB 이하이고, 실제 내용이 PNG·JPEG·WEBP인지
@Component
public class LogoImageValidator {

    static final long MAX_SIZE_BYTES = 5L * 1024 * 1024;

    // 통과하면 판별한 형식을 돌려준다 (저장 확장자·Content-Type에 사용)
    public ImageType validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.ITEM_LOGO_IMAGE_REQUIRED);
        }
        if (file.getSize() > MAX_SIZE_BYTES) {
            throw new BusinessException(ErrorCode.ITEM_LOGO_IMAGE_TOO_LARGE);
        }
        return ImageType.detect(readHeader(file))
                .orElseThrow(() -> new BusinessException(ErrorCode.ITEM_LOGO_IMAGE_INVALID_TYPE));
    }

    // 형식 판별에 필요한 앞부분만 읽는다 (파일 전체를 메모리에 올리지 않음)
    private byte[] readHeader(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            return in.readNBytes(ImageType.HEADER_SIZE);
        } catch (IOException e) {
            // 서버가 받은 업로드 파일을 못 읽는 건 클라이언트 잘못이 아니므로 500
            throw new UncheckedIOException("로고 이미지를 읽지 못함", e);
        }
    }
}