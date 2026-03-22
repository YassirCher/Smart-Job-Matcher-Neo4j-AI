package com.jobrecommender.backend.controller;

import com.jobrecommender.backend.repository.JobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/stats")
@RequiredArgsConstructor
public class StatsController {

    private final JobRepository jobRepository;

    @GetMapping
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalJobs", jobRepository.countJobs());
        stats.put("totalSkills", jobRepository.countSkills());
        stats.put("totalCandidates", jobRepository.countCandidates());
        stats.put("jobsByLevel", jobRepository.countJobsByLevel());
        stats.put("jobsByType", jobRepository.countJobsByType());
        stats.put("top10Skills", jobRepository.getTop10Skills());
        return stats;
    }
}
