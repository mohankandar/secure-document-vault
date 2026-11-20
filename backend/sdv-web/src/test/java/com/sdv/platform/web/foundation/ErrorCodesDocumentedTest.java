package com.sdv.platform.web.foundation;

import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.error.SdvErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Task 0024, 4a: every code has the SDV-AREA-NNN form, is unique, is listed in docs/api/error-codes.md and is sent. */
class ErrorCodesDocumentedTest {
    private static final Pattern FORMAT = Pattern.compile("SDV-[A-Z]{3}-\\d{3}");

    @Test
    void everyCodeHasTheFormatAndIsUnique() {
        Set<String> seen = new HashSet<>();
        for (SdvErrorCode code : SdvErrorCode.values()) {
            assertTrue(FORMAT.matcher(code.code()).matches(), code.code());
            assertTrue(seen.add(code.code()), "duplicate " + code.code());
            assertEquals(code.name(), code.code().substring(4).replace('-', '_'), "constant and code agree");
        }
    }

    @Test
    void everyCodeIsListedWithMeaningUserActionAndSupportText() throws Exception {
        // Maven runs tests from the module folder (backend/sdv-web).
        Path doc = Path.of("..", "..", "docs", "api", "error-codes.md");
        String text = Files.readString(doc, StandardCharsets.UTF_8);
        Set<String> listed = new HashSet<>();
        Matcher heading = Pattern.compile("(?m)^### (SDV-[A-Z]{3}-\\d{3}):").matcher(text);
        while (heading.find()) listed.add(heading.group(1));
        for (SdvErrorCode code : SdvErrorCode.values()) {
            assertTrue(listed.contains(code.code()), code.code() + " is not listed in docs/api/error-codes.md");
            int start = text.indexOf("### " + code.code() + ":");
            int end = text.indexOf("\n### ", start + 1);
            String section = text.substring(start, end < 0 ? text.length() : end);
            for (String part : new String[]{"**Meaning:**", "**What the user should do:**", "**What support should tell the customer:**"}) {
                assertTrue(section.contains(part), code.code() + " lacks " + part);
            }
        }
        assertEquals(listed, new HashSet<>(Arrays.stream(SdvErrorCode.values()).map(SdvErrorCode::code).toList()),
                "the doc lists only codes the product has (retired ones would stay in both)");
    }

    @RestController
    static class Refusing {
        @GetMapping("/bad") void bad() { throw new CodedRefusal.BadRequest(SdvErrorCode.SEN_002, "plain message"); }
        @GetMapping("/conflict") void conflict() { throw new CodedRefusal.Conflict(SdvErrorCode.SEN_007, "plain message"); }
        @GetMapping("/plain") void plain() { throw new IllegalStateException("uncoded"); }
        @GetMapping("/restricted") void restricted() {
            throw new CodedRefusal.Forbidden(SdvErrorCode.SEN_008, "This document is restricted.");
        }
    }

    @Test
    void theErrorBodyCarriesTheCodeAndTheHttpStatusIsUnchanged() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new Refusing())
                .setControllerAdvice(new FoundationExceptionHandler()).build();
        mvc.perform(get("/bad")).andExpect(status().is(HttpStatus.BAD_REQUEST.value()))
                .andExpect(jsonPath("$.code").value("SDV-SEN-002")).andExpect(jsonPath("$.message").value("plain message"));
        mvc.perform(get("/conflict")).andExpect(status().is(HttpStatus.CONFLICT.value()))
                .andExpect(jsonPath("$.code").value("SDV-SEN-007"));
        mvc.perform(get("/plain")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"));
        // Task 0026: a coded 403 sends exactly the code and the fixed message (and the time), nothing else.
        mvc.perform(get("/restricted")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SDV-SEN-008"))
                .andExpect(jsonPath("$.message").value("This document is restricted."))
                .andExpect(jsonPath("$.length()").value(3));
    }
}
