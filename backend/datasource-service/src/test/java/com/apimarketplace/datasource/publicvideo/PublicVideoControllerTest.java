package com.apimarketplace.datasource.publicvideo;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The wire format the public website reads.
 *
 * <p>Worth its own test because the frontend is written against these exact key
 * names: a field renamed here still compiles, still passes the service test, and
 * empties a page nobody looks at until a crawler does.
 */
class PublicVideoControllerTest {

    private final PublicVideoService service = mock(PublicVideoService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new PublicVideoController(service))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper()))
                .build();
        when(service.isConfigured()).thenReturn(true);
    }

    private PublicVideoDto film() {
        return new PublicVideoDto(
                "automate-x", "LiveContext product films", "Automate X, end to end",
                "A five minute film about X.", "lG-wfKo2NOo", 318, "2026-09-10T15:57:19Z",
                "https://livecontext.ai/videos/automate-x.webp",
                "https://livecontext.ai/videos/automate-x.jpg", "The X screen",
                List.of("The problem."), List.of("The answer."), List.of("A claim"),
                List.of(new PublicVideoDto.Chapter(0d, 15.4d, "The problem")),
                List.of(new PublicVideoDto.TranscriptLine(0.15d, "You posted the role.")),
                "x-app", "X App");
    }

    @Test
    @DisplayName("serves every published film under the keys the website reads")
    void servesTheLibrary() throws Exception {
        when(service.published()).thenReturn(List.of(film()));

        mockMvc.perform(get("/api/public/videos").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.videos[0].slug").value("automate-x"))
                .andExpect(jsonPath("$.videos[0].title").value("Automate X, end to end"))
                .andExpect(jsonPath("$.videos[0].youtubeId").value("lG-wfKo2NOo"))
                .andExpect(jsonPath("$.videos[0].durationSeconds").value(318))
                .andExpect(jsonPath("$.videos[0].publishedAt").value("2026-09-10T15:57:19Z"))
                .andExpect(jsonPath("$.videos[0].posterUrl").value("https://livecontext.ai/videos/automate-x.webp"))
                .andExpect(jsonPath("$.videos[0].shareImageUrl").value("https://livecontext.ai/videos/automate-x.jpg"))
                .andExpect(jsonPath("$.videos[0].problem[0]").value("The problem."))
                .andExpect(jsonPath("$.videos[0].answer[0]").value("The answer."))
                .andExpect(jsonPath("$.videos[0].highlights[0]").value("A claim"))
                .andExpect(jsonPath("$.videos[0].chapters[0].start").value(0d))
                .andExpect(jsonPath("$.videos[0].chapters[0].end").value(15.4d))
                .andExpect(jsonPath("$.videos[0].chapters[0].title").value("The problem"))
                .andExpect(jsonPath("$.videos[0].transcript[0].t").value(0.15d))
                .andExpect(jsonPath("$.videos[0].transcript[0].text").value("You posted the role."))
                .andExpect(jsonPath("$.videos[0].marketplaceSlug").value("x-app"))
                .andExpect(jsonPath("$.videos[0].marketplaceTitle").value("X App"));
    }

    @Test
    @DisplayName("tells an empty library apart from an install that has none")
    void distinguishesEmptyFromUnconfigured() throws Exception {
        when(service.published()).thenReturn(List.of());
        when(service.isConfigured()).thenReturn(false);

        mockMvc.perform(get("/api/public/videos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0))
                .andExpect(jsonPath("$.configured").value(false));
    }

    @Test
    @DisplayName("serves one film by its slug")
    void servesOneFilm() throws Exception {
        when(service.bySlug("automate-x")).thenReturn(film());

        mockMvc.perform(get("/api/public/videos/automate-x"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("automate-x"))
                .andExpect(jsonPath("$.chapters[0].title").value("The problem"));
    }

    @Test
    @DisplayName("404s a slug the library does not publish, drafts included")
    void notFoundForADraft() throws Exception {
        when(service.bySlug(anyString())).thenReturn(null);

        mockMvc.perform(get("/api/public/videos/a-draft")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("has no write surface: the allow-list in front of it matches on path, not verb")
    void readOnly() throws Exception {
        // The gateway's public allow-list is method-agnostic, so a route on it is
        // reachable by any verb. What makes that safe is this controller having
        // nothing but GET mappings, which is what this pins.
        when(service.published()).thenReturn(List.of(film()));

        mockMvc.perform(post("/api/public/videos")).andExpect(status().isMethodNotAllowed());
        mockMvc.perform(post("/api/public/videos/automate-x")).andExpect(status().isMethodNotAllowed());
    }
}
