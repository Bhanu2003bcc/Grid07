package com.grid07.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CommentResponse {
    private Long id;
    private Long postId;
    private Long authorId;
    private String authorType;
    private String content;
    private Integer depthLevel;
    private Long parentId;
    private LocalDateTime createdAt;
}
