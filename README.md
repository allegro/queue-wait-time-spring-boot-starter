# Queue wait time Spring Boot starter

Micrometer monitoring for Tomcat request threads. Also, Reactor Netty server event
loops, reactive MongoDB driver event loops, and Reactor Netty-backed WebClient requests.

## Starter main goal

Fix performance issues in Spring Boot applications.
They can be present on a daily-basis at any CPU utilization level

## Project status

**This project is in an early stage of development.** It has been tested at
Allegro, but remains experimental. APIs, configuration properties, and metric
names may change as the project evolves. Validate the integration behavior and
measurements in your own environment before relying on them.

## Usage

Add the starter alongside the Spring Boot dependencies used by your application:

```groovy
implementation 'pl.allegro.tech.queuewaittime:queue-wait-time-spring-boot-starter:<version>'
```

Provide a Micrometer `MeterRegistry`, for example through Spring Boot Actuator.
The starter automatically enables the integrations available on the application's
classpath. It does not bring in a web server, HTTP client implementation, MongoDB
driver, or metrics exporter.
Without an application-provided `MeterRegistry`, monitoring remains disabled;
the starter never creates a fallback registry.

Built against Spring Boot 4.1.1 and Java 21.

Starter-specific configuration uses the `allegro-tech.` prefix. Update existing
unprefixed keys; standard Spring Boot `server.tomcat.*` and `spring.*` settings
remain unchanged.

## Metrics

| Integration                      | Measurement                                                                | Micrometer timer                                                                                         |
|----------------------------------|----------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------|
| Tomcat platform threads          | Time between submitting a request task and starting its execution          | `executor.queue.wait.time`, tagged with `name=tomcat`                                                    |
| Tomcat virtual threads           | Initial delay between submitting a request task and starting its execution | `executor.queue.wait.time`, tagged with `name=tomcat-virtual-threads`                                    |
| Default virtual-thread scheduler | Positive Scheduler lag, sampled every 10 ms by default                     | `scheduler.lag`, tagged with `executor` and `type=VirtualThreadDefaultScheduler`, and `status=unhealthy` |
| Reactor Netty server scheduler   | Positive scheduler lag, sampled every 10 ms by default                     | `scheduler.lag`, tagged with `executor`, `type=server`, and `status=unhealthy`                           |
| Reactive MongoDB scheduler       | Positive scheduler lag, sampled every 10 ms by default                     | `scheduler.lag`, tagged with `executor`, `type=mongo`, and `status=unhealthy`                            |
| WebClient with Reactor Netty     | Time from exchange subscription until Reactor Netty's request callback     | `webclient_queue_wait_time`                                                                              |

Scheduler lag measures scheduling delays, not exact per-request queue wait time.
The `scheduler.sample` counters use `executor`, `type`, and
`status=healthy` or `status=unhealthy` tags. The `scheduler.lag` timer
records only unhealthy samples and uses `status=unhealthy`.
Tomcat uses Micrometer to measure both task waiting time and task execution
time. Platform and virtual thread monitoring follow
`spring.threads.virtual.enabled`.

Tomcat's platform-thread pool follows the configured
`server.tomcat.threads` pool sizes and queue capacity. It shuts down after the web
server and uses normal thread priority. Sampling tasks are canceled when the
application context closes. Reactor Netty keeps ownership of its server event
loops; stopping monitoring does not shut them down.

## Why these metrics matter

**Slow requests are not always slow work. They may spend most of their time
waiting for a thread to start processing.** A single latency metric can hide that
distinction: depending on its boundaries, it may measure waiting, processing, or
both.

For a simplified request path, ignoring network and other phases:

```text
request latency = queue wait time + processing time
```

| If your only metric measures...                                           | You see... | What may actually be happening                                                                                        |
|---------------------------------------------------------------------------|------------|-----------------------------------------------------------------------------------------------------------------------|
| Processing time, for example a server timer starting inside the handler   | `100 ms`   | The request also waited `X ms` before processing. With `80 ms` in a queue, the customer waits `180 ms`, not `100 ms`. |
| Total latency, for example a client timer covering waiting and processing | `100 ms`   | The request spent `80 ms` waiting and only `20 ms` processing. The total alone does not explain the split.            |
| Queue wait time                                                           | `80 ms`    | Processing time is still unknown, so queue wait alone cannot tell you the customer's total latency.                   |

