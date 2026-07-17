package dev.tahir.blueprint;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import dev.tahir.blueprint.api.dto.ItemRequest;
import dev.tahir.blueprint.api.dto.ItemResponse;
import dev.tahir.blueprint.domain.ItemRepository;

class ItemApiIntegrationTest extends IntegrationTestBase {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ItemRepository repository;

    @BeforeEach
    void reset() {
        repository.deleteAll();
    }

    @Test
    @DisplayName("round-trips an item through the real database")
    void createThenReadRoundTrip() {
        ResponseEntity<ItemResponse> created =
                rest.postForEntity("/api/v1/items", new ItemRequest("widget", "a widget", 4), ItemResponse.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getHeaders().getLocation()).isNotNull();
        UUID id = created.getBody().id();

        ResponseEntity<ItemResponse> fetched = rest.getForEntity("/api/v1/items/{id}", ItemResponse.class, id);

        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody().name()).isEqualTo("widget");
        assertThat(fetched.getBody().quantity()).isEqualTo(4);
        assertThat(fetched.getBody().createdAt()).isNotNull();
    }

    @Test
    @DisplayName("enforces case-insensitive name uniqueness in the database, not just in code")
    void duplicateNameIsRejectedCaseInsensitively() {
        rest.postForEntity("/api/v1/items", new ItemRequest("widget", null, 1), Void.class);

        ResponseEntity<String> duplicate =
                rest.postForEntity("/api/v1/items", new ItemRequest("WIDGET", null, 1), String.class);

        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(duplicate.getBody()).contains("Duplicate item");
    }

    @Test
    @DisplayName("bumps the optimistic-lock version on update")
    void updateIncrementsVersion() {
        UUID id = rest.postForEntity("/api/v1/items", new ItemRequest("gadget", null, 1), ItemResponse.class)
                .getBody()
                .id();

        ResponseEntity<ItemResponse> updated = rest.exchange(
                "/api/v1/items/{id}",
                HttpMethod.PUT,
                new HttpEntity<>(new ItemRequest("gadget", "now with more", 9)),
                ItemResponse.class,
                id);

        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updated.getBody().quantity()).isEqualTo(9);
        assertThat(updated.getBody().version()).isGreaterThan(0);
    }

    @Test
    @DisplayName("deletes an item and reports 404 afterwards")
    void deleteRemovesItem() {
        UUID id = rest.postForEntity("/api/v1/items", new ItemRequest("doomed", null, 1), ItemResponse.class)
                .getBody()
                .id();

        ResponseEntity<Void> deleted = rest.exchange("/api/v1/items/{id}", HttpMethod.DELETE, null, Void.class, id);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(rest.getForEntity("/api/v1/items/{id}", String.class, id).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("echoes a supplied correlation id and rejects a malformed one")
    void correlationIdHandling() {
        HttpHeaders good = new HttpHeaders();
        good.setContentType(MediaType.APPLICATION_JSON);
        good.set("X-Request-Id", "trace-abc-12345");

        ResponseEntity<String> withId =
                rest.exchange("/api/v1/items", HttpMethod.GET, new HttpEntity<>(good), String.class);
        assertThat(withId.getHeaders().getFirst("X-Request-Id")).isEqualTo("trace-abc-12345");

        HttpHeaders bad = new HttpHeaders();
        bad.set("X-Request-Id", "no");

        ResponseEntity<String> replaced =
                rest.exchange("/api/v1/items", HttpMethod.GET, new HttpEntity<>(bad), String.class);
        assertThat(replaced.getHeaders().getFirst("X-Request-Id"))
                .isNotEqualTo("no")
                .hasSize(36);
    }

    @Test
    @DisplayName("returns a 404 problem document for an unmapped path instead of a 500")
    void unmappedPathIsNotAServerError() {
        ResponseEntity<String> response = rest.getForEntity("/definitely/not/a/route", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
