package dev.paykit.config;

import com.stripe.StripeClient;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration(proxyBeanMethods = false)
public class StripeConfig {

    @Bean
    StripeClient stripeClient(PayKitProperties props) {
        StripeClient.StripeClientBuilder builder = StripeClient.builder()
                .setApiKey(props.stripe().apiKey())
                .setMaxNetworkRetries(2)          // the SDK retries with the same Idempotency-Key
                .setConnectTimeout(10_000)
                .setReadTimeout(30_000);
        if (StringUtils.hasText(props.stripe().apiBase())) {
            builder.setApiBase(props.stripe().apiBase());   // stripe-mock
        }
        return builder.build();
    }
}
