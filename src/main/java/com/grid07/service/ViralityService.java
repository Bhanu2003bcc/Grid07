package com.grid07.service;

import com.grid07.config.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class ViralityService {

    private final StringRedisTemplate redis;

    private static final long BOT_REPLY_POINTS    = 1L;
    private static final long HUMAN_LIKE_POINTS   = 20L;
    private static final long HUMAN_COMMENT_POINTS = 50L;

    public void onBotReply(Long postId) {
        addScore(postId, BOT_REPLY_POINTS, "BOT_REPLY");
    }

    public void onHumanLike(Long postId) {
        addScore(postId, HUMAN_LIKE_POINTS, "HUMAN_LIKE");
    }

    public void onHumanComment(Long postId) {
        addScore(postId, HUMAN_COMMENT_POINTS, "HUMAN_COMMENT");
    }

    public long getScore(Long postId) {
        String value = redis.opsForValue().get(RedisKeys.viralityScore(postId));
        if (value == null) return 0L;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            log.error("[VIRALITY] Corrupt score key for post:{}", postId);
            return 0L;
        }
    }

    private void addScore(Long postId, long points, String eventType) {
        String key = RedisKeys.viralityScore(postId);
        Long newScore = redis.opsForValue().increment(key, points);
        log.info("[VIRALITY] post={} event={} +{} pts → total={}",
                postId, eventType, points, newScore);
    }
}
