package dev.paykit.outbox;

import java.time.Duration;

import dev.paykit.config.PayKitProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class NotificationSenderConfig {

    private static final Logger log = LoggerFactory.getLogger("paykit.notifications");

    /** Default: log the notification, or POST it as JSON to NOTIFY_WEBHOOK_URL (n8n, Zapier, a mail service...). */
    @Bean
    @ConditionalOnMissingBean
    NotificationSender notificationSender(PayKitProperties props) {
        String url = props.outbox().webhookUrl();
        if (!StringUtils.hasText(url)) {
            return m -> log.info("notify [{}] to={} subject=\"{}\"", m.topic(), m.payload().get("to"),
                    m.payload().get("subject"));
        }
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(Duration.ofSeconds(5));
        rf.setReadTimeout(Duration.ofSeconds(10));
        RestClient http = RestClient.builder().requestFactory(rf).build();
        return m -> http.post().uri(url).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", m.dedupKey())
                .body(java.util.Map.of("id", m.id(), "topic", m.topic(), "payload", m.payload()))
                .retrieve().toBodilessEntity();
    }
}
