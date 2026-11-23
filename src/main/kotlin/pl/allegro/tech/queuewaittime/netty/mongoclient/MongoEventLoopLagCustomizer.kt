package pl.allegro.tech.queuewaittime.netty.mongoclient

import com.mongodb.MongoClientSettings
import com.mongodb.connection.TransportSettings
import io.micrometer.core.instrument.MeterRegistry
import io.netty.channel.EventLoopGroup
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.nio.NioIoHandler
import org.springframework.beans.factory.DisposableBean
import org.springframework.boot.mongodb.autoconfigure.MongoClientSettingsBuilderCustomizer
import pl.allegro.tech.queuewaittime.netty.NettySchedulerLagMonitor
import pl.allegro.tech.queuewaittime.schedulerlag.SchedulerLagProperties

internal class MongoEventLoopLagCustomizer(
    meterRegistry: MeterRegistry,
    properties: SchedulerLagProperties,
) : MongoClientSettingsBuilderCustomizer, DisposableBean {
    private val monitor = NettySchedulerLagMonitor(meterRegistry, properties.interval, "mongo")

    private var eventLoopGroup: EventLoopGroup? = null

    override fun customize(builder: MongoClientSettings.Builder) {
        val group = eventLoopGroup ?: MultiThreadIoEventLoopGroup(NioIoHandler.newFactory()).also {
            eventLoopGroup = it
            monitor.registerGroup(it)
        }
        builder.transportSettings(TransportSettings.nettyBuilder().eventLoopGroup(group).build())
    }

    override fun destroy() {
        monitor.close()
        eventLoopGroup?.shutdownGracefully()?.awaitUninterruptibly()
        eventLoopGroup = null
    }
}
