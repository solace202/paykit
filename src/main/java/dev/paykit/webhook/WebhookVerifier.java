package dev.paykit.webhook;

import com.stripe.exception.SignatureVerificationException;
import com.stripe.net.Webhook;
import dev.paykit.config.PayKitProperties;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Checks the {@code Stripe-Signature} header (HMAC-SHA256 of {@code timestamp.payload} with the endpoint secret)
 * with the official SDK, including the replay-protection tolerance on the timestamp.
 */
@Component
public class WebhookVerifier {

    private final PayKitProperties props;

    public WebhookVerifier(PayKitProperties props) {
        this.props = props;
    }

    public void verify(String payload, String signatureHeader) {
        if (!StringUtils.hasText(signatureHeader)) {
            throw new InvalidSignatureException("Missing Stripe-Signature header");
        }
        try {
            Webhook.Signature.verifyHeader(payload, signatureHeader, props.stripe().webhookSecret(),
                    props.stripe().webhookTolerance().toSeconds());
        } catch (SignatureVerificationException e) {
            throw new InvalidSignatureException(e.getMessage());
        }
    }
}
