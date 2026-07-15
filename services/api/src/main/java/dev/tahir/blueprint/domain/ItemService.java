package dev.tahir.blueprint.domain;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.tahir.blueprint.outbox.OutboxRecorder;
import dev.tahir.blueprint.platform.events.EventEnvelope;
import dev.tahir.blueprint.platform.events.EventTypes;

@Service
public class ItemService {

    private static final Logger log = LoggerFactory.getLogger(ItemService.class);

    private final ItemRepository repository;
    private final OutboxRecorder outbox;

    public ItemService(ItemRepository repository, OutboxRecorder outbox) {
        this.repository = repository;
        this.outbox = outbox;
    }

    @Transactional(readOnly = true)
    public Page<Item> list(Pageable pageable) {
        return repository.findAll(pageable);
    }

    @Transactional(readOnly = true)
    public Item get(UUID id) {
        return repository.findById(id).orElseThrow(() -> new ItemNotFoundException(id));
    }

    /**
     * The item row and its event are written in one transaction. Publishing happens later,
     * from the outbox relay — attempting to publish here would be a dual write, and a broker
     * timeout would either roll back a perfectly good business operation or leave an event
     * describing an item that no longer exists.
     */
    @Transactional
    public Item create(String name, String description, int quantity) {
        if (repository.existsByNameIgnoreCase(name)) {
            throw new DuplicateItemException(name);
        }
        Item saved = repository.save(Item.create(name, description, quantity));

        outbox.record(
                EventTypes.ITEM_CREATED,
                saved.getId().toString(),
                new EventEnvelope.ItemChanged(saved.getName(), saved.getQuantity(), null));

        log.info("item created id={} name={} quantity={}", saved.getId(), saved.getName(), saved.getQuantity());
        return saved;
    }

    @Transactional
    public Item update(UUID id, String name, String description, int quantity) {
        Item item = get(id);
        int previousQuantity = item.getQuantity();
        item.update(name, description, quantity);

        outbox.record(
                EventTypes.ITEM_UPDATED,
                id.toString(),
                new EventEnvelope.ItemChanged(name, quantity, previousQuantity));

        log.info("item updated id={} quantity={} previousQuantity={}", id, quantity, previousQuantity);
        return item;
    }

    @Transactional
    public void delete(UUID id) {
        Item item = repository.findById(id).orElseThrow(() -> new ItemNotFoundException(id));
        int previousQuantity = item.getQuantity();
        String name = item.getName();
        repository.delete(item);

        outbox.record(EventTypes.ITEM_DELETED, id.toString(), new EventEnvelope.ItemChanged(name, 0, previousQuantity));

        log.info("item deleted id={}", id);
    }

    @Transactional(readOnly = true)
    public long count() {
        return repository.count();
    }
}
