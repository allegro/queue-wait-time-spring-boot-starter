package pl.allegro.tech.queuewaittime.tomcat

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.apache.catalina.connector.Connector
import org.apache.coyote.ProtocolHandler
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.getBean
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.tomcat.ConfigurableTomcatWebServerFactory
import org.springframework.boot.tomcat.autoconfigure.TomcatVirtualThreadsWebServerFactoryCustomizer
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.boot.web.server.WebServerFactoryCustomizer
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.function.Supplier

internal class TomcatQueueWaitTimeAutoConfigurationTest {
    private val baseContextRunner = WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(TomcatQueueWaitTimeAutoConfiguration::class.java))
    private val contextRunner = baseContextRunner
        .withBean(MeterRegistry::class.java, Supplier { SimpleMeterRegistry() })

    @Test
    fun startsWithMaximumThreadsBelowDefaultMinimum() {
        contextRunner
            .withPropertyValues("server.tomcat.threads.max=5")
            .run { context ->
                val factory = TestTomcatFactory().apply { port = 0 }
                context.getBean<WebServerFactoryCustomizer<ConfigurableTomcatWebServerFactory>>().customize(factory)
                val executor = factory.protocolHandler().executor as ExecutorService
                val server = factory.getWebServer()
                try {
                    server.start()

                    assertThat(server.port).isPositive()
                    assertThat(executor.submit(Callable { "executed" }).get(5, TimeUnit.SECONDS))
                        .isEqualTo("executed")
                } finally {
                    server.stop()
                    server.destroy()
                }
            }
    }

    @ParameterizedTest
    @ValueSource(ints = [0, -1])
    fun ignoresNonPositiveThreadSettings(value: Int) {
        contextRunner
            .withPropertyValues(
                "server.tomcat.threads.min-spare=$value",
                "server.tomcat.threads.max=$value",
                "server.tomcat.threads.max-queue-capacity=$value",
            )
            .run { context ->
                val factory = TestTomcatFactory()
                context.getBean<WebServerFactoryCustomizer<ConfigurableTomcatWebServerFactory>>().customize(factory)
                val executor = factory.protocolHandler().executor as ExecutorService

                assertThat(executor.submit(Callable { "executed" }).get(5, TimeUnit.SECONDS))
                    .isEqualTo("executed")
            }
    }

    @Test
    fun recordsPlatformThreadQueueWaitAndShutsDownTheExecutor() {
        lateinit var executor: ExecutorService

        contextRunner
            .withPropertyValues(
                "server.tomcat.threads.min-spare=1",
                "server.tomcat.threads.max=1",
                "server.tomcat.threads.max-queue-capacity=2",
            )
            .run { context ->
                assertThat(context).doesNotHaveBean("tomcatVirtualThreadsWebServerCustomizer")
                val factory = TestTomcatFactory()
                context.getBean<WebServerFactoryCustomizer<ConfigurableTomcatWebServerFactory>>().customize(factory)
                executor = factory.protocolHandler().executor as ExecutorService
                val threadPriority = executor.submit(Callable { Thread.currentThread().priority })
                    .get(5, TimeUnit.SECONDS)
                assertThat(threadPriority).isEqualTo(Thread.NORM_PRIORITY)
                val started = CountDownLatch(1)
                val release = CountDownLatch(1)
                val blockingTask = executor.submit {
                    started.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }
                try {
                    assertThat(started.await(5, TimeUnit.SECONDS)).isTrue()
                    val queuedTask = executor.submit {}
                    Thread.sleep(100)
                    release.countDown()
                    blockingTask.get(5, TimeUnit.SECONDS)
                    queuedTask.get(5, TimeUnit.SECONDS)

                    await().atMost(Duration.ofSeconds(5)).untilAsserted {
                        val timer = context.getBean<MeterRegistry>()
                            .get("executor.idle").tag("name", "tomcat").timer()
                        assertThat(timer.max(TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(50.0)
                    }
                } finally {
                    release.countDown()
                }
            }

        await().atMost(Duration.ofSeconds(5)).untilAsserted {
            assertThat(executor.isTerminated).isTrue()
        }
    }

    @Test
    fun keepsTheExecutorAliveUntilBeanDestruction() {
        lateinit var executor: ExecutorService

        contextRunner
            .withPropertyValues(
                "server.tomcat.threads.min-spare=1",
                "server.tomcat.threads.max=1",
            )
            .run { context ->
                val factory = TestTomcatFactory()
                context.getBean<WebServerFactoryCustomizer<ConfigurableTomcatWebServerFactory>>().customize(factory)
                executor = factory.protocolHandler().executor as ExecutorService

                context.stop()

                assertThat(executor.isShutdown).isFalse()
            }

        await().atMost(Duration.ofSeconds(5)).untilAsserted {
            assertThat(executor.isTerminated).isTrue()
        }
    }

    @Test
    fun interruptsRunningTasksOnBeanDestructionWithoutWaiting() {
        contextRunner
            .withPropertyValues(
                "server.tomcat.threads.min-spare=1",
                "server.tomcat.threads.max=1",
            )
            .run { context ->
                val factory = TestTomcatFactory()
                context.getBean<WebServerFactoryCustomizer<ConfigurableTomcatWebServerFactory>>().customize(factory)
                val executor = factory.protocolHandler().executor as ExecutorService
                val started = CountDownLatch(1)
                val interrupted = CountDownLatch(1)
                executor.submit {
                    started.countDown()
                    try {
                        CountDownLatch(1).await()
                    } catch (_: InterruptedException) {
                        interrupted.countDown()
                    }
                }

                try {
                    assertThat(started.await(5, TimeUnit.SECONDS)).isTrue()

                    assertTimeoutPreemptively(Duration.ofSeconds(1)) { context.close() }

                    assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue()
                } finally {
                    executor.shutdownNow()
                }
            }
    }

    @Test
    fun wrapsBootsVirtualThreadExecutorWithoutCreatingAPlatformPool() {
        contextRunner
            .withPropertyValues("spring.threads.virtual.enabled=true")
            .run { context ->
                assertThat(context).doesNotHaveBean("tomcatPlatformThreadsWebServerCustomizer")
                val factory = TestTomcatFactory()
                TomcatVirtualThreadsWebServerFactoryCustomizer().customize(factory)
                context.getBean<WebServerFactoryCustomizer<ConfigurableTomcatWebServerFactory>>().customize(factory)
                val executor = factory.protocolHandler().executor as ExecutorService
                try {
                    assertThat(executor.submit(Callable { Thread.currentThread().isVirtual })
                        .get(5, TimeUnit.SECONDS)).isTrue()
                    await().atMost(Duration.ofSeconds(5)).untilAsserted {
                        val timer = context.getBean<MeterRegistry>()
                            .get("executor.idle").tag("name", "tomcat-virtual-threads").timer()
                        assertThat(timer.count()).isPositive()
                    }
                } finally {
                    executor.shutdownNow()
                }
            }
    }

    @Test
    fun doesNotConfigureMonitoringWithoutARegistry() {
        baseContextRunner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).doesNotHaveBean(WebServerFactoryCustomizer::class.java)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun canDisableTomcatMonitoring(virtualThreads: Boolean) {
        contextRunner
            .withPropertyValues(
                "allegro-tech.tomcat.queue-wait-time.enabled=false",
                "spring.threads.virtual.enabled=$virtualThreads",
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean(WebServerFactoryCustomizer::class.java)
            }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun canEnableTomcatMonitoringExplicitly(virtualThreads: Boolean) {
        contextRunner
            .withPropertyValues(
                "allegro-tech.tomcat.queue-wait-time.enabled=true",
                "spring.threads.virtual.enabled=$virtualThreads",
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(WebServerFactoryCustomizer::class.java)
            }
    }

    @Test
    fun backsOffWithoutTomcat() {
        contextRunner
            .withClassLoader(FilteredClassLoader("org.springframework.boot.tomcat"))
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean(WebServerFactoryCustomizer::class.java)
            }
    }

    private class TestTomcatFactory : TomcatServletWebServerFactory() {
        fun protocolHandler(): ProtocolHandler =
            Connector().also { customizeConnector(it) }.protocolHandler
    }
}
