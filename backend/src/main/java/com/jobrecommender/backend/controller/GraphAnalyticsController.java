package com.jobrecommender.backend.controller;

import com.jobrecommender.backend.dto.CareerPathDTO;
import com.jobrecommender.backend.dto.SkillCentralityDTO;
import com.jobrecommender.backend.dto.SkillCommunityDTO;
import com.jobrecommender.backend.service.GraphAnalyticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/graph-analytics")
@RequiredArgsConstructor
public class GraphAnalyticsController {

    private final GraphAnalyticsService graphAnalyticsService;

    @GetMapping("/skills/centrality")
    public List<SkillCentralityDTO> getSkillCentrality(
            @RequestParam(defaultValue = "20") int limit
    ) {
        return graphAnalyticsService.getTopSkillCentrality(limit);
    }

    @GetMapping("/skills/communities")
    public List<SkillCommunityDTO> getSkillCommunities(
            @RequestParam(defaultValue = "12") int limit,
            @RequestParam(defaultValue = "5") int minCooccurrence,
            @RequestParam(defaultValue = "4") int minDegree,
            @RequestParam(defaultValue = "6") int topNeighbors
    ) {
        return graphAnalyticsService.getSkillCommunities(limit, minCooccurrence, minDegree, topNeighbors);
    }

    @GetMapping("/candidates/{candidateId}/career-paths")
    public List<CareerPathDTO> getCareerPaths(
            @PathVariable String candidateId,
            @RequestParam(defaultValue = "5") int topJobs
    ) {
        return graphAnalyticsService.getCareerPaths(candidateId, topJobs);
    }
}
