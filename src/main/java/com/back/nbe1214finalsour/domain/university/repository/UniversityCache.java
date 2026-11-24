package com.back.nbe1214finalsour.domain.university.repository;

import com.back.nbe1214finalsour.domain.university.dto.LibraryLocation;
import com.back.nbe1214finalsour.domain.university.repository.projections.UnivCacheRow;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class UniversityCache {

    private final UniversityRepository universityRepository;
    private volatile Map<Long, Entry> universities = Map.of();

    @PostConstruct
    public void load() {
        universities = universityRepository.findAllForCache().stream()
                .collect(Collectors.toUnmodifiableMap(UnivCacheRow::id, Entry::from));
    }

    public String getName(Long universityId) {
        return find(universityId).name();
    }

    public Optional<LibraryLocation> getLibrary(Long universityId) {
        return Optional.ofNullable(find(universityId).library());
    }

    /**
     * 운영용 숨김 학교인지 (SCRUM-236). 숨김 학교는 마이그레이션으로만 들어가 서버가 뜰 때 캐시에 있으므로,
     * 캐시에 없는 학교는 숨김이 아니다. 학교가 있는지 확인하려면 {@link #getName(Long)}을 쓴다.
     */
    public boolean isHidden(Long universityId) {
        Entry entry = universities.get(universityId);
        return entry != null && entry.hidden();
    }

    private Entry find(Long universityId) {
        Entry entry = universities.get(universityId);
        if (entry == null) throw new BusinessException(ErrorCode.UNIV_NOT_FOUND);
        return entry;
    }

    private record Entry(String name, LibraryLocation library, boolean hidden) {
        static Entry from(UnivCacheRow row) {
            LibraryLocation library = (row.libraryLat() == null || row.libraryLng() == null)
                    ? null : new LibraryLocation(row.libraryLat().doubleValue(), row.libraryLng().doubleValue());
            return new Entry(row.name(), library, row.hidden());
        }
    }
}
