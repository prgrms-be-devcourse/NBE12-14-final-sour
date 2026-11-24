package com.back.nbe1214finalsour.domain.university.controller;

import com.back.nbe1214finalsour.domain.university.dto.UniversityMapResponse;
import com.back.nbe1214finalsour.domain.university.service.UniversityMapService;
import com.back.nbe1214finalsour.global.response.ApiResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/map")
public class UniversityMapController {

    private final UniversityMapService universityMapService;

    @GetMapping("/universities")
    public ApiResponse<List<UniversityMapResponse>> getUniversities(
            @RequestParam(defaultValue = "50")
            @Min(1) @Max(300) int limit
    ) {
        return ApiResponse.ok(
                universityMapService.getUniversities(limit)
        );
    }
}
