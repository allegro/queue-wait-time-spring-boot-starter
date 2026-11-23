package pl.allegro.tech.queuewaittime.micrometer

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics
import org.assertj.core.api.Assertions.assertThat
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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class ExecutorIdleRenameAutoConfigurationTest {
    private val executor = Executors.newFixedThreadPool(1)
    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                ExecutorIdleRenameAutoConfiguration::class.java,
                MetricsAutoConfiguration::class.java,
                CompositeMeterRegistryAutoConfiguration::class.java,
                SimpleMetricsExportAutoConfiguration::class.java,
            )
        )

    @AfterEach
    fun closeExecutor() {
        executor.shutdownNow()
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue()
    }

    @Test
    fun renamesExecutorIdleByDefault() {
        contextRunner.run { context ->
            assertThat(context).hasNotFailed()
            recordTaskAndAssertMetricName(
                context.getBean<MeterRegistry>(),
                expectedName = "executor.queue.wait.time",
                absentName = "executor.idle",
            )
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun respectsRenameFlag(enabled: Boolean) {
        contextRunner
            .withPropertyValues("allegro-tech.rename-executor-idle-to-executor-queue-wait-time=$enabled")
            .run { context ->
                assertThat(context).hasNotFailed()
                recordTaskAndAssertMetricName(
                    context.getBean<MeterRegistry>(),
                    expectedName = if (enabled) "executor.queue.wait.time" else "executor.idle",
                    absentName = if (enabled) "executor.idle" else "executor.queue.wait.time",
                )
            }
    }

    @Test
    fun backsOffWithoutMicrometer() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ExecutorIdleRenameAutoConfiguration::class.java))
            .withClassLoader(FilteredClassLoader("io.micrometer"))
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean("executorIdleRenameMeterFilter")
            }
    }

    private fun recordTaskAndAssertMetricName(registry: MeterRegistry, expectedName: String, absentName: String) {
        val executorName = "test-executor"
        val tags = Tags.of("source", "test")
        val monitoredExecutor = ExecutorServiceMetrics.monitor(registry, executor, executorName, tags)

        monitoredExecutor.submit {}.get(5, TimeUnit.SECONDS)

        val timer = registry.find(expectedName).tag("name", executorName).tags(tags).timer()
        assertThat(timer?.count()).isEqualTo(1L)
        assertThat(registry.find(absentName).timer()).isNull()
    }
}
