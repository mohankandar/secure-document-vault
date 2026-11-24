package com.sdv.platform.web.foundation;

import com.sdv.platform.domain.error.StartupFailure;
import com.sdv.platform.web.config.CorrelationIdFilter;
import com.sdv.platform.web.config.JsonLimitsConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Task 0062: every body but a document upload has a limit, by Content-Length and while it is read. */
class RequestBodyLimitTest {
    static final AtomicInteger READ = new AtomicInteger();
    private static final RequestBodyLimitFilter FILTER = new RequestBodyLimitFilter(2_000, 5_000);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new Probe()).setControllerAdvice(new FoundationExceptionHandler())
            .addFilters(new CorrelationIdFilter(), FILTER).build();

    @RestController
    static class Probe {
        @PostMapping({"/any", RequestBodyLimitFilter.SUBMISSIONS}) String list(@RequestBody List<String> body) { READ.incrementAndGet(); return "" + body.size(); }
        @PostMapping(RequestBodyLimitFilter.SUBMISSIONS + "/{id}/documents") String upload(jakarta.servlet.http.HttpServletRequest rq) throws Exception {
            return "" + rq.getInputStream().readAllBytes().length;
        }
    }

    private static String json(int size) {
        return "[\"" + "x".repeat(size - 4) + "\"]";
    }

    /** A body whose length isn't announced. */
    private static org.springframework.test.web.servlet.request.RequestPostProcessor chunked(String body) {
        return rq -> {
            MockHttpServletRequest unannounced = new MockHttpServletRequest(rq.getServletContext(), rq.getMethod(), rq.getRequestURI()) {
                @Override public int getContentLength() { return -1; }
                @Override public long getContentLengthLong() { return -1; }
            };
            unannounced.setContentType("application/json");
            unannounced.setContent(body.getBytes());
            return unannounced;
        };
    }

    @Test
    void overTheLimitIs413WhetherAnnouncedOrNotAndNoControllerRuns() throws Exception {
        int before = READ.get();
        for (var request : List.of(post("/any").contentType(MediaType.APPLICATION_JSON).content(json(2_001)),
                post("/any").with(chunked(json(2_001))), post(RequestBodyLimitFilter.SUBMISSIONS).with(chunked(json(5_001))),
                post(RequestBodyLimitFilter.SUBMISSIONS).contentType(MediaType.APPLICATION_JSON).content(json(5_001)))) {
            mvc.perform(request).andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value("SDV-SYS-019"))
                    .andExpect(jsonPath("$.message").value(ErrorReplies.SYS_019_MESSAGE)).andExpect(jsonPath("$.reference").isNotEmpty());
        }
        assertEquals(before, READ.get());
    }

    @Test
    void upToTheLimitIsReadAndAnUploadIsNotCounted() throws Exception {
        mvc.perform(post("/any").contentType(MediaType.APPLICATION_JSON).content(json(2_000))).andExpect(status().isOk());
        mvc.perform(post("/any").with(chunked(json(2_000)))).andExpect(status().isOk());
        mvc.perform(post(RequestBodyLimitFilter.SUBMISSIONS).contentType(MediaType.APPLICATION_JSON).content(json(5_000))).andExpect(status().isOk());
        assertEquals("9000", mvc.perform(post(RequestBodyLimitFilter.SUBMISSIONS + "/a/documents").contentType("application/pdf").content(new byte[9_000]))
                .andReturn().getResponse().getContentAsString());
    }

    @Test
    void onlyTheUploadAddressIsFree() throws Exception {
        for (String path : List.of(RequestBodyLimitFilter.SUBMISSIONS + "/a/documents/x", RequestBodyLimitFilter.SUBMISSIONS + "/a/b/documents", "/x" + RequestBodyLimitFilter.SUBMISSIONS + "/a/documents")) {
            mvc.perform(post(path).contentType("application/pdf").content(new byte[9_000])).andExpect(status().isPayloadTooLarge());
        }
    }

    @Test
    void theJsonReaderHasTheSameLimitAndABadSettingStopsTheStart() {
        var builder = tools.jackson.core.json.JsonFactory.builder();
        new JsonLimits().customize(builder);
        assertEquals(5_000, builder.build().streamReadConstraints().getMaxDocumentLength());
        assertThrows(StartupFailure.class, () -> new RequestBodyLimitFilter(0, 5_000));
        assertThrows(StartupFailure.class, () -> new RequestBodyLimitFilter(2_000, 1L << 40));
    }

    private static final class JsonLimits {
        void customize(tools.jackson.core.json.JsonFactoryBuilder builder) {
            new org.springframework.boot.test.context.runner.ApplicationContextRunner().withBean(RequestBodyLimitFilter.class, () -> FILTER)
                    .withUserConfiguration(JsonLimitsConfiguration.class).run(c -> c.getBean(
                            org.springframework.boot.jackson.autoconfigure.JsonFactoryBuilderCustomizer.class).customize(builder));
        }
    }
}
