package ru.koren.demo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "koren.inference-url=http://127.0.0.1:1")
@AutoConfigureMockMvc
@ActiveProfiles("local")
class DemoApiTest {
    @Autowired MockMvc mvc;

    @Test void syntheticCaseLoads() throws Exception {
        mvc.perform(get("/api/v1/demo/case")).andExpect(status().isOk())
                .andExpect(jsonPath("$.synthetic").value(true))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.progress.allConfirmed").value(false));
    }
    @Test void unavailableMlKeepsManualPath() throws Exception {
        mvc.perform(get("/api/v1/demo/compare")).andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("MANUAL"))
                .andExpect(jsonPath("$.suggestions.length()").value(2));
    }
    @Test void applicationRoutesAreClosed() throws Exception {
        mvc.perform(get("/api/v1/cases")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/cases").contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
    }
}
