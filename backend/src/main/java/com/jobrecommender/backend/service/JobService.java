package com.jobrecommender.backend.service;

import com.jobrecommender.backend.config.CacheNames;
import com.jobrecommender.backend.dto.JobListItemDTO;
import com.jobrecommender.backend.entity.Job;
import com.jobrecommender.backend.entity.Company;
import com.jobrecommender.backend.entity.Location;
import com.jobrecommender.backend.entity.Skill;
import com.jobrecommender.backend.exception.ResourceNotFoundException;
import com.jobrecommender.backend.repository.CompanyRepository;
import com.jobrecommender.backend.repository.JobRepository;
import com.jobrecommender.backend.repository.LocationRepository;
import com.jobrecommender.backend.repository.SkillRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import com.jobrecommender.backend.util.FullTextQueryNormalizer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class JobService {

    private final JobRepository jobRepository;
    private final SkillRepository skillRepository;
    private final CompanyRepository companyRepository;
    private final LocationRepository locationRepository;
    private final SkillResolutionService skillResolutionService;
        private final Neo4jClient neo4jClient;

        private static final String JOB_FILTERED_PAGE_QUERY =
            "MATCH (j:Job) " +
            "WHERE ($titleFt = '' OR EXISTS { " +
            "         CALL db.index.fulltext.queryNodes('job_title_level_ft', $titleFt) YIELD node " +
            "         WITH node WHERE node = j RETURN 1 " +
            "      }) " +
            "  AND ($levelFt = '' OR EXISTS { " +
            "         CALL db.index.fulltext.queryNodes('job_title_level_ft', $levelFt) YIELD node " +
            "         WITH node WHERE node = j RETURN 1 " +
            "      }) " +
            "  AND ($skillFt = '' OR EXISTS { " +
            "         CALL db.index.fulltext.queryNodes('skill_name_ft', $skillFt) YIELD node " +
            "         WITH node MATCH (j)-[:REQUIRES]->(node) RETURN 1 " +
            "      }) " +
            "WITH j ORDER BY coalesce(j.job_title, j.title, ''), coalesce(j.job_link, j.jobLink, '') " +
            "SKIP $skip LIMIT $limit " +
            "OPTIONAL MATCH (j)-[:REQUIRES]->(s:Skill) " +
            "OPTIONAL MATCH (j)-[:LOCATED_IN]->(l:Location) " +
            "OPTIONAL MATCH (c:Company)-[:POSTED]->(j) " +
            "RETURN coalesce(j.job_link, j.jobLink, '') AS jobLink, " +
            "       coalesce(j.job_title, j.title, j.name, '') AS title, " +
            "       coalesce(j.type, j.job_type, '') AS type, " +
            "       coalesce(j.level, j.job_level, '') AS level, " +
            "       coalesce(l.name, '') AS locationName, " +
            "       coalesce(c.name, '') AS companyName, " +
            "       collect(DISTINCT coalesce(s.name, '')) AS skillNames";

        private static final String JOB_FILTERED_COUNT_QUERY =
            "MATCH (j:Job) " +
            "WHERE ($titleFt = '' OR EXISTS { " +
            "         CALL db.index.fulltext.queryNodes('job_title_level_ft', $titleFt) YIELD node " +
            "         WITH node WHERE node = j RETURN 1 " +
            "      }) " +
            "  AND ($levelFt = '' OR EXISTS { " +
            "         CALL db.index.fulltext.queryNodes('job_title_level_ft', $levelFt) YIELD node " +
            "         WITH node WHERE node = j RETURN 1 " +
            "      }) " +
            "  AND ($skillFt = '' OR EXISTS { " +
            "         CALL db.index.fulltext.queryNodes('skill_name_ft', $skillFt) YIELD node " +
            "         WITH node MATCH (j)-[:REQUIRES]->(node) RETURN 1 " +
            "      }) " +
            "RETURN count(j) AS total";

    public Page<Job> findAll(Pageable pageable) {
        return jobRepository.findAll(pageable);
    }

    public Page<Job> findAllFiltered(String title, String level, String skill, Pageable pageable) {
        boolean hasFilter = StringUtils.hasText(title) || StringUtils.hasText(level) || StringUtils.hasText(skill);
        if (!hasFilter) {
            return jobRepository.findAll(pageable);
        }

        // Fallback to repository query if full-text indexes are unavailable in Neo4j.
        try {
            return findAllFilteredWithFullText(title, level, skill, pageable);
        } catch (RuntimeException ex) {
            return jobRepository.searchJobs(title, level, skill, pageable);
        }
    }

    private Page<Job> findAllFilteredWithFullText(String title, String level, String skill, Pageable pageable) {
        String titleFt = FullTextQueryNormalizer.normalize(title);
        String levelFt = FullTextQueryNormalizer.normalize(level);
        String skillFt = FullTextQueryNormalizer.normalize(skill);

        long total = neo4jClient.query(JOB_FILTERED_COUNT_QUERY)
                .bind(titleFt).to("titleFt")
                .bind(levelFt).to("levelFt")
                .bind(skillFt).to("skillFt")
                .fetchAs(Long.class)
                .one()
                .orElse(0L);

        if (total == 0L) {
            return Page.empty(pageable);
        }

        List<Job> content = neo4jClient.query(JOB_FILTERED_PAGE_QUERY)
                .bind(titleFt).to("titleFt")
                .bind(levelFt).to("levelFt")
                .bind(skillFt).to("skillFt")
                .bind(pageable.getOffset()).to("skip")
                .bind(pageable.getPageSize()).to("limit")
                .fetch()
                .all()
                .stream()
                .map(this::mapJobRow)
                .toList();

        return new PageImpl<>(content, pageable, total);
    }

    public Page<JobListItemDTO> findAllSummariesFiltered(String title, String level, String skill, Pageable pageable) {
        Page<Job> page = findAllFiltered(title, level, skill, pageable);
        List<JobListItemDTO> content = page.getContent().stream().map(job -> new JobListItemDTO(
                job.getJobLink(),
                job.getTitle(),
                job.getType(),
                job.getLevel(),
                job.getCompany() == null ? "" : job.getCompany().getName(),
                job.getLocation() == null ? "" : job.getLocation().getName(),
                job.getSkills() == null ? List.of() : job.getSkills().stream().map(Skill::getName).filter(StringUtils::hasText).toList()
        )).toList();

        return new PageImpl<>(content, pageable, page.getTotalElements());
    }

    public Job findById(String id) {
        return jobRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Job not found with id: " + id));
    }

    public Job findByJobLink(String jobLink) {
        if (!StringUtils.hasText(jobLink)) {
            throw new IllegalArgumentException("jobLink is required");
        }
        return jobRepository.findByJobLink(jobLink.trim())
                .orElseThrow(() -> new ResourceNotFoundException("Job not found with jobLink: " + jobLink));
    }

    @Caching(evict = {
            @CacheEvict(cacheNames = CacheNames.LEXICAL_RECOMMENDATIONS, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.SEMANTIC_RECOMMENDATIONS, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.SKILL_CENTRALITY, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.SKILL_COMMUNITIES, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.CAREER_PATHS, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.DATA_FUNNEL, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.DATA_DRIFT, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.CANDIDATE_QUALITY, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.SKILL_GAP_ROADMAP, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.RECOMMENDATION_COMPARISON, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.RECOMMENDATION_COUNTERFACTUAL, allEntries = true)
    })
    public Job create(Job job) {
        if (job == null) {
            throw new IllegalArgumentException("Job payload is required");
        }

        if (job.getJobLink() == null || job.getJobLink().isEmpty()) {
            job.setJobLink(UUID.randomUUID().toString());
        }

        prepareJobRelationships(job);

        return jobRepository.save(job);
    }

    @Caching(evict = {
            @CacheEvict(cacheNames = CacheNames.LEXICAL_RECOMMENDATIONS, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.SEMANTIC_RECOMMENDATIONS, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.SKILL_CENTRALITY, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.SKILL_COMMUNITIES, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.CAREER_PATHS, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.DATA_FUNNEL, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.DATA_DRIFT, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.CANDIDATE_QUALITY, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.SKILL_GAP_ROADMAP, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.RECOMMENDATION_COMPARISON, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.RECOMMENDATION_COUNTERFACTUAL, allEntries = true)
    })
    public Job update(String id, Job payload) {
        if (!StringUtils.hasText(id)) {
            throw new IllegalArgumentException("Job id is required");
        }
        if (payload == null) {
            throw new IllegalArgumentException("Job payload is required");
        }

        Job existing = findById(id);

        existing.setTitle(payload.getTitle());
        existing.setType(payload.getType());
        existing.setLevel(payload.getLevel());
        if (StringUtils.hasText(payload.getJobLink())) {
            existing.setJobLink(payload.getJobLink().trim());
        }

        existing.setCompany(payload.getCompany());
        existing.setLocation(payload.getLocation());
        existing.setSkills(payload.getSkills() == null ? List.of() : payload.getSkills());

        prepareJobRelationships(existing);

        return jobRepository.save(existing);
    }

    @Caching(evict = {
            @CacheEvict(cacheNames = CacheNames.LEXICAL_RECOMMENDATIONS, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.SEMANTIC_RECOMMENDATIONS, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.SKILL_CENTRALITY, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.SKILL_COMMUNITIES, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.CAREER_PATHS, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.DATA_FUNNEL, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.DATA_DRIFT, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.CANDIDATE_QUALITY, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.SKILL_GAP_ROADMAP, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.RECOMMENDATION_COMPARISON, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.RECOMMENDATION_COUNTERFACTUAL, allEntries = true)
    })
    public Job updateByJobLink(String jobLink, Job payload) {
        Job existing = findByJobLink(jobLink);
        return update(existing.getJobLink(), payload);
    }

    private void prepareJobRelationships(Job job) {
        if (job.getCompany() != null && StringUtils.hasText(job.getCompany().getName())) {
            job.setCompany(companyRepository.mergeByName(job.getCompany().getName().trim()));
        } else {
            job.setCompany(null);
        }

        if (job.getLocation() != null && StringUtils.hasText(job.getLocation().getName())) {
            job.setLocation(locationRepository.mergeByName(job.getLocation().getName().trim()));
        } else {
            job.setLocation(null);
        }

        List<Skill> resolvedSkills = new ArrayList<>();
        LinkedHashSet<String> seenSkillNames = new LinkedHashSet<>();
        if (job.getSkills() != null) {
            for (Skill incomingSkill : job.getSkills()) {
                if (incomingSkill == null || !StringUtils.hasText(incomingSkill.getName())) {
                    continue;
                }

                SkillResolutionService.ResolvedSkill resolved = skillResolutionService.resolve(incomingSkill.getName());
                if (!StringUtils.hasText(resolved.canonical())) {
                    continue;
                }

                String key = resolved.canonical();
                if (seenSkillNames.contains(key)) {
                    continue;
                }

                Skill merged = skillRepository.mergeByNameWithEmbedding(resolved.canonical(), resolved.embedding());
                resolvedSkills.add(merged);
                seenSkillNames.add(key);
            }
        }
        job.setSkills(resolvedSkills);
    }

    @Caching(evict = {
            @CacheEvict(cacheNames = CacheNames.LEXICAL_RECOMMENDATIONS, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.SEMANTIC_RECOMMENDATIONS, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.SKILL_CENTRALITY, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.SKILL_COMMUNITIES, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.CAREER_PATHS, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.DATA_FUNNEL, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.DATA_DRIFT, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.CANDIDATE_QUALITY, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.SKILL_GAP_ROADMAP, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.RECOMMENDATION_COMPARISON, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.RECOMMENDATION_COUNTERFACTUAL, allEntries = true)
    })
    public void delete(String id) {
        jobRepository.deleteById(id);
    }

    @SuppressWarnings("unchecked")
    private Job mapJobRow(Map<String, Object> row) {
        Job job = new Job();
        job.setJobLink(asString(row.get("jobLink")));
        job.setTitle(asString(row.get("title")));
        job.setType(asString(row.get("type")));
        job.setLevel(asString(row.get("level")));

        String locationName = asString(row.get("locationName"));
        if (StringUtils.hasText(locationName)) {
            Location location = new Location();
            location.setName(locationName);
            job.setLocation(location);
        }

        String companyName = asString(row.get("companyName"));
        if (StringUtils.hasText(companyName)) {
            Company company = new Company();
            company.setName(companyName);
            job.setCompany(company);
        }

        List<Skill> skills = new ArrayList<>();
        Object raw = row.get("skillNames");
        if (raw instanceof List<?> list) {
            for (Object value : list) {
                if (value == null) {
                    continue;
                }
                String name = value.toString().trim();
                if (!name.isEmpty()) {
                    Skill s = new Skill();
                    s.setName(name);
                    skills.add(s);
                }
            }
        }
        job.setSkills(skills);

        return job;
    }

    private String asString(Object value) {
        return value == null ? "" : value.toString();
    }
}