package com.grid07;

import com.grid07.entity.Bot;
import com.grid07.entity.Post;
import com.grid07.entity.User;
import com.grid07.repository.BotRepository;
import com.grid07.repository.CommentRepository;
import com.grid07.repository.PostRepository;
import com.grid07.repository.UserRepository;
import com.grid07.service.BotGuardService;
import com.grid07.service.CommentService;
import com.grid07.dto.request.CreateCommentRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class ConcurrencyTest {

    @Autowired private CommentService commentService;
    @Autowired private BotRepository botRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PostRepository postRepository;
    @Autowired private CommentRepository commentRepository;
    @Autowired private StringRedisTemplate redis;

    private static final int CONCURRENT_REQUESTS = 300;
    private static final int EXPECTED_CAP        = 100;

    private Long testPostId;
    private Long testUserId;

    @BeforeEach
    void setup() {
        commentRepository.deleteAll();
        postRepository.deleteAll();

        // Create a human user
        User user = userRepository.save(User.builder()
                .username("test_human_" + System.currentTimeMillis())
                .isPremium(false)
                .build());
        testUserId = user.getId();

        // Create a post -> user
        Post post = postRepository.save(Post.builder()
                .authorId(testUserId)
                .authorType(Post.AuthorType.USER)
                .content("This is a test post for concurrency testing.")
                .build());
        testPostId = post.getId();

        // Clear key from redis
        redis.delete("post:" + testPostId + ":bot_count");
        redis.delete("post:" + testPostId + ":virality_score");
    }

    @Test
    @DisplayName(" 200 concurrent bot requests must result in exactly 100 DB comments")
    void testHorizontalCapUnderConcurrentLoad() throws InterruptedException {

        List<Bot> bots = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            bots.add(botRepository.save(Bot.builder()
                    .name("stress_bot_" + i + "_" + System.currentTimeMillis())
                    .personaDescription("Stress test bot #" + i)
                    .build()));
        }

        // Counters
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger rejectedCount = new AtomicInteger(0);

        // Thread pool simulating 200 requests
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
        CountDownLatch startLatch = new CountDownLatch(1);  // synchronize start
        CountDownLatch doneLatch  = new CountDownLatch(CONCURRENT_REQUESTS);

        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            final Bot bot = bots.get(i);
            executor.submit(() -> {
                try {
                    startLatch.await(); // wait for all threads to be ready

                    CreateCommentRequest req = new CreateCommentRequest();
                    req.setAuthorId(bot.getId());
                    req.setAuthorType("BOT");
                    req.setContent("Concurrent bot comment from " + bot.getName());
                    req.setParentCommentId(null); // top-level

                    commentService.addComment(testPostId, req);
                    successCount.incrementAndGet();

                } catch (Exception e) {
                    rejectedCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        long dbCommentCount = commentRepository.countByPostIdAndAuthorType(
                testPostId, com.grid07.entity.Comment.AuthorType.BOT);

        System.out.println("\n=CONCURRENCY TEST RESULTS =");
        System.out.println("Total requests fired  : " + CONCURRENT_REQUESTS);
        System.out.println("Successful (allowed)  : " + successCount.get());
        System.out.println("Rejected (429)        : " + rejectedCount.get());
        System.out.println("DB bot comment count  : " + dbCommentCount);
        System.out.println("Redis bot_count key   : " +
                redis.opsForValue().get("post:" + testPostId + ":bot_count"));
        System.out.println("==\n");

        assertThat(dbCommentCount)
                .as("Database must contain  %d bot comments, not more", EXPECTED_CAP)
                .isEqualTo(EXPECTED_CAP);

        assertThat(successCount.get())
                .as("%d requests should have succeeded", EXPECTED_CAP)
                .isEqualTo(EXPECTED_CAP);

        assertThat(rejectedCount.get())
                .as(" %d requests should have been rejected", CONCURRENT_REQUESTS - EXPECTED_CAP)
                .isEqualTo(CONCURRENT_REQUESTS - EXPECTED_CAP);
    }

    @Test
    @DisplayName("Bot cooldown must block second interaction within 10 minutes")
    void testBotCooldownCap() {
        Bot bot = botRepository.save(Bot.builder()
                .name("cooldown_bot_" + System.currentTimeMillis())
                .personaDescription("Cooldown test bot")
                .build());

        CreateCommentRequest req = new CreateCommentRequest();
        req.setAuthorId(bot.getId());
        req.setAuthorType("BOT");
        req.setContent("First comment from cooldown bot");

        commentService.addComment(testPostId, req);

        // 2nd interaction should be blocked by cooldown
        org.junit.jupiter.api.Assertions.assertThrows(
                com.grid07.exception.GuardrailException.class,
                () -> commentService.addComment(testPostId, req),
                "Second bot interaction within cooldown window must be rejected"
        );
    }

    @Test
    @DisplayName("Comment depth beyond 20 levels must be rejected")
    void testVerticalCapEnforcement() throws Exception {
        User user = userRepository.findById(testUserId).orElseThrow();
        Bot bot = botRepository.save(Bot.builder()
                .name("depth_bot_" + System.currentTimeMillis())
                .personaDescription("Depth test bot")
                .build());
        // depth
        Long parentId = null;
        for (int d = 1; d <= 20; d++) {
            CreateCommentRequest humanReq = new CreateCommentRequest();
            humanReq.setAuthorId(testUserId);
            humanReq.setAuthorType("USER");
            humanReq.setContent("Human comment at depth " + d);
            humanReq.setParentCommentId(parentId);
            var resp = commentService.addComment(testPostId, humanReq);
            parentId = resp.getId();
        }

        // 21st time
        final Long lastParentId = parentId;
        CreateCommentRequest botReq = new CreateCommentRequest();
        botReq.setAuthorId(bot.getId());
        botReq.setAuthorType("BOT");
        botReq.setContent("Bot reply at depth 21 — must be blocked");
        botReq.setParentCommentId(lastParentId);

        org.junit.jupiter.api.Assertions.assertThrows(
                com.grid07.exception.GuardrailException.class,
                () -> commentService.addComment(testPostId, botReq),
                "Comment at depth 21 must be rejected by vertical cap"
        );
    }
}
