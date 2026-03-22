package com.jobrecommender.backend.service;

import com.jobrecommender.backend.config.CacheNames;
import com.jobrecommender.backend.dto.CandidateListItemDTO;
import com.jobrecommender.backend.entity.Candidate;
import com.jobrecommender.backend.entity.Skill;
import com.jobrecommender.backend.exception.ResourceNotFoundException;
import com.jobrecommender.backend.repository.CandidateRepository;
import com.jobrecommender.backend.repository.SkillRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class CandidateService {

    private static final String FIND_CANDIDATE_BY_ID_QUERY =
            "MATCH (c:Candidate {id: $id}) " +
            "OPTIONAL MATCH (c)-[:HAS_SKILL]->(s:Skill) " +
            "RETURN c.id AS id, c.name AS name, c.email AS email, c.resumePath AS resumePath, " +
            "collect(CASE WHEN s IS NULL THEN NULL ELSE {id: s.id, name: s.name} END) AS skills";

        private static final String FIND_CANDIDATES_PAGE_QUERY =
            "MATCH (c:Candidate) " +
            "WITH c ORDER BY coalesce(c.name, ''), c.id " +
            "SKIP $skip LIMIT $limit " +
            "OPTIONAL MATCH (c)-[:HAS_SKILL]->(s:Skill) " +
            "RETURN c.id AS id, c.name AS name, c.email AS email, c.resumePath AS resumePath, " +
            "collect(CASE WHEN s IS NULL THEN NULL ELSE {id: s.id, name: s.name} END) AS skills";

        private static final String COUNT_CANDIDATES_QUERY =
            "MATCH (c:Candidate) RETURN count(c) AS total";

            private static final String FIND_CANDIDATE_SUMMARIES_PAGE_QUERY =
                "MATCH (c:Candidate) " +
                "WITH c ORDER BY coalesce(c.name, ''), c.id " +
                "SKIP $skip LIMIT $limit " +
                "OPTIONAL MATCH (c)-[:HAS_SKILL]->(s:Skill) " +
                "WITH c, collect(DISTINCT s.name)[..8] AS topSkills, count(DISTINCT s) AS skillCount " +
                "RETURN c.id AS id, c.name AS name, c.email AS email, c.resumePath AS resumePath, " +
                "       topSkills AS topSkills, skillCount AS skillCount";

    private final CandidateRepository candidateRepository;
    private final SkillRepository skillRepository;
    private final SkillResolutionService skillResolutionService;
    private final Neo4jClient neo4jClient;

    public Page<Candidate> findAll(Pageable pageable) {
        long total = neo4jClient.query(COUNT_CANDIDATES_QUERY)
                .fetchAs(Long.class)
                .one()
                .orElse(0L);

        if (total == 0) {
            return Page.empty(pageable);
        }

        List<Candidate> content = neo4jClient.query(FIND_CANDIDATES_PAGE_QUERY)
                .bind(pageable.getOffset()).to("skip")
                .bind(pageable.getPageSize()).to("limit")
                .fetch()
                .all()
                .stream()
                .map(this::mapCandidateRow)
                .toList();

        return new PageImpl<>(content, pageable, total);
    }

    public Candidate findById(String id) {
        return neo4jClient.query(FIND_CANDIDATE_BY_ID_QUERY)
            .bind(id).to("id")
            .fetch()
            .one()
            .map(this::mapCandidateRow)
            .orElseThrow(() -> new ResourceNotFoundException("Candidate not found with id: " + id));
    }

            public Page<CandidateListItemDTO> findAllSummaries(Pageable pageable) {
            long total = neo4jClient.query(COUNT_CANDIDATES_QUERY)
                .fetchAs(Long.class)
                .one()
                .orElse(0L);

            if (total == 0) {
                return Page.empty(pageable);
            }

            List<CandidateListItemDTO> content = neo4jClient.query(FIND_CANDIDATE_SUMMARIES_PAGE_QUERY)
                .bind(pageable.getOffset()).to("skip")
                .bind(pageable.getPageSize()).to("limit")
                .fetch()
                .all()
                .stream()
                .map(this::mapCandidateSummaryRow)
                .toList();

            return new PageImpl<>(content, pageable, total);
            }

    public void ensureExists(String id) {
        if (!candidateRepository.existsById(id)) {
            throw new ResourceNotFoundException("Candidate not found with id: " + id);
        }
    }

    @Caching(evict = {
            @CacheEvict(cacheNames = CacheNames.LEXICAL_RECOMMENDATIONS, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.SEMANTIC_RECOMMENDATIONS, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.CAREER_PATHS, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.CANDIDATE_QUALITY, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.SKILL_GAP_ROADMAP, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.RECOMMENDATION_COMPARISON, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.RECOMMENDATION_COUNTERFACTUAL, allEntries = true)
    })
    public Candidate create(Candidate candidate) {
        if (candidate.getId() == null || candidate.getId().isEmpty()) {
            candidate.setId(UUID.randomUUID().toString());
        }
        return candidateRepository.save(candidate);
    }

    @Caching(evict = {
            @CacheEvict(cacheNames = CacheNames.LEXICAL_RECOMMENDATIONS, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.SEMANTIC_RECOMMENDATIONS, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.CAREER_PATHS, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.CANDIDATE_QUALITY, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.SKILL_GAP_ROADMAP, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.RECOMMENDATION_COMPARISON, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.RECOMMENDATION_COUNTERFACTUAL, allEntries = true)
    })
    public Candidate addSkillToCandidate(String candidateId, String skillId) {
        if (!StringUtils.hasText(skillId)) {
            throw new ResourceNotFoundException("Skill not found with id: " + skillId);
        }

        ensureExists(candidateId);
        Skill skill = skillRepository.findById(skillId.trim())
                .orElseThrow(() -> new ResourceNotFoundException("Skill not found with id: " + skillId));
        candidateRepository.attachSkillIds(candidateId, List.of(skill.getId()));
        return findById(candidateId);
    }

    @Caching(evict = {
            @CacheEvict(cacheNames = CacheNames.LEXICAL_RECOMMENDATIONS, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.SEMANTIC_RECOMMENDATIONS, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.CAREER_PATHS, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.CANDIDATE_QUALITY, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.SKILL_GAP_ROADMAP, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.RECOMMENDATION_COMPARISON, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.RECOMMENDATION_COUNTERFACTUAL, allEntries = true)
    })
    public Candidate addSkillToCandidateByName(String candidateId, String skillName) {
        if (!StringUtils.hasText(skillName)) {
            throw new ResourceNotFoundException("Skill name is required");
        }

        ensureExists(candidateId);
        SkillResolutionService.ResolvedSkill resolved = skillResolutionService.resolve(skillName);
        if (!StringUtils.hasText(resolved.canonical())) {
            throw new ResourceNotFoundException("Skill name is required");
        }

        String skillId;
        try {
            skillId = skillRepository.mergeByNameWithEmbeddingId(resolved.canonical(), resolved.embedding());
        } catch (RuntimeException ex) {
            log.warn("[SKILL_ATTACH_FALLBACK] candidateId={} skillName='{}' canonical='{}' reason={} -> fallback mergeByName",
                    candidateId,
                    skillName,
                    resolved.canonical(),
                    ex.getMessage());
            skillId = skillRepository.mergeByNameId(resolved.canonical());
        }

        if (!StringUtils.hasText(skillId)) {
            throw new IllegalStateException("Unable to resolve skill id for name: " + resolved.canonical());
        }

        candidateRepository.attachSkillIds(candidateId, List.of(skillId));
        return findById(candidateId);
    }

    @Caching(evict = {
            @CacheEvict(cacheNames = CacheNames.LEXICAL_RECOMMENDATIONS, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.SEMANTIC_RECOMMENDATIONS, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.CAREER_PATHS, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.CANDIDATE_QUALITY, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.SKILL_GAP_ROADMAP, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.RECOMMENDATION_COMPARISON, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.RECOMMENDATION_COUNTERFACTUAL, allEntries = true)
    })
    public void delete(String id) {
        if (!candidateRepository.existsById(id)) {
            throw new ResourceNotFoundException("Candidate not found with id: " + id);
        }
        candidateRepository.deleteById(id);
    }

        @Caching(evict = {
            @CacheEvict(cacheNames = CacheNames.LEXICAL_RECOMMENDATIONS, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.SEMANTIC_RECOMMENDATIONS, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.CAREER_PATHS, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.CANDIDATE_QUALITY, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.SKILL_GAP_ROADMAP, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.RECOMMENDATION_COMPARISON, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.RECOMMENDATION_COUNTERFACTUAL, allEntries = true)
        })
        public void attachSkillIds(String candidateId, List<String> skillIds) {
        ensureExists(candidateId);
        if (skillIds == null || skillIds.isEmpty()) {
            return;
        }

        List<String> clean = skillIds.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
        if (clean.isEmpty()) {
            return;
        }

        candidateRepository.attachSkillIds(candidateId, clean);
    }

    @Caching(evict = {
            @CacheEvict(cacheNames = CacheNames.LEXICAL_RECOMMENDATIONS, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.SEMANTIC_RECOMMENDATIONS, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.CAREER_PATHS, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.CANDIDATE_QUALITY, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.SKILL_GAP_ROADMAP, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.RECOMMENDATION_COMPARISON, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.RECOMMENDATION_COUNTERFACTUAL, allEntries = true)
    })
    public Candidate attachSkillNames(String candidateId, List<String> skillNames) {
        ensureExists(candidateId);
        if (skillNames == null || skillNames.isEmpty()) {
            return findById(candidateId);
        }

        List<String> skillIds = new ArrayList<>();
        for (String rawName : skillNames) {
            if (!StringUtils.hasText(rawName)) {
                continue;
            }

            SkillResolutionService.ResolvedSkill resolved = skillResolutionService.resolve(rawName);
            if (!StringUtils.hasText(resolved.canonical())) {
                continue;
            }

            String mergedSkillId;
            try {
                mergedSkillId = skillRepository.mergeByNameWithEmbeddingId(resolved.canonical(), resolved.embedding());
            } catch (RuntimeException ex) {
                log.warn("[SKILL_ATTACH_BULK_FALLBACK] candidateId={} skillName='{}' canonical='{}' reason={} -> fallback mergeByName",
                        candidateId,
                        rawName,
                        resolved.canonical(),
                        ex.getMessage());
                mergedSkillId = skillRepository.mergeByNameId(resolved.canonical());
            }

            if (StringUtils.hasText(mergedSkillId)) {
                skillIds.add(mergedSkillId);
            }
        }

        attachSkillIds(candidateId, skillIds);
        return findById(candidateId);
    }

    @SuppressWarnings("unchecked")
    private CandidateListItemDTO mapCandidateSummaryRow(Map<String, Object> row) {
        List<String> topSkills = new ArrayList<>();
        Object rawTopSkills = row.get("topSkills");
        if (rawTopSkills instanceof List<?> list) {
            for (Object value : list) {
                if (value != null) {
                    String s = value.toString().trim();
                    if (!s.isEmpty()) {
                        topSkills.add(s);
                    }
                }
            }
        }

        long skillCount = 0L;
        Object rawSkillCount = row.get("skillCount");
        if (rawSkillCount instanceof Number number) {
            skillCount = number.longValue();
        }

        return new CandidateListItemDTO(
                stringValue(row.get("id")),
                stringValue(row.get("name")),
                stringValue(row.get("email")),
                stringValue(row.get("resumePath")),
                skillCount,
                topSkills
        );
    }

    @SuppressWarnings("unchecked")
    private Candidate mapCandidateRow(Map<String, Object> row) {
        Candidate candidate = new Candidate();
        candidate.setId(stringValue(row.get("id")));
        candidate.setName(stringValue(row.get("name")));
        candidate.setEmail(stringValue(row.get("email")));
        candidate.setResumePath(stringValue(row.get("resumePath")));

        List<Skill> skills = new ArrayList<>();
        Object rawSkills = row.get("skills");
        if (rawSkills instanceof List<?> skillRows) {
            for (Object item : skillRows) {
                if (!(item instanceof Map<?, ?> map)) {
                    continue;
                }
                String id = stringValue(map.get("id"));
                String name = stringValue(map.get("name"));
                if (!StringUtils.hasText(id) && !StringUtils.hasText(name)) {
                    continue;
                }
                Skill skill = new Skill();
                skill.setId(id);
                skill.setName(name);
                skills.add(skill);
            }
        }
        candidate.setSkills(skills);
        return candidate;
    }

    private String stringValue(Object value) {
        return value == null ? null : value.toString();
    }
}