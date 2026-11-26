package com.back.nbe1214finalsour.domain.university.controller;

import com.back.nbe1214finalsour.domain.university.controller.docs.UniversityMapApiDocs;
import com.back.nbe1214finalsour.domain.university.dto.UniversityMapResponse;
import com.back.nbe1214finalsour.domain.university.service.UniversityMapService;
import com.back.nbe1214finalsour.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/map")
public class UniversityMapController implements UniversityMapApiDocs {

    private final UniversityMapService universityMapService;

    @Override
    @GetMapping("/universities")
    public ApiResponse<List<UniversityMapResponse>> getUniversities(
            @RequestParam(defaultValue = "50")
            int limit
    ) {
        return ApiResponse.ok(
                universityMapService.getUniversities(limit)
        );
    }
}
