package pl.allegro.tech.queuewaittime.tomcat

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics
import org.apache.tomcat.util.threads.TaskQueue
import org.apache.tomcat.util.threads.TaskThreadFactory
import org.apache.tomcat.util.threads.ThreadPoolExecutor
import org.springframework.beans.factory.DisposableBean
import org.springframework.boot.tomcat.ConfigurableTomcatWebServerFactory
import org.springframework.boot.tomcat.autoconfigure.TomcatServerProperties
import org.springframework.boot.web.server.WebServerFactoryCustomizer
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

internal class TomcatPlatformThreadsWebServerCustomizer(
    private val properties: TomcatServerProperties,
    private val meterRegistry: MeterRegistry,
) : WebServerFactoryCustomizer<ConfigurableTomcatWebServerFactory>, DisposableBean {
    private var executor: ExecutorService? = null

    override fun customize(factory: ConfigurableTomcatWebServerFactory) {
        val tomcatExecutor = createExecutor()
        tomcatExecutor.prestartAllCoreThreads()
        val monitoredExecutor = ExecutorServiceMetrics.monitor(meterRegistry, tomcatExecutor, "tomcat")
        executor = monitoredExecutor
        factory.addProtocolHandlerCustomizers({ it.executor = monitoredExecutor })
    }

    private fun createExecutor(): ThreadPoolExecutor {
        val threads = properties.threads
        val defaults = TomcatServerProperties.Threads()
        val maxThreads = threads.max.takeIf { it > 0 } ?: defaults.max
        val minSpareThreads = (threads.minSpare.takeIf { it > 0 } ?: defaults.minSpare).coerceAtMost(maxThreads)
        val taskQueue = TaskQueue(threads.maxQueueCapacity.takeIf { it > 0 } ?: defaults.maxQueueCapacity)
        val tomcatExecutor = ThreadPoolExecutor(
            minSpareThreads,
            maxThreads,
            60,
            TimeUnit.SECONDS,
            taskQueue,
            TaskThreadFactory("custom-tomcat-handler-", true, Thread.NORM_PRIORITY),
        )
        taskQueue.setParent(tomcatExecutor)
        return tomcatExecutor
    }

    override fun destroy() {
        executor?.shutdownNow()
    }
}
