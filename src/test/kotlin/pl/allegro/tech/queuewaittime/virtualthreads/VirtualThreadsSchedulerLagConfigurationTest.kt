package pl.allegro.tech.queuewaittime.virtualthreads

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.getBean
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration
import org.springframework.boot.micrometer.metrics.autoconfigure.export.simple.SimpleMetricsExportAutoConfiguration
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import pl.allegro.tech.queuewaittime.schedulerlag.SchedulerLagProperties
import pl.allegro.tech.queuewaittime.schedulerlag.SchedulerLagPropertiesConfiguration
import java.time.Duration

internal class VirtualThreadsSchedulerLagConfigurationTest {
    private val registry = SimpleMeterRegistry()
    private val baseContextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                VirtualThreadsSchedulerLagConfiguration::class.java,
                SchedulerLagPropertiesConfiguration::class.java,
            )
        )
    private val contextRunner = baseContextRunner
        .withInitializer { it.beanFactory.registerSingleton("meterRegistry", registry) }
    private val virtualContextRunner = contextRunner
        .withPropertyValues("spring.threads.virtual.enabled=true")
    private lateinit var monitor: VirtualThreadSchedulerLagMonitor

    @AfterEach
    fun closeResources() {
        try {
            if (::monitor.isInitialized) {
                monitor.recordingThreadProbe.interrupt()
                monitor.recordingThreadProbe.join(Duration.ofSeconds(5))
            }
        } finally {
            registry.close()
        }
    }

    @Test
    fun startsSamplingInANonWebApplicationAndClosesTheProbe() {
        lateinit var counters: Collection<Counter>

        virtualContextRunner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).hasSingleBean(VirtualThreadSchedulerLagMonitor::class.java)
            monitor = context.getBean<VirtualThreadSchedulerLagMonitor>()
            assertThat(monitor.recordingThreadProbe.isVirtual).isTrue()
            assertThat(monitor.recordingThreadProbe.isAlive).isTrue()
            counters = sampleCounters(registry)
            await().atMost(Duration.ofSeconds(5)).untilAsserted {
                assertThat(counters.sumOf { it.count() }).isPositive()
            }
            val initialSampleCount = counters.sumOf { it.count() }
            await().atMost(Duration.ofSeconds(5)).untilAsserted {
                assertThat(counters.sumOf { it.count() }).isGreaterThan(initialSampleCount)
            }
        }

        assertThat(monitor.recordingThreadProbe.isAlive).isFalse()
        assertThat(registry.isClosed).isFalse()
        val sampleCountAfterClose = counters.sumOf { it.count() }
        await().during(Duration.ofMillis(100)).atMost(Duration.ofSeconds(5)).untilAsserted {
            assertThat(counters.sumOf { it.count() }).isEqualTo(sampleCountAfterClose)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun doesNotStartWhenVirtualThreadsAreNotEnabled(explicitlyDisabled: Boolean) {
        val runner = if (explicitlyDisabled) {
            contextRunner.withPropertyValues("spring.threads.virtual.enabled=false")
        } else {
            contextRunner
        }

        runner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).doesNotHaveBean(VirtualThreadSchedulerLagMonitor::class.java)
            assertThat(registry.find("scheduler.sample").counters()).isEmpty()
        }
    }

    @Test
    fun doesNotConfigureMonitoringWithoutARegistry() {
        baseContextRunner
            .withPropertyValues("spring.threads.virtual.enabled=true")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean(MeterRegistry::class.java)
                assertThat(context).doesNotHaveBean(VirtualThreadSchedulerLagMonitor::class.java)
            }
    }

    @Test
    fun backsOffWithoutMicrometer() {
        baseContextRunner
            .withPropertyValues("spring.threads.virtual.enabled=true")
            .withClassLoader(FilteredClassLoader("io.micrometer"))
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean("virtualThreadSchedulerLagMonitor")
            }
    }

    @Test
    fun canDisableMonitoring() {
        virtualContextRunner
            .withPropertyValues("allegro-tech.scheduler-lag.enabled=false")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean(VirtualThreadSchedulerLagMonitor::class.java)
                assertThat(registry.find("scheduler.sample").counters()).isEmpty()
            }
    }

    @Test
    fun canEnableMonitoringExplicitly() {
        virtualContextRunner
            .withPropertyValues("allegro-tech.scheduler-lag.enabled=true")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(VirtualThreadSchedulerLagMonitor::class.java)
                monitor = context.getBean<VirtualThreadSchedulerLagMonitor>()
                assertThat(monitor.recordingThreadProbe.isAlive).isTrue()
            }
    }

    @Test
    fun usesTheConfiguredSamplingInterval() {
        virtualContextRunner
            .withPropertyValues("allegro-tech.scheduler-lag.interval=5s")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(VirtualThreadSchedulerLagMonitor::class.java)
                assertThat(context.getBean<SchedulerLagProperties>().interval).isEqualTo(Duration.ofSeconds(5))
                monitor = context.getBean<VirtualThreadSchedulerLagMonitor>()
                assertThat(monitor.recordingThreadProbe.isAlive).isTrue()
                val counters = sampleCounters(registry)
                await().during(Duration.ofMillis(100)).atMost(Duration.ofSeconds(5)).untilAsserted {
                    assertThat(counters.sumOf { it.count() }).isZero()
                }
            }
    }

    @Test
    fun enablesMonitoringAfterBootCreatesTheMeterRegistry() {
        baseContextRunner
            .withConfiguration(
                AutoConfigurations.of(
                    MetricsAutoConfiguration::class.java,
                    CompositeMeterRegistryAutoConfiguration::class.java,
                    SimpleMetricsExportAutoConfiguration::class.java,
                )
            )
            .withPropertyValues("spring.threads.virtual.enabled=true")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(MeterRegistry::class.java)
                assertThat(context).hasSingleBean(VirtualThreadSchedulerLagMonitor::class.java)
                monitor = context.getBean<VirtualThreadSchedulerLagMonitor>()
                val bootRegistry = context.getBean<MeterRegistry>()
                await().atMost(Duration.ofSeconds(5)).untilAsserted {
                    assertThat(sampleCounters(bootRegistry).sumOf { it.count() }).isPositive()
                }
            }
    }

    private fun sampleCounters(meterRegistry: MeterRegistry) = meterRegistry.get("scheduler.sample")
        .tag("executor", "VirtualThreadDefaultScheduler")
        .tag("type", "VirtualThreadDefaultScheduler")
        .counters()
}
