package dev.tahir.blueprint.worker.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import dev.tahir.blueprint.worker.processing.InventorySummary;

/**
 * Mirrors the API's serialisation settings exactly. Both services must agree byte for byte
 * on this shape; the two configurations are separate because the services deploy
 * separately, and a shared bean would hide a breaking change behind a single commit.
 */
@Configuration
public class WorkerRedisConfig {

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
