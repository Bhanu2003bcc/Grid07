package com.grid07.exception;

public class DuplicateLikeException extends RuntimeException {
    public DuplicateLikeException(Long postId, Long userId) {
        super("User " + userId + " has already liked post " + postId);
    }
}
