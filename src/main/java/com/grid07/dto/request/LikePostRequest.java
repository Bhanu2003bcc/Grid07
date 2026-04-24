package com.grid07.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class LikePostRequest {

    @NotNull(message = "userId is required")
    private Long userId;
}
