package pl.allegro.tech.queuewaittime.netty.nettyserver

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.getBean
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.reactor.netty.NettyServerCustomizer
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner
import pl.allegro.tech.queuewaittime.netty.EventLoopLagProperties
import reactor.core.publisher.Mono
import reactor.netty.http.client.HttpClient
import reactor.netty.http.server.HttpServer
import reactor.netty.resources.LoopResources
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Supplier

internal class ReactorNettyQueueWaitTimeAutoConfigurationTest {
    private val baseContextRunner = ReactiveWebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ReactorNettyQueueWaitTimeAutoConfiguration::class.java))
    private val contextRunner = baseContextRunner
        .withBean(MeterRegistry::class.java, Supplier { SimpleMeterRegistry() })

    @ParameterizedTest
    @ValueSource(ints = [-1, 1])
    fun recordsLagAndCancelsSamplingWithoutStoppingServerEventLoops(selectorCount: Int) {
        val resources = if (selectorCount == -1) {
            LoopResources.create("queue-wait-test", 1, true)
        } else {
            LoopResources.create("queue-wait-test", selectorCount, 1, true)
        }
        val registry = SimpleMeterRegistry()
        val workerName = AtomicReference<String>()
        try {
            baseContextRunner
                .withInitializer { it.beanFactory.registerSingleton("meterRegistry", registry) }
                .run { context ->
                    val server = context.getBean<NettyServerCustomizer>()
                        .apply(HttpServer.create().host("127.0.0.1").port(0))
                        .runOn(resources, false)
                        .handle { _, response ->
                            workerName.set(Thread.currentThread().name)
                            Thread.sleep(100)
                            response.sendString(Mono.just("ok"))
                        }
                        .bindNow(Duration.ofSeconds(5))
                    try {
                        val group = resources.onServer(false)
                        await().atMost(Duration.ofSeconds(5)).untilAsserted {
                            assertThat(registry.find("event_loop.lag").tag("type", "server").timers()).isNotEmpty
                        }
                        val body = HttpClient.create().get().uri("http://127.0.0.1:${server.port()}")
                            .responseContent().aggregate().asString().block(Duration.ofSeconds(5))

                        assertThat(body).isEqualTo("ok")

                        await().atMost(Duration.ofSeconds(5)).untilAsserted {
                            val timers = registry.find("event_loop.lag")
                                .tag("type", "server").tag("executor", workerName.get()).timers()
                            assertThat(timers).isNotEmpty
                            assertThat(timers.maxOf { it.max(TimeUnit.MILLISECONDS) })
                                .isGreaterThanOrEqualTo(50.0)
                            timers.forEach {
                                assertThat(it.id.getTag("executor")).isNotBlank
                                assertThat(it.id.getTag("type")).isEqualTo("server")
                            }
                        }
                        assertThat(registry.find("custom.netty.eventloop.lag").timers()).isEmpty()
                        assertThat(registry.find("event.loop.lag").timers()).isEmpty()

                        context.close()

                        assertThat(registry.isClosed).isFalse()
                        assertThat(group.isShuttingDown).isFalse()
                        group.forEach { it.submit {}.syncUninterruptibly() }
                        val timers = registry.find("event_loop.lag").tag("type", "server").timers()
                        val count = timers.sumOf { it.count() }
                        await().during(Duration.ofMillis(100)).atMost(Duration.ofSeconds(5)).untilAsserted {
                            assertThat(timers.sumOf { it.count() }).isEqualTo(count)
                        }
                    } finally {
                        server.disposeNow()
                    }
                }
        } finally {
            try {
                resources.disposeLater(Duration.ZERO, Duration.ofSeconds(5)).block()
            } finally {
                registry.close()
            }
        }
    }

    @Test
    fun bindsTheSharedSamplingProperties() {
        contextRunner
            .withPropertyValues("allegro-tech.event-loop-lag.interval=25ms")
            .run { context ->
                assertThat(context).hasSingleBean(EventLoopLagProperties::class.java)
                assertThat(context.getBean<EventLoopLagProperties>().interval)
                    .isEqualTo(Duration.ofMillis(25))
            }
    }

    @Test
    fun canDisableServerMonitoring() {
        contextRunner
            .withPropertyValues("allegro-tech.event-loop-lag.enabled=false")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean(NettyServerCustomizer::class.java)
            }
    }

    @Test
    fun doesNotConfigureMonitoringWithoutARegistry() {
        baseContextRunner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).doesNotHaveBean(NettyServerCustomizer::class.java)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["reactor.netty", "org.springframework.boot.reactor.netty", "io.micrometer"])
    fun backsOffWhenAnOptionalDependencyIsMissing(packageName: String) {
        contextRunner
            .withClassLoader(FilteredClassLoader(packageName))
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean(NettyServerCustomizer::class.java)
            }
    }
}
