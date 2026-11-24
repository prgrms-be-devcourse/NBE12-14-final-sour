package com.back.nbe1214finalsour.domain.university.service.rival;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties("rivals")
public record RivalProperties(List<List<String>> groups) {
    public RivalProperties {
        groups = groups == null ? List.of() : groups;
    }
}
