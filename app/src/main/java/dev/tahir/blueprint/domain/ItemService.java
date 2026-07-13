package dev.tahir.blueprint.domain;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ItemService {

    private static final Logger log = LoggerFactory.getLogger(ItemService.class);

    private final ItemRepository repository;

    public ItemService(ItemRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Page<Item> list(Pageable pageable) {
        return repository.findAll(pageable);
    }

    @Transactional(readOnly = true)
    public Item get(UUID id) {
        return repository.findById(id).orElseThrow(() -> new ItemNotFoundException(id));
    }

    @Transactional
    public Item create(String name, String description, int quantity) {
        if (repository.existsByNameIgnoreCase(name)) {
            throw new DuplicateItemException(name);
        }
        Item saved = repository.save(Item.create(name, description, quantity));
        log.info("item created id={} name={} quantity={}", saved.getId(), saved.getName(), saved.getQuantity());
        return saved;
    }

    @Transactional
    public Item update(UUID id, String name, String description, int quantity) {
        Item item = get(id);
        item.update(name, description, quantity);
        log.info("item updated id={} quantity={}", id, quantity);
        return item;
    }

    @Transactional
    public void delete(UUID id) {
        if (!repository.existsById(id)) {
            throw new ItemNotFoundException(id);
        }
        repository.deleteById(id);
        log.info("item deleted id={}", id);
    }

    @Transactional(readOnly = true)
    public long count() {
        return repository.count();
    }

    @Transactional(readOnly = true)
    public List<Item> sample(int limit) {
        return repository.findAll(Pageable.ofSize(limit)).getContent();
    }
}
