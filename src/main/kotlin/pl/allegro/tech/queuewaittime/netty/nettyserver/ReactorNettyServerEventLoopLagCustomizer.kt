package pl.allegro.tech.queuewaittime.netty.nettyserver

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.DisposableBean
import org.springframework.boot.reactor.netty.NettyServerCustomizer
import pl.allegro.tech.queuewaittime.netty.EventLoopLagMonitor
import pl.allegro.tech.queuewaittime.netty.EventLoopLagProperties
import reactor.netty.http.server.HttpServer

internal class ReactorNettyServerEventLoopLagCustomizer(
    meterRegistry: MeterRegistry,
    properties: EventLoopLagProperties,
) : NettyServerCustomizer, DisposableBean {
    private val monitor = EventLoopLagMonitor(meterRegistry, properties.interval, "server")

    override fun apply(server: HttpServer): HttpServer =
        server.doOnBind { configuration ->
            monitor.registerGroup(configuration.loopResources().onServer(configuration.isPreferNative))
        }

    override fun destroy() {
        monitor.close()
    }
}
