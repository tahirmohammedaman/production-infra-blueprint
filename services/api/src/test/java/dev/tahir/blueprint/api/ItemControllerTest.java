package dev.tahir.blueprint.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import dev.tahir.blueprint.domain.DuplicateItemException;
import dev.tahir.blueprint.domain.Item;
import dev.tahir.blueprint.domain.ItemNotFoundException;
import dev.tahir.blueprint.domain.ItemService;

/**
 * Web-layer slice. Asserts the HTTP contract — status codes, problem bodies and the
 * correlation header — without starting a database.
 */
@WebMvcTest(ItemController.class)
class ItemControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ItemService service;

    @Test
    @DisplayName("returns 201 with a Location header on create")
    void createReturnsCreated() throws Exception {
        Item item = Item.create("widget", "desc", 5);
        when(service.create(anyString(), anyString(), anyInt())).thenReturn(item);

        mockMvc.perform(post("/api/v1/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"widget\",\"description\":\"desc\",\"quantity\":5}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/items/" + item.getId()))
                .andExpect(jsonPath("$.name").value("widget"));
    }

    @Test
    @DisplayName("returns a 400 problem document listing each invalid field")
    void validationFailureIsAProblemDocument() throws Exception {
        mockMvc.perform(post("/api/v1/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"quantity\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Request validation failed"))
                .andExpect(jsonPath("$.errors.name").exists())
                .andExpect(jsonPath("$.errors.quantity").exists());
    }

    @Test
    @DisplayName("maps a duplicate name to 409 rather than 500")
    void duplicateIsConflict() throws Exception {
        when(service.create(anyString(), any(), anyInt())).thenThrow(new DuplicateItemException("widget"));

        mockMvc.perform(post("/api/v1/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"widget\",\"quantity\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Duplicate item"));
    }

    @Test
    @DisplayName("maps a missing item to 404")
    void missingItemIsNotFound() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.get(eq(id))).thenThrow(new ItemNotFoundException(id));

        mockMvc.perform(get("/api/v1/items/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Item not found"));
    }

    @Test
    @DisplayName("rejects a page size beyond the documented maximum")
    void oversizedPageIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/items").param("size", "5000")).andExpect(status().isBadRequest());
    }
}
