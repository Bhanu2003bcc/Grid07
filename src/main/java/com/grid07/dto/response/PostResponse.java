package com.grid07.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PostResponse {
    private Long id;
    private Long authorId;
    private String authorType;
    private String content;
    private LocalDateTime createdAt;
    private Long viralityScore;
    private Long likeCount;
}
