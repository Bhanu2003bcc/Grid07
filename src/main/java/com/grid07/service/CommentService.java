package com.grid07.service;

import com.grid07.dto.request.CreateCommentRequest;
import com.grid07.dto.response.CommentResponse;
import com.grid07.entity.Comment;
import com.grid07.entity.Comment.AuthorType;
import com.grid07.entity.Post;
import com.grid07.exception.ResourceNotFoundException;
import com.grid07.repository.BotRepository;
import com.grid07.repository.CommentRepository;
import com.grid07.repository.PostRepository;
import com.grid07.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class CommentService {

    private final CommentRepository  commentRepository;
    private final PostRepository     postRepository;
    private final UserRepository     userRepository;
    private final BotRepository      botRepository;
    private final BotGuardService    botGuardService;
    private final ViralityService    viralityService;
    private final NotificationService notificationService;

    public CommentResponse addComment(Long postId, CreateCommentRequest request) {
        // 1: Validate post exists
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new ResourceNotFoundException("Post", postId));

        AuthorType authorType = parseAuthorType(request.getAuthorType());
        validateAuthorExists(request.getAuthorId(), authorType);

        //  2: Calculate depth
        int depth = resolveDepth(request.getParentCommentId());

        //  3 & 4: Bot-specific flow
        if (authorType == AuthorType.BOT) {
            return handleBotComment(post, request, depth);
        } else {
            return handleHumanComment(post, request, depth);
        }
    }


    private CommentResponse handleBotComment(Post post, CreateCommentRequest request, int depth) {
        Long botId   = request.getAuthorId();
        Long humanId = post.getAuthorId(); // The human who owns the post

        botGuardService.enforceAllGuardrails(post.getId(), botId, humanId, depth);

        //  DB write inside transaction
        Comment saved;
        try {
            saved = persistComment(post.getId(), request, AuthorType.BOT, depth);
        } catch (Exception dbException) {
            // !! Critical: rollback the Redis INCR if DB write fails
            botGuardService.rollbackBotCount(post.getId());
            log.error("[COMMENT] DB write failed after Redis guardrail passed. " +
                      "Rolled back bot_count for post:{}. Error: {}",
                      post.getId(), dbException.getMessage());
            throw dbException;
        }

        //  Set cooldown AFTER successful commit
        botGuardService.setBotCooldown(botId, humanId);

        // Update virality score
        viralityService.onBotReply(post.getId());

        // smart notification (queue or immediate)
        String botName = botRepository.findById(botId)
                .map(b -> b.getName())
                .orElse("Bot#" + botId);
        notificationService.handleBotInteractionNotification(humanId, botName, post.getId());

        log.info("[COMMENT CREATED] bot={} on post={} depth={}", botId, post.getId(), depth);
        return mapToResponse(saved);
    }

    private CommentResponse handleHumanComment(Post post, CreateCommentRequest request, int depth) {
        Comment saved = persistComment(post.getId(), request, AuthorType.USER, depth);
        viralityService.onHumanComment(post.getId());
        log.info("[COMMENT CREATED] user={} on post={} depth={}",
                request.getAuthorId(), post.getId(), depth);
        return mapToResponse(saved);
    }

    @Transactional
    protected Comment persistComment(Long postId, CreateCommentRequest req,
                                     AuthorType authorType, int depth) {
        Comment comment = Comment.builder()
                .postId(postId)
                .authorId(req.getAuthorId())
                .authorType(authorType)
                .content(req.getContent())
                .depthLevel(depth)
                .parentId(req.getParentCommentId())
                .build();
        return commentRepository.save(comment);
    }

    /**
     * Resolves depth level.
     * - Top-level comment (no parent) → depth = 1
     * - Reply to a comment → parent.depth + 1
     */
    private int resolveDepth(Long parentCommentId) {
        if (parentCommentId == null) {
            return 1;
        }
        Comment parent = commentRepository.findById(parentCommentId)
                .orElseThrow(() -> new ResourceNotFoundException("Parent comment", parentCommentId));
        return parent.getDepthLevel() + 1;
    }

    private void validateAuthorExists(Long authorId, AuthorType authorType) {
        if (authorType == AuthorType.USER) {
            if (!userRepository.existsById(authorId)) {
                throw new ResourceNotFoundException("User", authorId);
            }
        } else {
            if (!botRepository.existsById(authorId)) {
                throw new ResourceNotFoundException("Bot", authorId);
            }
        }
    }

    private CommentResponse mapToResponse(Comment comment) {
        return CommentResponse.builder()
                .id(comment.getId())
                .postId(comment.getPostId())
                .authorId(comment.getAuthorId())
                .authorType(comment.getAuthorType().name())
                .content(comment.getContent())
                .depthLevel(comment.getDepthLevel())
                .parentId(comment.getParentId())
                .createdAt(comment.getCreatedAt())
                .build();
    }

    private AuthorType parseAuthorType(String type) {
        try {
            return AuthorType.valueOf(type.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                "Invalid authorType '" + type + "'. Must be USER or BOT.");
        }
    }
}
