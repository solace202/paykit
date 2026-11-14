package dev.paykit.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Background jobs: outbox relay, failed-event retries, grace-period checks. Tests switch it off and drive them. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "paykit.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
