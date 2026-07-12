package dev.tahir.blueprint.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ItemServiceTest {

    @Mock
    private ItemRepository repository;

    @InjectMocks
    private ItemService service;

    @Test
    @DisplayName("rejects a duplicate name before touching the database")
    void createRejectsDuplicateName() {
        when(repository.existsByNameIgnoreCase("widget")).thenReturn(true);

        assertThatThrownBy(() -> service.create("widget", "desc", 1))
                .isInstanceOf(DuplicateItemException.class)
                .hasMessageContaining("widget");

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("persists a new item when the name is free")
    void createPersistsWhenNameIsFree() {
        when(repository.existsByNameIgnoreCase("widget")).thenReturn(false);
        when(repository.save(any(Item.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Item created = service.create("widget", "desc", 3);

        assertThat(created.getName()).isEqualTo("widget");
        assertThat(created.getQuantity()).isEqualTo(3);
        assertThat(created.getId()).isNotNull();
    }

    @Test
    @DisplayName("reports a missing item rather than returning null")
    void getThrowsWhenAbsent() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(id)).isInstanceOf(ItemNotFoundException.class);
    }

    @Test
    @DisplayName("does not issue a delete for an id that does not exist")
    void deleteChecksExistenceFirst() {
        UUID id = UUID.randomUUID();
        when(repository.existsById(id)).thenReturn(false);

        assertThatThrownBy(() -> service.delete(id)).isInstanceOf(ItemNotFoundException.class);

        verify(repository, never()).deleteById(id);
    }
}
