package com.jobrecommender.backend.controller;

import com.jobrecommender.backend.dto.SmartJobParseRequest;
import com.jobrecommender.backend.dto.SmartJobParseResponse;
import com.jobrecommender.backend.dto.JobListItemDTO;
import com.jobrecommender.backend.entity.Job;
import com.jobrecommender.backend.service.JobService;
import com.jobrecommender.backend.service.SmartJobCreatorService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/jobs")
@RequiredArgsConstructor
public class JobController {

    private final JobService jobService;
    private final SmartJobCreatorService smartJobCreatorService;

    @GetMapping
    public Page<Job> getAllJobs(
            @RequestParam(required = false) String title,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String skill,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return jobService.findAllFiltered(title, level, skill, PageRequest.of(page, size));
    }

    @GetMapping("/list")
    public Page<JobListItemDTO> getJobSummaries(
            @RequestParam(required = false) String title,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String skill,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return jobService.findAllSummariesFiltered(title, level, skill, PageRequest.of(page, size));
    }

    @GetMapping("/{id}")
    public Job getJobById(@PathVariable String id) {
        return jobService.findById(id);
    }

    @GetMapping("/by-link")
    public Job getJobByLink(@RequestParam String jobLink) {
        return jobService.findByJobLink(jobLink);
    }

    @PostMapping
    public Job createJob(@RequestBody Job job) {
        return jobService.create(job);
    }

    @PutMapping("/{id}")
    public Job updateJob(@PathVariable String id, @RequestBody Job job) {
        return jobService.update(id, job);
    }

    @PutMapping("/by-link")
    public Job updateJobByLink(@RequestParam String jobLink, @RequestBody Job job) {
        return jobService.updateByJobLink(jobLink, job);
    }

    @PostMapping("/smart-parse")
    public SmartJobParseResponse smartParseJob(@RequestBody SmartJobParseRequest request) {
        String rawText = request == null ? null : request.rawText();
        return smartJobCreatorService.parseRawDescription(rawText);
    }

    @DeleteMapping("/{id}")
    public void deleteJob(@PathVariable String id) {
        jobService.delete(id);
    }
}
