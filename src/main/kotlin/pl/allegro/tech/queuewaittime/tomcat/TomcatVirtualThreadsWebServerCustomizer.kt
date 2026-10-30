package pl.allegro.tech.queuewaittime.tomcat

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics
import org.springframework.boot.tomcat.ConfigurableTomcatWebServerFactory
import org.springframework.boot.web.server.WebServerFactoryCustomizer
import java.util.concurrent.ExecutorService

internal class TomcatVirtualThreadsWebServerCustomizer(
    private val meterRegistry: MeterRegistry,
) : WebServerFactoryCustomizer<ConfigurableTomcatWebServerFactory> {
    override fun customize(factory: ConfigurableTomcatWebServerFactory) {
        factory.addProtocolHandlerCustomizers({ protocolHandler ->
            val executor = requireNotNull(protocolHandler.executor as ExecutorService?) {
                "Tomcat executor not present"
            }
            protocolHandler.executor =
                ExecutorServiceMetrics.monitor(meterRegistry, executor, "tomcat-virtual-threads")
        })
    }
}
