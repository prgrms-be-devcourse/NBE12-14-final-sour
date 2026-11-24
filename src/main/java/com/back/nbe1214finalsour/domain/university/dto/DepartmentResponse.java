package com.back.nbe1214finalsour.domain.university.dto;

import com.back.nbe1214finalsour.domain.university.entity.Department;

public record DepartmentResponse(
        Long id,
        String name
) {
    public DepartmentResponse(Department department){
        this(
                department.getId(),
                department.getName()
        );
    }
}
