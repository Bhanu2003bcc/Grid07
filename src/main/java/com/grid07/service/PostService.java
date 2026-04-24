package com.grid07.service;

import com.grid07.dto.request.CreatePostRequest;
import com.grid07.dto.response.PostResponse;
import com.grid07.entity.Post;
import com.grid07.entity.Post.AuthorType;
import com.grid07.exception.ResourceNotFoundException;
import com.grid07.repository.BotRepository;
import com.grid07.repository.PostRepository;
import com.grid07.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class PostService {

    private final PostRepository     postRepository;
    private final UserRepository     userRepository;
    private final BotRepository      botRepository;
    private final ViralityService    viralityService;


    @Transactional
    public PostResponse createPost(CreatePostRequest request) {
        AuthorType authorType = parseAuthorType(request.getAuthorType());
        validateAuthorExists(request.getAuthorId(), authorType);

        Post post = Post.builder()
                .authorId(request.getAuthorId())
                .authorType(authorType)
                .content(request.getContent())
                .build();

        Post saved = postRepository.save(post);
        log.info("[POST CREATED] id={} by {}:{}", saved.getId(), authorType, request.getAuthorId());

        return mapToResponse(saved);
    }


    //  Retrieves a post by ID

    @Transactional(readOnly = true)
    public PostResponse getPost(Long postId) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new ResourceNotFoundException("Post", postId));

        return mapToResponse(post);
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

    private PostResponse mapToResponse(Post post) {
        return PostResponse.builder()
                .id(post.getId())
                .authorId(post.getAuthorId())
                .authorType(post.getAuthorType().name())
                .content(post.getContent())
                .createdAt(post.getCreatedAt())
                .viralityScore(viralityService.getScore(post.getId()))
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
