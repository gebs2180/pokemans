package com.pokemans.app.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Architecture review #46: one error body, the old statuses and words, and no bare 500 for a lost race. */
class ApiErrorHandlerTest {

    private enum Why { NO_BUYER_ADDRESS }

    @RestController
    static class Throwing {

        @GetMapping("/race")
        String race() {
            throw new DataIntegrityViolationException("duplicate key value violates unique constraint (secret row values)");
        }

        @GetMapping("/refused")
        String refused() {
            throw new ApiException(HttpStatus.CONFLICT, Why.NO_BUYER_ADDRESS, "Add the address cards should ship to first.");
        }

        @GetMapping("/plain")
        String plain() {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This card is already listed.");
        }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new Throwing()).setControllerAdvice(new ApiErrorHandler()).build();

    @Test
    void aConstraintThatSaysNoIsAConflictNotA500() throws Exception {
        mvc.perform(get("/race"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").value(ApiErrorHandler.CONFLICT))
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void aCodedRefusalKeepsItsWordsAndAddsItsCode() throws Exception {
        mvc.perform(get("/refused"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Add the address cards should ship to first."))
                .andExpect(jsonPath("$.code").value("NO_BUYER_ADDRESS"));
    }

    @Test
    void anUncodedRefusalIsUnchanged() throws Exception {
        mvc.perform(get("/plain"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("This card is already listed."))
                .andExpect(jsonPath("$.code").doesNotExist());
    }
}
