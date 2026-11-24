package com.back.nbe1214finalsour.domain.item.image;

import org.springframework.web.multipart.MultipartFile;

// 로고 이미지 저장소. 구매 로직은 S3를 직접 모르고 이 인터페이스만 쓴다 (단위 테스트에서 Mock으로 대체)
public interface ImageStorage {

    // key 경로에 이미지를 저장하고, 브라우저가 열 수 있는 공개 주소를 돌려준다
    String upload(String key, MultipartFile file, ImageType type);

    // key 경로의 이미지를 지운다 (업로드 후 구매가 실패했을 때 되돌리기용)
    void delete(String key);
}