**These metrics are especially useful for performance debugging, load testing,
and tuning.** Used alongside existing response-time metrics, throughput, and
profiling, they help you:

- Reveal time spent waiting for a thread that response-time metrics alone can
  hide.
- Investigate whether rising latency comes from waiting, slower processing, or
  both, instead of assuming the application code itself became slower.
- Focus performance work on the actual bottleneck and verify whether a change
  improves latency under load, including the slowest requests.

The signals have different boundaries: Tomcat measures time waiting for a thread.
Scheduler lag indicates scheduling delays, not exact queue time for each request.
The WebClient metric approximates the interval before an outbound request is sent
(see [Known limitations](#known-limitations)). These signals complement
response-time metrics; they are not
interchangeable phases to add together or subtract from unrelated latency
percentiles.

### Taming queues

When queueing or scheduler lag contributes to latency during normal application
traffic, consider isolating work rather than letting unrelated operations compete
for the same worker pool:

- **Thread-pool operation isolation (bulkheads):** split work horizontally into
  separate pools for different workloads or dependencies, so a slow operation
  cannot occupy all the workers needed by unrelated work.
- **Thread-pool stage isolation (staged event-driven architecture, SEDA):** split
  processing vertically into stages connected by queues, with separate worker
  pools for individual stages.

Both approaches can be combined. Use queue wait time, scheduler lag, and
response-time metrics to verify whether isolation improves latency; moving work
to another queue does not by itself increase processing capacity.

**Remember context propagation when crossing thread-pool or stage boundaries.**
`ThreadLocal` values, such as tracing context, logging MDC, or security context,
are not automatically transferred to another thread. Capture and restore the
relevant context for each task, then restore the worker thread's previous context
afterward to avoid leaking state between requests. In reactive flows, account
for Reactor Context as well rather than assuming a request stays on one thread.

The starter measures waiting and scheduling delays; it does not configure
workload isolation or context propagation.

## Tomcat configuration

| Property                                      | Default | Description                                                               |
|-----------------------------------------------|---------|---------------------------------------------------------------------------|
| `allegro-tech.tomcat.queue-wait-time.enabled` | `true`  | Enable queue wait time monitoring for Tomcat platform and virtual threads |

Set this property to `false` to leave Spring Boot's Tomcat thread configuration unchanged.

## Micrometer executor metric naming

By default, a `MeterFilter` renames `executor.idle` to `executor.queue.wait.time`
in Spring Boot-managed meter registries. It applies to every meter with that
exact name, not only Tomcat metrics, and preserves tags and measurements.

| Property                                                        | Default | Description                                         |
|-----------------------------------------------------------------|---------|-----------------------------------------------------|
| `allegro-tech.rename-executor-idle-to-executor-queue-wait-time` | `true`  | Rename Micrometer's executor queue wait time metric |

Set the flag to `false` to retain the original `executor.idle` name:

```yaml
allegro-tech:
  rename-executor-idle-to-executor-queue-wait-time: false
```

## Scheduler lag configuration

The same settings control Reactor Netty server, reactive MongoDB, and the default
virtual-thread scheduler monitoring. They do not affect Tomcat queue wait time
monitoring.

Virtual-thread scheduler monitoring starts when `spring.threads.virtual.enabled=true`
and a `MeterRegistry` is available. It also works in non-web Spring applications.
The sampling probe is stopped when the application context closes.

| Property                              | Default | Description                                           |
|---------------------------------------|---------|-------------------------------------------------------|
| `allegro-tech.scheduler-lag.enabled`  | `true`  | Enable scheduler lag monitoring                       |
| `allegro-tech.scheduler-lag.interval` | `10ms`  | Positive sampling interval for all scheduler monitors |

```yaml
allegro-tech:
  scheduler-lag:
    enabled: true
    interval: 10ms
```

### Interpreting scheduler samples

`diff` is the gap between probe measurements, not task execution time.
We require two missed nominal sampling windows to confidently flag scheduler
occupancy longer than one interval. For `interval=10ms`, this sets the cutoff at
`2 × 10ms = 20ms`:

- `diff ≤ 20ms`: `healthy`, sample counter `+1`.
- `diff > 20ms`: `unhealthy`, sample counter `+floor(diff / interval)`.

Only unhealthy samples update `scheduler.lag`, recording `diff - 2 × interval`.
Example: a 67ms gap adds six unhealthy windows and records 47ms of lag.

### Window-based ratios

Calculate ratios from counter rates over the same observation window and for the
same executor/type grouping:

```text
total_rate = healthy_rate + unhealthy_rate
healthy_ratio = healthy_rate / total_rate
unhealthy_ratio = unhealthy_rate / total_rate
```

These ratios describe weighted sampling windows, not the number of probe
executions or an exact fraction of wall-clock time. A 100ms gap contributes ten
unhealthy windows, while each healthy observation contributes one.
Interpret the ratios only when `total_rate > 0`.

Normal fixed-delay or sleep-based sampling often produces gaps slightly above
the configured interval. These samples remain healthy within the two-interval
tolerance; a small timing overshoot alone is not evidence of an unhealthy scheduler.

## WebClient monitoring

The starter registers a `WebClientCustomizer` and a Reactor-specific
`ClientHttpConnectorBuilderCustomizer`. Create clients from Spring Boot's injected
`WebClient.Builder` with a Reactor Netty connector to use both customizations.
The starter does not create client, connector, or Netty resource beans; ownership
and shutdown remain with Spring Boot or the application. This integration also
works in applications without a web server.

Every outbound request attempt reaching Reactor Netty's `doOnRequest` callback is
measured, without requiring a special header. The interval starts on subscription
and ends when Reactor Netty is about to send the request. Without automatic
redirects, it does not include waiting for the response. It is not pure scheduler
queue time; see [Known limitations](#known-limitations) for redirect behavior.

Clients created with `WebClient.create()` or manually supplied builders/connectors
do not automatically receive both customizations. Apply the relevant customizer
beans explicitly when bypassing Boot's configuration. Other connector types, such
as JDK, remain usable but do not record this Reactor-specific measurement.

## Reactive MongoDB configuration

Mongo monitoring requires the reactive MongoDB driver, Micrometer, and Netty.
For Mongo-only applications, add `io.netty:netty-handler` as well as the reactive
MongoDB starter; WebFlux applications using Reactor Netty already provide it.
The starter supplies a monitored Netty transport through a
`MongoClientSettingsBuilderCustomizer` and closes its scheduler group when the
application context shuts down.

Applications supplying their own `MongoClientSettings` are left unchanged.
General MongoDB connection settings and document converters remain Spring Boot's
responsibility.

## Known limitations

- **Tomcat virtual-thread metrics:** `executor.queue.wait.time` measures the initial
  task-start delay. Separately, `scheduler.lag` with
  `type=VirtualThreadDefaultScheduler` samples lag in the shared default scheduler
  used by Tomcat and other virtual threads. It is enabled when Spring virtual threads and
  scheduler monitoring are enabled. This is a scheduler-wide signal, not an exact
  measurement of each request's rescheduling delays.
- **MongoDB transport customization:** Monitoring configures its own Netty
  transport and can overwrite transport settings applied by an earlier
  `MongoClientSettingsBuilderCustomizer`, including a custom scheduler group or
  TLS context. Applications providing their own `MongoClientSettings` are left
  unchanged and do not receive this monitoring integration.
- **WebClient timing and redirects:** This metric is a workaround, not an exact
  per-request queue-wait measurement. Automatic redirects reuse the subscription
  start time, so measurements for later attempts can include time spent waiting
  for an earlier response. Treat the metric as a diagnostic signal rather than
  an exact separation of queue wait and response time.

## Development

Integration code and tests are grouped into `tomcat`, `webclient`,
`netty.nettyserver`, and `netty.mongoclient` packages under
`pl.allegro.tech.queuewaittime`. The Netty scheduler sampler and properties shared
by Reactor Netty and MongoDB live in the `netty` package.

```bash
./gradlew check
```

## Contributing

Found this useful? Star the repo!

If you spot an error, feel free to open an issue or fork the repo and submit a
pull request with a fix.

## License

Copyright Allegro Sp. z o.o.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
