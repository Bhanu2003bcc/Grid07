package com.grid07.service;

import com.grid07.config.GuardrailProperties;
import com.grid07.config.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    private final StringRedisTemplate redis;
    private final GuardrailProperties props;

//  Called after bot interaction with a human's post.
    public void handleBotInteractionNotification(Long userId, String botName, Long postId) {
        String cooldownKey = RedisKeys.notificationCooldown(userId);
        String pendingKey  = RedisKeys.pendingNotifications(userId);
        String message     = buildMessage(botName, postId);

        Boolean onCooldown = redis.hasKey(cooldownKey);

        if (Boolean.TRUE.equals(onCooldown)) {
           //cooldown: queue the notification   -> user
            redis.opsForList().rightPush(pendingKey, message);
            log.info("[NOTIFICATION] Queued for user={}: \"{}\"", userId, message);
        } else {
            // No cooldown
            log.info("[NOTIFICATION] Push Notification Sent to User {}: \"{}\"",
                    userId, message);
            redis.opsForValue().set(
                cooldownKey, "1",
                Duration.ofSeconds(props.getNotificationCooldownSeconds())
            );
        }
    }

    private String buildMessage(String botName, Long postId) {
        return botName + " replied to your post #" + postId;
    }
}
