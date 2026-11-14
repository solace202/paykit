package dev.paykit.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    @Bean
    OpenAPI payKitOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("PayKit API")
                        .version("0.1.0")
                        .description("""
                                Stripe subscription billing backend.

                                1. `POST /api/accounts` creates a customer (and the Stripe Customer).
                                2. `POST /api/accounts/{id}/checkout` returns a Stripe Checkout URL (trial applied once per account).
                                3. Stripe calls `POST /webhooks/stripe`; verified events update subscriptions, entitlements and dunning.
                                4. Your app asks `GET /api/accounts/{id}/entitlements` what the customer may use.

                                Click **Authorize** and use the admin credentials (default `admin` / `change-me`).""")
                        .license(new License().name("MIT")))
                .components(new Components().addSecuritySchemes("basic",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("basic")))
                .addSecurityItem(new SecurityRequirement().addList("basic"));
    }
}
