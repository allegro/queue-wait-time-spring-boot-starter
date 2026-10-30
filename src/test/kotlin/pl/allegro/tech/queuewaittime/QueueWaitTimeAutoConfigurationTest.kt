package pl.allegro.tech.queuewaittime

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.context.annotation.ImportCandidates
import org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration
import org.springframework.boot.micrometer.metrics.autoconfigure.export.simple.SimpleMetricsExportAutoConfiguration
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner
import pl.allegro.tech.queuewaittime.netty.EventLoopLagProperties
import pl.allegro.tech.queuewaittime.netty.mongoclient.ReactiveMongoQueueWaitTimeAutoConfiguration
import pl.allegro.tech.queuewaittime.netty.nettyserver.ReactorNettyQueueWaitTimeAutoConfiguration
import pl.allegro.tech.queuewaittime.tomcat.TomcatQueueWaitTimeAutoConfiguration
import pl.allegro.tech.queuewaittime.webclient.WebClientQueueWaitTimeAutoConfiguration
import java.util.function.Supplier

internal class QueueWaitTimeAutoConfigurationTest {
    private val configurations = arrayOf(
        TomcatQueueWaitTimeAutoConfiguration::class.java,
        ReactorNettyQueueWaitTimeAutoConfiguration::class.java,
        ReactiveMongoQueueWaitTimeAutoConfiguration::class.java,
        WebClientQueueWaitTimeAutoConfiguration::class.java,
    )
    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(*configurations))

    @Test
    fun registersAllFourIntegrations() {
        val imports = ImportCandidates.load(AutoConfiguration::class.java, javaClass.classLoader)
            .filter { it.startsWith("pl.allegro.tech.queuewaittime.") }

        assertThat(imports).containsExactlyInAnyOrder(*configurations.map { it.name }.toTypedArray())
    }

    @Test
    fun startsWithoutAnyOptionalMonitoringDependencies() {
        contextRunner
            .withClassLoader(
                FilteredClassLoader(
                    "io.micrometer",
                    "io.netty",
                    "com.mongodb",
                    "reactor",
                    "org.springframework.boot.tomcat",
                    "org.springframework.boot.reactor.netty",
                    "org.springframework.boot.mongodb",
                    "org.springframework.boot.webclient",
                    "org.springframework.boot.http.client",
                    "org.springframework.web.reactive",
                )
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean("tomcatPlatformThreadsWebServerCustomizer")
                assertThat(context).doesNotHaveBean("tomcatVirtualThreadsWebServerCustomizer")
                assertThat(context).doesNotHaveBean("reactorNettyServerEventLoopLagCustomizer")
                assertThat(context).doesNotHaveBean("mongoEventLoopLagCustomizer")
                assertThat(context).doesNotHaveBean("webClientQueueWaitTimeCustomizer")
                assertThat(context).doesNotHaveBean("reactorClientHttpConnectorQueueWaitTimeCustomizer")
            }
    }

    @Test
    fun doesNotConfigureServerMonitoringInNonWebApplications() {
        contextRunner
            .withBean(MeterRegistry::class.java, Supplier { SimpleMeterRegistry() })
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean("tomcatPlatformThreadsWebServerCustomizer")
                assertThat(context).doesNotHaveBean("tomcatVirtualThreadsWebServerCustomizer")
                assertThat(context).doesNotHaveBean("reactorNettyServerEventLoopLagCustomizer")
                assertThat(context).hasBean("mongoEventLoopLagCustomizer")
                assertThat(context).hasBean("webClientQueueWaitTimeCustomizer")
                assertThat(context).hasBean("reactorClientHttpConnectorQueueWaitTimeCustomizer")
            }
    }

    @Test
    fun disablesBothEventLoopIntegrationsWithoutDisablingTomcat() {
        ReactiveWebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(*configurations))
            .withBean(MeterRegistry::class.java, Supplier { SimpleMeterRegistry() })
            .withPropertyValues("allegro-tech.event-loop-lag.enabled=false")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean("reactorNettyServerEventLoopLagCustomizer")
                assertThat(context).doesNotHaveBean("mongoEventLoopLagCustomizer")
                assertThat(context).hasBean("tomcatPlatformThreadsWebServerCustomizer")
                assertThat(context).hasBean("webClientQueueWaitTimeCustomizer")
            }
    }

    @Test
    fun enablesMonitoringAfterBootCreatesTheMeterRegistry() {
        ReactiveWebApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    *configurations,
                    MetricsAutoConfiguration::class.java,
                    CompositeMeterRegistryAutoConfiguration::class.java,
                    SimpleMetricsExportAutoConfiguration::class.java,
                )
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(MeterRegistry::class.java)
                assertThat(context).hasSingleBean(EventLoopLagProperties::class.java)
                assertThat(context).hasBean("tomcatPlatformThreadsWebServerCustomizer")
                assertThat(context).hasBean("reactorNettyServerEventLoopLagCustomizer")
                assertThat(context).hasBean("mongoEventLoopLagCustomizer")
                assertThat(context).hasBean("webClientQueueWaitTimeCustomizer")
                assertThat(context).hasBean("reactorClientHttpConnectorQueueWaitTimeCustomizer")
            }
    }
}
