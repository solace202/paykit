package dev.paykit.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The JSON API and the admin pages are meant to be called by your own backend / operators, so they sit behind
 * HTTP Basic. The Stripe webhook is public but every request must carry a valid {@code Stripe-Signature}.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.ignoringRequestMatchers("/webhooks/**", "/api/**", "/demo/**", "/checkout/**"))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/webhooks/stripe", "/", "/pricing", "/checkout/**", "/account/**",
                                "/demo/**", "/css/**", "/api/plans", "/swagger-ui.html", "/swagger-ui/**",
                                "/v3/api-docs/**", "/actuator/health/**", "/error").permitAll()
                        .requestMatchers("/admin/**", "/api/**").hasRole("ADMIN")
                        .anyRequest().denyAll())
                .httpBasic(Customizer.withDefaults());
        return http.build();
    }

    @Bean
    UserDetailsService users(PayKitProperties props) {
        PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        return new InMemoryUserDetailsManager(User.withUsername(props.admin().username())
                .password(encoder.encode(props.admin().password())).roles("ADMIN").build());
    }
}
