package com.jobrecommender.backend.controller;

import com.jobrecommender.backend.service.DataQualityAuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/data-quality")
@RequiredArgsConstructor
public class AdminDataQualityController {

    private final DataQualityAuditService dataQualityAuditService;

    @GetMapping
    public Map<String, Object> getDataQualitySnapshot() {
        return dataQualityAuditService.getDataQualitySnapshot();
    }
}
