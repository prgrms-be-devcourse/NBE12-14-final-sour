package com.back.nbe1214finalsour.domain.university.service.rival;

import com.back.nbe1214finalsour.domain.university.repository.UniversityRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(RivalProperties.class)
public class RivalChecker {

    private final RivalProperties rivalProperties;
    private final UniversityRepository universityRepository;
    private volatile Map<Long, Set<Long>> rivals = Map.of();

    @PostConstruct
    public void load() {
        Map<Long, Set<Long>> map = new HashMap<>();
        for (List<String> group : rivalProperties.groups()) {
            if (group.size() < 2) {
                throw new IllegalStateException("rivals.groups에는 학교가 2개 이상 있어야 합니다: " + group);
            }
            List<Long> ids = group.stream().map(this::idOf).toList();
            for (Long a : ids) {
                for (Long b : ids) {
                    if (!a.equals(b)) {
                        map.computeIfAbsent(a, k -> new HashSet<>()).add(b);
                    }
                }
            }
        }
        rivals = Map.copyOf(map);
    }

    public boolean isRival(Long universityId, Long targetUniversityId) {
        return rivals.getOrDefault(universityId, Set.of()).contains(targetUniversityId);
    }

    private Long idOf(String name) {
        return universityRepository.findByName(name)
                .orElseThrow(() -> new IllegalStateException("rivals.groups에 있는 학교가 DB에 없습니다: " + name))
                .getId();
    }
}
