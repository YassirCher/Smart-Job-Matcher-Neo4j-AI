package com.jobrecommender.backend.controller;

import com.jobrecommender.backend.dto.CandidateListItemDTO;
import com.jobrecommender.backend.service.CandidateService;
import com.jobrecommender.backend.service.DynamicInterviewGeneratorService;
import com.jobrecommender.backend.service.GitHubProfileAnalyzerService;
import com.jobrecommender.backend.service.ResumePortfolioIntelligenceService;
import com.jobrecommender.backend.service.SoftSkillBehavioralProfileService;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CandidateControllerTest {

    @Test
    void getCandidateSummaries_shouldReturnLightweightPayload() throws Exception {
        CandidateService candidateService = mock(CandidateService.class);
        GitHubProfileAnalyzerService gitHubProfileAnalyzerService = mock(GitHubProfileAnalyzerService.class);
        ResumePortfolioIntelligenceService resumePortfolioIntelligenceService = mock(ResumePortfolioIntelligenceService.class);
        DynamicInterviewGeneratorService dynamicInterviewGeneratorService = mock(DynamicInterviewGeneratorService.class);
        SoftSkillBehavioralProfileService softSkillBehavioralProfileService = mock(SoftSkillBehavioralProfileService.class);

        CandidateController controller = new CandidateController(
                candidateService,
                gitHubProfileAnalyzerService,
                resumePortfolioIntelligenceService,
                dynamicInterviewGeneratorService,
                softSkillBehavioralProfileService
        );

        Page<CandidateListItemDTO> page = new PageImpl<>(
                List.of(new CandidateListItemDTO(
                        "cand-1",
                        "Yassir",
                        "yassir@example.com",
                        "/uploads/yassir.pdf",
                        12L,
                        List.of("python", "neo4j")
                )),
                PageRequest.of(0, 50),
                1
        );

        when(candidateService.findAllSummaries(any())).thenReturn(page);

        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();

        mvc.perform(get("/api/candidates/list?page=0&size=50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value("cand-1"))
                .andExpect(jsonPath("$.content[0].name").value("Yassir"))
                .andExpect(jsonPath("$.content[0].email").value("yassir@example.com"))
                .andExpect(jsonPath("$.content[0].resumePath").value("/uploads/yassir.pdf"))
                .andExpect(jsonPath("$.content[0].skillCount").value(12))
                .andExpect(jsonPath("$.content[0].topSkills[0]").value("python"));
    }
}
