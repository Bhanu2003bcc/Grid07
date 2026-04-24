package com.grid07.scheduler;

import com.grid07.config.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationScheduler {

    private final StringRedisTemplate redis;

    @Scheduled(fixedRateString = "${app.scheduler.notification-sweep-ms:300000}")
    public void sweepPendingNotifications() {
        log.info("[NOTIF SWEEPER] Starting sweep of pending notifications...");

        List<String> pendingKeys = scanPendingNotifKeys();

        if (pendingKeys.isEmpty()) {
            log.info("[NOTIF SWEEPER] No pending notifications found.");
            return;
        }

        log.info("[NOTIF SWEEPER] Found {} user queues to process.", pendingKeys.size());

        int totalProcessed = 0;
        for (String key : pendingKeys) {
            totalProcessed += processPendingNotificationsForUser(key);
        }

        log.info("[NOTIF SWEEPER] Sweep complete. Processed {} total notifications.", totalProcessed);
    }

    //  Private helpers

    private List<String> scanPendingNotifKeys() {
        List<String> keys = new ArrayList<>();

        ScanOptions options = ScanOptions.scanOptions()
                .match(RedisKeys.PENDING_NOTIFS_PATTERN)
                .count(100)
                .build();

        try (Cursor<String> cursor = redis.scan(options)) {
            while (cursor.hasNext()) {
                keys.add(cursor.next());
            }
        } catch (Exception e) {
            log.error("[NOTIF SWEEPER] Error during Redis SCAN: {}", e.getMessage());
        }

        return keys;
    }


//     Processes all pending notifications for a single user:
//     1. LRANGE to read all messages


    private int processPendingNotificationsForUser(String key) {
        // Extract userId from key pattern "user:{id}:pending_notifs"
        String userId = extractUserId(key);

        // Read all pending messages
        List<String> messages = redis.opsForList().range(key, 0, -1);
        if (messages == null || messages.isEmpty()) {
            return 0;
        }

        int count = messages.size();

        // Atomically clear the list
        redis.delete(key);

        // Build summarized notification log
        logSummarizedNotification(userId, messages, count);

        return count;
    }

//        1 message  → "Summarized Push Notification: BotX replied to your post #1"


    private void logSummarizedNotification(String userId, List<String> messages, int count) {
        if (count == 1) {
            log.info("[NOTIF SWEEPER] Summarized Push Notification to User {}: {}",
                    userId, messages.get(0));
        } else {
            String firstMessage = messages.get(0);
            // Extract bot name
            String firstBotName = firstMessage.split(" ")[0];
            log.info("[NOTIF SWEEPER] Summarized Push Notification to User {}: " +
                     "{} and [{}] others interacted with your posts.",
                    userId, firstBotName, count - 1);
        }
    }


//      Extracts userId from a Redis key of the form "user:{id}:pending_notifs".

    private String extractUserId(String key) {
        // key = "user:42:pending_notifs"
        String[] parts = key.split(":");
        return parts.length >= 2 ? parts[1] : "unknown";
    }
}
