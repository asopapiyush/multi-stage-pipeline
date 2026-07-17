package com.pipeline.controller;

import com.pipeline.model.JobRequest;
import com.pipeline.service.JobService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

// addFilters = false bypasses the Spring Security filter chain: this test's purpose is
// controller/service wiring, not auth (auth itself is covered by JobPipelineIntegrationTest,
// which exercises a real login).
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
class JobControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JobService jobService;

    @BeforeEach
    void setUp() {
        // Clear job service state if needed
    }

    @Test
    void testCreateJobWithValidUrls() throws Exception {
        JobRequest request = new JobRequest(List.of(
            "https://example.com",
            "https://test.com"
        ));

        mockMvc.perform(post("/api/jobs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.jobId").exists())
                .andExpect(jsonPath("$.jobId").isString());
    }

    @Test
    void testCreateJobWithEmptyUrlList() throws Exception {
        JobRequest request = new JobRequest(List.of());

        mockMvc.perform(post("/api/jobs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testCreateJobWithTooManyUrls() throws Exception {
        List<String> manyUrls = new java.util.ArrayList<>();
        for (int i = 0; i < 101; i++) {
            manyUrls.add("https://example.com/" + i);
        }
        JobRequest request = new JobRequest(manyUrls);

        mockMvc.perform(post("/api/jobs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testCreateJobReturnsUUID() throws Exception {
        JobRequest request = new JobRequest(List.of("https://example.com"));

        String response = mockMvc.perform(post("/api/jobs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // Extract jobId
        String jobId = objectMapper.readTree(response).get("jobId").asText();
        assertTrue(jobId.length() > 0);

        // Job should be queryable
        mockMvc.perform(get("/api/jobs/" + jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(jobId));
    }

    @Test
    void testGetNonExistentJob() throws Exception {
        mockMvc.perform(get("/api/jobs/non-existent-id"))
                .andExpect(status().isNotFound());
    }

    @Test
    void testInputValidation() throws Exception {
        // Test various invalid inputs
        List<String> invalidUrls = List.of("not-a-url", "ftp://invalid.com", "");

        for (String invalidUrl : invalidUrls) {
            JobRequest request = new JobRequest(List.of(invalidUrl));

            mockMvc.perform(post("/api/jobs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated());  // Job created, but URL marked as invalid
        }
    }
}
