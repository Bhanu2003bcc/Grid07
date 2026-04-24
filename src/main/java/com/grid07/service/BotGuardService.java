package com.grid07.service;

import com.grid07.config.GuardrailProperties;
import com.grid07.config.RedisKeys;
import com.grid07.exception.GuardrailException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
@RequiredArgsConstructor
@Slf4j
public class BotGuardService {

    private final StringRedisTemplate redis;
    private final GuardrailProperties props;

    //  Public entry point: run ALL three guardrail checks atomically
    //  before any bot comment is allowed through.

    // Atomic increment to prevent race conditions
    public void enforceAllGuardrails(Long postId, Long botId, Long humanId, int depth) {
        checkVerticalCap(depth);
        checkCooldownCap(botId, humanId);
        checkHorizontalCap(postId);  // ← must be last: it mutates state (INCR)
    }

    public void rollbackBotCount(Long postId) {
        String key = RedisKeys.botCount(postId);
        Long result = redis.opsForValue().decrement(key);
        log.warn("[GUARDRAIL ROLLBACK] post:{} bot_count decremented to {}", postId, result);
    }

    // cooldown after bot interaction
    public void setBotCooldown(Long botId, Long humanId) {
        String key = RedisKeys.botCooldown(botId, humanId);
        redis.opsForValue().set(key, "1", Duration.ofSeconds(props.getBotCooldownSeconds()));
        log.debug("[GUARDRAIL] Cooldown set: bot={} human={} ttl={}s",
                botId, humanId, props.getBotCooldownSeconds());
    }

    //  Internal guardrail checks

    private void checkHorizontalCap(Long postId) {
        String key = RedisKeys.botCount(postId);
        Long newCount = redis.opsForValue().increment(key);

        if (newCount == null) {
            // Redis connection issue
            throw new GuardrailException(
                "Unable to verify bot reply cap. Request rejected for safety.");
        }

        if (newCount > props.getBotReplyCap()) {
            // Immediately rollback
            redis.opsForValue().decrement(key);
            log.warn("[GUARDRAIL BLOCKED] Horizontal cap reached for post:{}. " +
                     "Attempted count={}, cap={}", postId, newCount, props.getBotReplyCap());
            throw new GuardrailException(
                "Bot reply cap of " + props.getBotReplyCap() +
                " reached for post " + postId + ". rejected.");
        }

        log.debug("[GUARDRAIL PASS] Horizontal cap: post={} bot_count={}/{}",
                postId, newCount, props.getBotReplyCap());
    }


    // Vertical Cap -> thread depth
    private void checkVerticalCap(int depth) {
        if (depth > props.getMaxCommentDepth()) {
            log.warn("[GUARDRAIL BLOCKED] Vertical cap: depth={} exceeds max={}",
                    depth, props.getMaxCommentDepth());
            throw new GuardrailException(
                "Comment thread depth of " + depth + " exceeds maximum allowed depth of " +
                props.getMaxCommentDepth() + ".");
        }
    }

    private void checkCooldownCap(Long botId, Long humanId) {
        String key = RedisKeys.botCooldown(botId, humanId);
        Boolean exists = redis.hasKey(key);

        if (Boolean.TRUE.equals(exists)) {
            log.warn("[GUARDRAIL BLOCKED] Cooldown active: bot={} human={}",
                    botId, humanId);
            throw new GuardrailException(
                "Bot " + botId + " must wait before interacting with user " +
                humanId + " again. Cooldown: " + props.getBotCooldownSeconds() + "s.");
        }
    }
}
