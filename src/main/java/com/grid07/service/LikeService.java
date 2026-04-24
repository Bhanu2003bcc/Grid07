package com.grid07.service;

import com.grid07.dto.request.LikePostRequest;
import com.grid07.dto.response.PostResponse;
import com.grid07.entity.Post;
import com.grid07.entity.PostLike;
import com.grid07.exception.DuplicateLikeException;
import com.grid07.exception.ResourceNotFoundException;
import com.grid07.repository.PostLikeRepository;
import com.grid07.repository.PostRepository;
import com.grid07.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class LikeService {

    private final PostRepository     postRepository;
    private final PostLikeRepository postLikeRepository;
    private final UserRepository     userRepository;
    private final ViralityService    viralityService;

    @Transactional
    public PostResponse likePost(Long postId, LikePostRequest request) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new ResourceNotFoundException("Post", postId));

        if (!userRepository.existsById(request.getUserId())) {
            throw new ResourceNotFoundException("User", request.getUserId());
        }

        if (postLikeRepository.existsByPostIdAndUserId(postId, request.getUserId())) {
            throw new DuplicateLikeException(postId, request.getUserId());
        }

        PostLike like = PostLike.builder()
                .postId(postId)
                .userId(request.getUserId())
                .build();
        postLikeRepository.save(like);

        // Update virality score in Redis
        viralityService.onHumanLike(postId);

        log.info("[LIKE] user={} liked post={}", request.getUserId(), postId);

        return PostResponse.builder()
                .id(post.getId())
                .authorId(post.getAuthorId())
                .authorType(post.getAuthorType().name())
                .content(post.getContent())
                .createdAt(post.getCreatedAt())
                .viralityScore(viralityService.getScore(postId))
                .likeCount(postLikeRepository.countByPostId(postId))
                .build();
    }
}
