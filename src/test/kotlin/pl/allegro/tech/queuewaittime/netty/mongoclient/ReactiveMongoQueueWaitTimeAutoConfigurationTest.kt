package pl.allegro.tech.queuewaittime.netty.mongoclient

import com.mongodb.MongoClientSettings
import com.mongodb.connection.NettyTransportSettings
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.netty.channel.EventLoopGroup
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.getBean
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.mongodb.autoconfigure.MongoClientSettingsBuilderCustomizer
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import pl.allegro.tech.queuewaittime.netty.EventLoopLagProperties
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.function.Supplier

internal class ReactiveMongoQueueWaitTimeAutoConfigurationTest {
    private val baseContextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ReactiveMongoQueueWaitTimeAutoConfiguration::class.java))
    private val contextRunner = baseContextRunner
        .withBean(MeterRegistry::class.java, Supplier { SimpleMeterRegistry() })

    @Test
    fun recordsSchedulingLagReusesTheGroupAndShutsItDown() {
        lateinit var group: EventLoopGroup

        contextRunner.run { context ->
            val customizer = context.getBean<MongoClientSettingsBuilderCustomizer>()
            val builder = MongoClientSettings.builder()
            customizer.customize(builder)
            group = (builder.build().transportSettings as NettyTransportSettings).eventLoopGroup

            val anotherBuilder = MongoClientSettings.builder()
            customizer.customize(anotherBuilder)
            assertThat((anotherBuilder.build().transportSettings as NettyTransportSettings).eventLoopGroup)
                .isSameAs(group)

            group.next().submit { Thread.sleep(100) }.syncUninterruptibly()

            await().atMost(Duration.ofSeconds(5)).untilAsserted {
                val timers = context.getBean<MeterRegistry>()
                    .find("event_loop.lag").tag("type", "mongo").timers()
                assertThat(timers).isNotEmpty
                assertThat(context.getBean<MeterRegistry>().find("eventLoop").timers()).isEmpty()
                assertThat(context.getBean<MeterRegistry>().find("event.loop.lag").timers()).isEmpty()
                assertThat(timers.maxOf { it.max(TimeUnit.MILLISECONDS) }).isGreaterThanOrEqualTo(50.0)
                timers.forEach {
                    assertThat(it.id.getTag("executor")).isNotBlank
                    assertThat(it.id.getTag("type")).isEqualTo("mongo")
                }
            }
        }

        assertThat(group.isTerminated).isTrue()
    }

    @Test
    fun bindsTheSharedSamplingProperties() {
        contextRunner
            .withPropertyValues("allegro-tech.event-loop-lag.interval=25ms")
            .run { context ->
                assertThat(context.getBean<EventLoopLagProperties>().interval)
                    .isEqualTo(Duration.ofMillis(25))
            }
    }

    @Test
    fun canDisableMongoMonitoring() {
        contextRunner
            .withPropertyValues("allegro-tech.event-loop-lag.enabled=false")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean(MongoClientSettingsBuilderCustomizer::class.java)
            }
    }

    @Test
    fun doesNotConfigureMonitoringWithoutARegistry() {
        baseContextRunner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).doesNotHaveBean(MongoClientSettingsBuilderCustomizer::class.java)
        }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "io.micrometer.core.instrument",
            "io.netty",
            "io.netty.handler",
            "com.mongodb.reactivestreams",
            "reactor.core.publisher",
            "org.springframework.boot.mongodb",
        ]
    )
    fun backsOffWhenAnOptionalDependencyIsMissing(packageName: String) {
        contextRunner
            .withClassLoader(FilteredClassLoader(packageName))
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean("mongoEventLoopLagCustomizer")
            }
    }

    @Test
    fun leavesCustomMongoSettingsUntouched() {
        val settings = MongoClientSettings.builder().build()

        contextRunner
            .withBean(MongoClientSettings::class.java, Supplier { settings })
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBean<MongoClientSettings>()).isSameAs(settings)
                assertThat(settings.transportSettings).isNull()
                assertThat(context).doesNotHaveBean(MongoClientSettingsBuilderCustomizer::class.java)
            }
    }
}
