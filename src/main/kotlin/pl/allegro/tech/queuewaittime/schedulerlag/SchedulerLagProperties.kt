package pl.allegro.tech.queuewaittime.schedulerlag

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "allegro-tech.scheduler-lag")
data class SchedulerLagProperties(
    val enabled: Boolean = true,
    val interval: Duration = Duration.ofMillis(10),
) {
    init {
        require(!interval.isZero && !interval.isNegative) {
            "allegro-tech.scheduler-lag.interval must be positive"
        }
    }
}

@AutoConfiguration
@EnableConfigurationProperties(SchedulerLagProperties::class)
class SchedulerLagPropertiesConfiguration
