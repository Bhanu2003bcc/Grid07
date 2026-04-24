package com.grid07.controller;

import com.grid07.dto.request.CreateCommentRequest;
import com.grid07.dto.request.CreatePostRequest;
import com.grid07.dto.request.LikePostRequest;
import com.grid07.dto.response.ApiResponse;
import com.grid07.dto.response.CommentResponse;
import com.grid07.dto.response.PostResponse;
import com.grid07.service.CommentService;
import com.grid07.service.LikeService;
import com.grid07.service.PostService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/posts")
@RequiredArgsConstructor
@Slf4j
public class PostController {

    private final PostService postService;
    private final CommentService commentService;
    private final LikeService likeService;

    //  Create a new post (by User or Bot)

    @PostMapping
    public ResponseEntity<ApiResponse<PostResponse>> createPost(
            @Valid @RequestBody CreatePostRequest request) {

        PostResponse response = postService.createPost(request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Post created successfully.", response));
    }

    //  Fetch a post with its current virality score

    @GetMapping("/{postId}")
    public ResponseEntity<ApiResponse<PostResponse>> getPost(@PathVariable Long postId) {
        PostResponse response = postService.getPost(postId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    //  Add a comment — enforces all Redis guardrails for bots

    @PostMapping("/{postId}/comments")
    public ResponseEntity<ApiResponse<CommentResponse>> addComment(
            @PathVariable Long postId,
            @Valid @RequestBody CreateCommentRequest request) {

        CommentResponse response = commentService.addComment(postId, request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Comment added successfully.", response));
    }

    // humans only —> +20 virality points

    @PostMapping("/{postId}/like")
    public ResponseEntity<ApiResponse<PostResponse>> likePost(
            @PathVariable Long postId,
            @Valid @RequestBody LikePostRequest request) {

        PostResponse response = likeService.likePost(postId, request);
        return ResponseEntity.ok(ApiResponse.ok("Post liked successfully.", response));
    }
}
