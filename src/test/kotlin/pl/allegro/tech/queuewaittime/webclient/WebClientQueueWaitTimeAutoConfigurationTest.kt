package pl.allegro.tech.queuewaittime.webclient

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.getBean
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration
import org.springframework.boot.http.client.autoconfigure.reactive.ClientHttpConnectorBuilderCustomizer
import org.springframework.boot.http.client.autoconfigure.reactive.ReactiveHttpClientAutoConfiguration
import org.springframework.boot.http.client.reactive.ClientHttpConnectorBuilder
import org.springframework.boot.http.client.reactive.JdkClientHttpConnectorBuilder
import org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration
import org.springframework.boot.micrometer.metrics.autoconfigure.export.simple.SimpleMetricsExportAutoConfiguration
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.webclient.WebClientCustomizer
import org.springframework.boot.webclient.autoconfigure.WebClientAutoConfiguration
import org.springframework.http.client.ReactorResourceFactory
import org.springframework.http.client.reactive.ClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import reactor.netty.http.server.HttpServer
import java.time.Duration
import java.util.function.Supplier

internal class WebClientQueueWaitTimeAutoConfigurationTest {
    private val baseContextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(WebClientQueueWaitTimeAutoConfiguration::class.java))
    private val contextRunner = baseContextRunner
        .withBean(MeterRegistry::class.java, Supplier { SimpleMeterRegistry() })
    private val bootClientConfigurations = AutoConfigurations.of(
        HttpClientAutoConfiguration::class.java,
        ReactiveHttpClientAutoConfiguration::class.java,
        WebClientAutoConfiguration::class.java,
    )

    @Test
    fun registersOnlyCustomizersWithoutCreatingClientsOrResources() {
        contextRunner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).hasSingleBean(WebClientCustomizer::class.java)
            assertThat(context).hasSingleBean(ClientHttpConnectorBuilderCustomizer::class.java)
            assertThat(context).doesNotHaveBean(WebClient::class.java)
            assertThat(context).doesNotHaveBean(WebClient.Builder::class.java)
            assertThat(context).doesNotHaveBean(ClientHttpConnector::class.java)
            assertThat(context).doesNotHaveBean(ClientHttpConnectorBuilder::class.java)
            assertThat(context).doesNotHaveBean(ReactorResourceFactory::class.java)
        }
    }

    @Test
    fun doesNotConfigureMonitoringWithoutARegistry() {
        baseContextRunner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).doesNotHaveBean(WebClientCustomizer::class.java)
            assertThat(context).doesNotHaveBean(ClientHttpConnectorBuilderCustomizer::class.java)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "io.micrometer",
        "org.springframework.web.reactive",
        "org.springframework.boot.webclient",
        "org.springframework.boot.http.client",
        "reactor.netty",
    ])
    fun backsOffWhenAnOptionalDependencyIsMissing(packageName: String) {
        contextRunner
            .withClassLoader(FilteredClassLoader(packageName))
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean("webClientQueueWaitTimeCustomizer")
                assertThat(context).doesNotHaveBean("reactorClientHttpConnectorQueueWaitTimeCustomizer")
            }
    }

    @Test
    fun customizesBootsWebClientInANonWebApplication() {
        val server = HttpServer.create().host("127.0.0.1").port(0)
            .handle { _, response -> response.sendString(Mono.just("ok")) }
            .bindNow(Duration.ofSeconds(5))
        try {
            contextRunner
                .withConfiguration(bootClientConfigurations)
                .withPropertyValues("spring.http.clients.reactive.connector=reactor")
                .run { context ->
                    assertThat(context).hasNotFailed()
                    val client = context.getBean<WebClient.Builder>().build()

                    val body = client.get().uri("http://127.0.0.1:${server.port()}")
                        .retrieve().bodyToMono(String::class.java).block(Duration.ofSeconds(5))

                    assertThat(body).isEqualTo("ok")
                    val timer = context.getBean<MeterRegistry>().find("webclient_queue_wait_time").timer()
                    assertThat(timer).isNotNull
                    assertThat(timer!!.count()).isEqualTo(1)
                }
        } finally {
            server.disposeNow()
        }
    }

    @Test
    fun supportsBootsJdkConnectorWithoutApplyingReactorInstrumentation() {
        val server = HttpServer.create().host("127.0.0.1").port(0)
            .handle { _, response -> response.sendString(Mono.just("ok")) }
            .bindNow(Duration.ofSeconds(5))
        try {
            contextRunner
                .withConfiguration(bootClientConfigurations)
                .withPropertyValues("spring.http.clients.reactive.connector=jdk")
                .run { context ->
                    assertThat(context).hasNotFailed()
                    assertThat(context).hasBean("reactorClientHttpConnectorQueueWaitTimeCustomizer")
                    assertThat(context.getBean<ClientHttpConnectorBuilder<*>>())
                        .isInstanceOf(JdkClientHttpConnectorBuilder::class.java)
                    val client = context.getBean<WebClient.Builder>().build()

                    val body = client.get().uri("http://127.0.0.1:${server.port()}")
                        .retrieve().bodyToMono(String::class.java).block(Duration.ofSeconds(5))

                    assertThat(body).isEqualTo("ok")
                    assertThat(context.getBean<MeterRegistry>()
                        .find("webclient_queue_wait_time").timers().sumOf { it.count() }).isZero()
                }
        } finally {
            server.disposeNow()
        }
    }

    @Test
    fun enablesMonitoringAfterBootCreatesTheMeterRegistry() {
        baseContextRunner
            .withConfiguration(AutoConfigurations.of(
                MetricsAutoConfiguration::class.java,
                CompositeMeterRegistryAutoConfiguration::class.java,
                SimpleMetricsExportAutoConfiguration::class.java,
            ))
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(MeterRegistry::class.java)
                assertThat(context).hasBean("webClientQueueWaitTimeCustomizer")
                assertThat(context).hasBean("reactorClientHttpConnectorQueueWaitTimeCustomizer")
            }
    }
}
