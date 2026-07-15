package dev.tahir.blueprint.platform.events;

/** Event type strings and topic names, shared so producer and consumer cannot disagree. */
public final class EventTypes {

    public static final String ITEM_CREATED = "item.created";
    public static final String ITEM_UPDATED = "item.updated";
    public static final String ITEM_DELETED = "item.deleted";

    /** Main topic. Created by deploy-time configuration, never by broker auto-creation. */
    public static final String TOPIC_ITEM_EVENTS = "item-events";

    /**
     * Dead-letter topic. Named by convention as {@code <topic>.DLT} because that is what
     * Spring Kafka's {@code DeadLetterPublishingRecoverer} defaults to; changing it would
     * mean configuring the resolver everywhere for no benefit.
     */
    public static final String TOPIC_ITEM_EVENTS_DLT = TOPIC_ITEM_EVENTS + ".DLT";

    /** Kafka record header carrying the correlation id across the async boundary. */
    public static final String HEADER_CORRELATION_ID = "X-Request-Id";

    public static final String HEADER_EVENT_ID = "X-Event-Id";
    public static final String HEADER_EVENT_TYPE = "X-Event-Type";

    private EventTypes() {}
}
