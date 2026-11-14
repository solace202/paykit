package dev.paykit.demo;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.paykit.config.PayKitProperties;
import dev.paykit.web.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Demo only: delivers signed events to the app's own webhook endpoint over HTTP, the way Stripe would. */
@Component
@ConditionalOnProperty(name = "paykit.demo.enabled", havingValue = "true")
public class DemoWebhookEmitter {

    private static final Logger log = LoggerFactory.getLogger(DemoWebhookEmitter.class);

    private final PayKitProperties props;
    private final Environment env;
    private final RestClient http;

    public DemoWebhookEmitter(PayKitProperties props, Environment env) {
        this.props = props;
        this.env = env;
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(Duration.ofSeconds(5));
        rf.setReadTimeout(Duration.ofSeconds(30));
        this.http = RestClient.builder().requestFactory(rf).build();
    }

    public Map<String, Object> send(ObjectNode event) {
        return deliver(Json.write(event));
    }

    /** Re-delivers an exact payload (same event id), freshly signed: what Stripe does on a retry. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> deliver(String payload) {
        String sig = WebhookSigner.header(payload, props.stripe().webhookSecret(), Instant.now());
        Map<String, Object> receipt = http.post().uri(env.resolvePlaceholders(props.demo().webhookTarget()))
                .contentType(MediaType.APPLICATION_JSON).header("Stripe-Signature", sig).body(payload)
                .retrieve().body(Map.class);
        log.info("demo webhook -> {}", receipt);
        return receipt;
    }
}
