package dev.tahir.blueprint.cache;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

@Configuration
public class RedisConfig {

    /**
     * A template typed to exactly one value class, serialised as JSON.
     *
     * <p>Not JDK serialisation: two independently deployed services read and write these
     * values, and JDK serialisation would couple them to identical class bytecode, so a
     * worker deploy would start throwing {@code InvalidClassException} inside the API. JSON
     * also means the value can be read with {@code redis-cli GET} during an incident.
     *
     * <p>Not polymorphic typing either. Jackson's default typing embeds a class name in the
     * document and instantiates it on read, which turns a shared cache into a
     * deserialisation gadget: anything that can write to Redis can choose what class the API
     * constructs. Binding the serialiser to a single concrete type removes that entirely,
     * and costs only an extra bean when a second cached type appears.
     */
    @Bean
    RedisTemplate<String, InventorySummary> inventorySummaryRedisTemplate(
            RedisConnectionFactory connectionFactory, ObjectMapper baseMapper) {
        ObjectMapper mapper = baseMapper.copy().registerModule(new JavaTimeModule());

        RedisTemplate<String, InventorySummary> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new Jackson2JsonRedisSerializer<>(mapper, InventorySummary.class));
        template.afterPropertiesSet();
        return template;
    }
}
