package com.grid07.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.guardrail")
@Getter
@Setter
public class GuardrailProperties {

    private int botReplyCap = 100;

    private int maxCommentDepth = 20;

    // Bot to human cooldown (10 min).
    private long botCooldownSeconds = 600;

    // Notification cooldown (15 min).
    private long notificationCooldownSeconds = 900;
}
