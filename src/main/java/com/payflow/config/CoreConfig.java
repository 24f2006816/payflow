package com.payflow.config;

import com.payflow.domain.payment.FeeCalculator;
import com.payflow.domain.retry.RetryBackoff;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Clock;
import java.util.SplittableRandom;

@Configuration(proxyBeanMethods = false)
public class CoreConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    FeeCalculator feeCalculator(PayflowProperties props) {
        return new FeeCalculator(props.fees().bps());
    }

    @Bean
    RetryBackoff webhookBackoff(PayflowProperties props) {
        var w = props.webhooks();
        return new RetryBackoff(w.baseBackoff(), w.maxBackoff(), w.maxAttempts(), new SplittableRandom());
    }

    /** HTTP client for outbound webhooks: strict timeouts, no redirects (redirects are an SSRF vector). */
    @Bean
    RestClient webhookRestClient(PayflowProperties props) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(props.webhooks().connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(props.webhooks().readTimeout());
        return RestClient.builder().requestFactory(factory).build();
    }

    @Bean
    NewTopic paymentEventsTopic(PayflowProperties props) {
        // 6 partitions: events are keyed by aggregate id, so per-payment ordering is preserved.
        return TopicBuilder.name(props.outbox().topic()).partitions(6).replicas(1).build();
    }

    @Bean
    NewTopic paymentEventsDeadLetterTopic(PayflowProperties props) {
        return TopicBuilder.name(props.outbox().topic() + ".DLT").partitions(1).replicas(1).build();
    }

    @Bean
    OpenAPI payflowOpenApi() {
        return new OpenAPI()
                .info(new Info().title("PayFlow Payments API").version("v1")
                        .description("Double-entry ledger backed payments: top-ups, authorize/capture/cancel/refund, "
                                + "idempotent writes (Idempotency-Key header) and signed webhooks."))
                .components(new Components().addSecuritySchemes("bearerAuth",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
}
