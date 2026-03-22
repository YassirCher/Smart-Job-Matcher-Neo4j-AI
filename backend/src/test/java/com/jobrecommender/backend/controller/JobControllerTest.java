package com.jobrecommender.backend.controller;

import com.jobrecommender.backend.dto.JobListItemDTO;
import com.jobrecommender.backend.service.JobService;
import com.jobrecommender.backend.service.SmartJobCreatorService;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JobControllerTest {

    @Test
    void getJobSummaries_shouldReturnLightweightPayload() throws Exception {
        JobService jobService = mock(JobService.class);
        SmartJobCreatorService smartJobCreatorService = mock(SmartJobCreatorService.class);

        JobController controller = new JobController(jobService, smartJobCreatorService);

        Page<JobListItemDTO> page = new PageImpl<>(
                List.of(new JobListItemDTO(
                        "job-1",
                        "Senior Data Engineer",
                        "Full-time",
                        "Senior",
                        "ACME",
                        "Paris",
                        List.of("python", "neo4j")
                )),
                PageRequest.of(0, 50),
                1
        );

        when(jobService.findAllSummariesFiltered(nullable(String.class), nullable(String.class), nullable(String.class), any())).thenReturn(page);

        Page<JobListItemDTO> actual = controller.getJobSummaries("data", null, null, 0, 50);

        assertEquals(1, actual.getTotalElements());
        assertEquals("job-1", actual.getContent().get(0).jobLink());
        assertEquals("Senior Data Engineer", actual.getContent().get(0).title());
        assertEquals("Full-time", actual.getContent().get(0).type());
        assertEquals("Senior", actual.getContent().get(0).level());
        assertEquals("ACME", actual.getContent().get(0).companyName());
        assertEquals("Paris", actual.getContent().get(0).locationName());
        assertEquals("python", actual.getContent().get(0).skills().get(0));
    }
}
