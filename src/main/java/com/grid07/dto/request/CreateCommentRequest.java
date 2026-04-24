package com.grid07.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateCommentRequest {

    @NotNull(message = "authorId is required")
    private Long authorId;

    @NotNull(message = "authorType is required (USER or BOT)")
    private String authorType; // "USER" or "BOT"

    @NotBlank(message = "content must not be blank")
    @Size(max = 2000, message = "content must not exceed 2000 characters")
    private String content;

    //     Optional: ID of the parent comment for nested replies.


    private Long parentCommentId;
}
