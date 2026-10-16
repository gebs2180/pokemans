package com.pokemans.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.pokemans.app.identity.RoleClaimConverter;
import com.pokemans.app.identity.SecurityProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Calls the API as a given user in integration tests, and sets up orgs, cards and photos. */
public class TestApi {

    private final MockMvc mvc;
    private final SecurityProperties security;
    private final HttpClient http = HttpClient.newHttpClient();

    public TestApi(MockMvc mvc, SecurityProperties security) {
        this.mvc = mvc;
        this.security = security;
    }

    public JwtRequestPostProcessor user(String subject, List<String> roles) {
        return jwt()
                .jwt(b -> b.subject(subject).claim(security.rolesClaim(), roles).claim(security.mfaClaim(), true))
                .authorities(new RoleClaimConverter(security)::convert);
    }

    public JwtRequestPostProcessor admin() {
        return user("auth0|admin", List.of("ADMIN"));
    }

    public static String read(ResultActions result, String path) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), path).toString();
    }

    public ResultActions postJson(String url, String json, JwtRequestPostProcessor who) throws Exception {
        return mvc.perform(post(url).with(who).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    public ResultActions get(String url, JwtRequestPostProcessor who) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url).with(who));
    }

    /** A collector org with the given user as its member. Returns the org id. */
    public String collectorOrg(String name, String memberSubject) throws Exception {
        String orgId = read(postJson("/api/admin/orgs", "{\"name\":\"" + name + "\",\"type\":\"COLLECTOR\"}", admin())
                .andExpect(status().isCreated()), "$.id");
        String token = read(postJson("/api/admin/orgs/" + orgId + "/invites", "{}", admin()), "$.token");
        mvc.perform(post("/api/invites/" + token + "/accept").with(user(memberSubject, List.of()))).andExpect(status().isOk());
        return orgId;
    }

    /** A single card in a new collection of the org. */
    public String card(String orgId, String label, JwtRequestPostProcessor who) throws Exception {
        String collectionId = read(postJson("/api/orgs/" + orgId + "/collections", "{\"name\":\"Binder\"}", who), "$.id");
        return read(postJson("/api/orgs/" + orgId + "/collections/" + collectionId + "/cards",
                "{\"label\":\"" + label + "\",\"lot\":false,\"quantity\":1}", who).andExpect(status().isCreated()), "$.id");
    }

    /** Requests an upload slot, PUTs the bytes to storage, and confirms. Returns the confirm response. */
    public ResultActions uploadPhoto(String orgId, String cardId, String side, byte[] png, JwtRequestPostProcessor who) throws Exception {
        String body = postJson("/api/orgs/" + orgId + "/cards/" + cardId + "/photos",
                "{\"side\":\"" + side + "\",\"contentType\":\"image/png\",\"sizeBytes\":" + png.length + "}", who)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String photoId = JsonPath.read(body, "$.photoId");
        assertThat(put(JsonPath.read(body, "$.url"), JsonPath.read(body, "$.headers"), png)).isEqualTo(200);
        return mvc.perform(post("/api/orgs/" + orgId + "/photos/" + photoId + "/confirm").with(who));
    }

    public int put(String url, Map<String, Object> headers, byte[] body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).PUT(HttpRequest.BodyPublishers.ofByteArray(body));
        headers.forEach((name, value) -> request.header(name, value.toString()));
        return http.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
