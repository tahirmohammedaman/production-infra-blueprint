package dev.tahir.blueprint.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

import dev.tahir.blueprint.outbox.OutboxRecorder;
import dev.tahir.blueprint.platform.events.EventEnvelope;
import dev.tahir.blueprint.platform.events.EventTypes;

@ExtendWith(MockitoExtension.class)
class ItemServiceTest {

    @Mock
    private ItemRepository repository;

    @Mock
    private OutboxRecorder outbox;

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
        // No item, therefore no event. Recording one anyway would publish a fact that never
        // happened, and the worker would project an item that does not exist.
        verify(outbox, never()).record(any(), any(), any());
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
        verify(outbox)
                .record(
                        eq(EventTypes.ITEM_CREATED),
                        eq(created.getId().toString()),
                        eq(new EventEnvelope.ItemChanged("widget", 3, null)));
    }

    @Test
    @DisplayName("records the previous quantity on update so the worker can apply a delta")
    void updateRecordsPreviousQuantity() {
        Item existing = Item.create("gadget", null, 5);
        when(repository.findById(existing.getId())).thenReturn(Optional.of(existing));

        service.update(existing.getId(), "gadget", "changed", 12);

        verify(outbox)
                .record(
                        eq(EventTypes.ITEM_UPDATED),
                        eq(existing.getId().toString()),
                        eq(new EventEnvelope.ItemChanged("gadget", 12, 5)));
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
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(id)).isInstanceOf(ItemNotFoundException.class);

        verify(repository, never()).delete(any());
        verify(outbox, never()).record(any(), any(), any());
    }
}
