package com.grid07.config;


//  Centralized Redis key definitions.

public final class RedisKeys {

    private RedisKeys() {}

    public static String viralityScore(Long postId) {
        return "post:" + postId + ":virality_score";
    }


    public static String botCount(Long postId) {
        return "post:" + postId + ":bot_count";
    }


    public static String botCooldown(Long botId, Long humanId) {
        return "cooldown:bot_" + botId + ":human_" + humanId;
    }


    // Notification Engine

    public static String pendingNotifications(Long userId) {
        return "user:" + userId + ":pending_notifs";
    }

    public static String notificationCooldown(Long userId) {
        return "notif_cooldown:user_" + userId;
    }


    public static final String PENDING_NOTIFS_PATTERN = "user:*:pending_notifs";
}
