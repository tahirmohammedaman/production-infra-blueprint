package dev.tahir.blueprint.api;

import dev.tahir.blueprint.api.dto.ItemRequest;
import dev.tahir.blueprint.api.dto.ItemResponse;
import dev.tahir.blueprint.api.dto.PageResponse;
import dev.tahir.blueprint.domain.Item;
import dev.tahir.blueprint.domain.ItemService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/v1/items")
@Validated
@Tag(name = "Items", description = "Database-backed read and write operations")
public class ItemController {

    private static final int MAX_PAGE_SIZE = 100;

    private final ItemService service;

    public ItemController(ItemService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "List items, newest first")
    public PageResponse<ItemResponse> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return PageResponse.of(service.list(pageable), ItemResponse::from);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Fetch a single item")
    public ItemResponse get(@PathVariable UUID id) {
        return ItemResponse.from(service.get(id));
    }

    @PostMapping
    @Operation(summary = "Create an item")
    public ResponseEntity<ItemResponse> create(@Valid @RequestBody ItemRequest request) {
        Item created = service.create(request.name(), request.description(), request.quantity());
        return ResponseEntity
                .created(URI.create("/api/v1/items/" + created.getId()))
                .body(ItemResponse.from(created));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Replace an item")
    public ItemResponse update(@PathVariable UUID id, @Valid @RequestBody ItemRequest request) {
        return ItemResponse.from(service.update(id, request.name(), request.description(), request.quantity()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete an item")
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }
}
