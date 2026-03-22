package com.jobrecommender.backend.controller;

import com.jobrecommender.backend.entity.Skill;
import com.jobrecommender.backend.service.SkillService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/skills")
@RequiredArgsConstructor
public class SkillController {

    private final SkillService skillService;

    @GetMapping
    public Page<Skill> getAllSkills(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return skillService.findAll(PageRequest.of(page, size));
    }

    @GetMapping("/search")
    public Page<Skill> searchSkills(
            @RequestParam(defaultValue = "") String name,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return skillService.searchByName(name, PageRequest.of(page, size));
    }

    @GetMapping("/resolve")
    public java.util.Map<String, Object> resolveSkillName(@RequestParam(defaultValue = "") String name) {
        return skillService.resolveName(name);
    }

    @GetMapping("/semantic/search")
    public List<com.jobrecommender.backend.dto.SkillSimilarityResultDTO> semanticSearch(
            @RequestParam(defaultValue = "") String name,
            @RequestParam(defaultValue = "5") int topK) {
        return skillService.semanticNearest(name, topK);
    }

    @PostMapping
    public Skill createSkill(@RequestBody Skill skill) {
        return skillService.create(skill);
    }
}
