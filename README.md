# Queue wait time Spring Boot starter

Filling observability gaps.

Execution-time metrics answer:

> How long did a thread spend running a given task?

They may miss another question:

> How long did the task wait in a queue before a thread picked it up?

This starter fills that gap with metrics for:

- Task queue wait time
- Scheduling delays

Monitoring requires a Micrometer MeterRegistry.

# Table of Contents

- [Installation](#installation)
- [Project status](#project-status)
- [Why queue-wait-time and scheduling-delays metric matter](#why-queue-wait-time-and-scheduling-delays-metric-matter)
- [Metrics](#metrics)
    - [Queue Wait Time Metrics](#queue-wait-time-metrics)
        - [Tomcat queue wait time metrics](#tomcat-queue-wait-time-metrics)
        - [WebClient queue wait time metrics](#webclient-queue-wait-time-metrics)
    - [Scheduler Lag Metrics](#scheduler-lag-metrics)
        - [Scheduler metrics interpretation](#scheduler-metrics-interpretation)
- [Micrometer executor metric naming](#micrometer-executor-metric-naming)
- [Taming queues](#taming-queues)
- [Development](#development)
- [Appendix](./APPENDIX.md)
    - [List of all Metrics](./APPENDIX.md/#metrics)
    - [List of all Properties](./APPENDIX.md/#properties)
- [Contributing](#contributing)
- [License](#license)

## Installation

Add the starter to your Spring Boot application's dependencies:

```groovy
implementation 'pl.allegro.tech.queuewaittime:queue-wait-time-spring-boot-starter:<version>'
```

Requirements:
- Java 21
- Spring Boot 4.2

## Project status

**This project is in an early stage of development.** It has been tested at
Allegro, but remains experimental. APIs, configuration properties, and metric
names may change as the project evolves. Validate the integration behavior and
measurements in your own environment.

We’re open to feedback!

## Why queue-wait-time and scheduling-delays metric matter

Often, only **a single timing metric** is available. Depending on where the
measurement starts and stops, it may capture:

- Task Queue wait time
- Task execution time
    - e.g. a server-side timer that starts when a worker
      thread begins handling the request
- Both queue wait time and execution time
    - e.g. client-observed response
      time, which also includes network latency and other overhead

A single metric does not provide a separate view of waiting and execution.
Queueing delays may go unnoticed - or be mistaken for slow task execution.
If client-side latency exceeds the server's reported execution time,
the difference may be incorrectly attributed to the network rather than
time spent waiting in a queue.

# Metrics

This section describes the metrics exposed by the starter and how to interpret them.

## Queue Wait Time Metrics

No new measurements are recorded when there is no traffic.

### Tomcat queue wait time metrics

Time between submitting a request task and starting its execution.

> Simplified server-side latency = queue wait time + request handling time(http.server.requests)

```text
New request
↓
http-nio-{port}-Poller <- this thread adds tasks to a queue
↓
Queue
↓
Tomcat Worker Threads (Platform or Virtual)
```

| Integration             | Micrometer timer                                                      |
|-------------------------|-----------------------------------------------------------------------|
| Tomcat Platform threads | `executor.queue.wait.time`, tagged with `name=tomcat`                 |
| Tomcat Virtual threads  | `executor.queue.wait.time`, tagged with `name=tomcat-virtual-threads` |

#### Known limitations
- For Tomcat with `Platform Threads`
    - a dedicated Thread Pool is created [TomcatPlatformThreadsWebServerCustomizer.kt](src/main/kotlin/pl/allegro/tech/queuewaittime/tomcat/TomcatPlatformThreadsWebServerCustomizer.kt)
        - Thread names use the `allegro-tech-tomcat-handler-`
        - Spring Boot apps with dedicated management port may not work as expected
- For Tomcat with `Virtual Threads`
    - Only the initial task-start delay is measured by `executor.queue.wait.time`, tagged with `name=tomcat-virtual-threads`
    - For virtual-thread scheduler monitoring, see [Scheduler Lag Metrics](#scheduler-lag-metrics)

#### Properties
Configuration options for this integration:

```yaml
allegro-tech:
  tomcat:
    queue-wait-time:
      enabled: true #default
```
Standard Spring Boot `server.tomcat.*` properties remain unchanged.


### WebClient queue wait time metrics

Time from exchange subscription until Reactor Netty's request callback is executed.
It requires `ReactorClientHttpConnector` as a `ClientHttpConnector` in Spring Boot's `WebClient.Builder`

> Simplified client-side latency(http.client.requests) = queue wait time + actual client response time

| Integration                  | Micrometer timer            |
|------------------------------|-----------------------------|
| WebClient with Reactor Netty | `webclient.queue.wait.time` |

#### Known limitations
Create WebClient by using `WebClient.Builder` factory.

This metric measures the time from exchange subscription until Reactor Netty
is about to send the request.
This may include scheduling delays, connection acquisition,
and connection setup - not just queue wait time.

By default, Spring Boot applications using Reactor Netty for both the server
and WebClient share event-loop resources.

Only delays occurring after exchange subscription are measured.
Time spent waiting before subscription is not included.

#### Properties
Configuration options for this integration:
```yaml
allegro-tech:
  webclient:
    queue-wait-time:
      enabled: true #default
```
Standard Spring Boot `server.tomcat.*` properties remains unchanged.


## Scheduler Lag Metrics

Scheduler lag is sampled periodically, regardless of application traffic -
including when there is no traffic at all.

> This is similar to event-loop delay in JavaScript, but samples are classified
> as `healthy` or `unhealthy`.
>
> For unhealthy samples, `scheduler.lag` records `diff - 2 × interval`,
> not the full gap between probe executions.

These metrics are recorded for:

- Netty
- Virtual Threads
- Reactive MongoDB

Metrics exposed:

- Counters `scheduler.sample`
    - Used to calculate the proportion of unhealthy sampling windows.
- Timer `scheduler.lag`

Both metrics use the following tags:

- `executor` — the event-loop thread name or virtual-thread scheduler name
- `type` — `server`, `mongo`, or `VirtualThreadDefaultScheduler`
- `status` — `healthy` or `unhealthy`
    - Always `unhealthy` for the `scheduler.lag` timer.

These metrics help answer:

- What proportion of sampling windows is classified as unhealthy?
- How far beyond the healthy threshold do unhealthy sampling gaps extend?

#### Properties

Configuration options for this integration:

```yaml
allegro-tech:
  scheduler-lag:
    enabled: true
    interval: 10ms
```

## Scheduler metrics interpretation

The monitoring probe is expected to run at the configured interval
(10ms by default). Each execution measures the gap since the previous execution.

On each execution:

- Calculate `diff`, the gap between the current and previous execution
- For a healthy scheduler:
    - the expected gap between probe executions is the configured interval
    - increment only `scheduler.sample{status=healthy}` with 1
- For an unhealthy scheduler:
    - record `diff - 2 × interval` in the `scheduler.lag` timer
    - record a weighted sample in the `scheduler.sample` counter
        - a single unhealthy sample may represent multiple missed sampling windows

The gap between probes does not reveal exactly when other work started or ended.
For example, a task could run:

- From t=9ms to t=11ms
- From t=1ms to t=19ms

These tasks have very different execution times, but both cases remain within
the default 20ms classification threshold.

For `interval=10ms`, this sets the cutoff at `2 × 10ms = 20ms`:

- `diff ≤ 20ms`: `healthy`, sample counter `+1`.
- `diff > 20ms`: `unhealthy`, sample counter `+floor(diff / interval)`.

Only unhealthy samples update `scheduler.lag`, recording `diff - 2 × interval`.
Example: a 67ms gap adds six unhealthy windows and records 47ms of lag.

#### Known limitations

- For reactive MongoDB, a dedicated event-loop group is created by
  [MongoEventLoopLagCustomizer.kt](src/main/kotlin/pl/allegro/tech/queuewaittime/netty/mongoclient/MongoEventLoopLagCustomizer.kt).

## Micrometer executor metric naming

The starter uses Micrometer to monitor Tomcat's request executor.
Micrometer's `executor.idle` timer measures how long a task waits before execution,
but its name does not make that clear.
By default, the starter uses a MeterFilter to rename it to `executor.queue.wait.time`,
making the measurement explicit.
Only the name changes; tags and measurements are preserved.

#### Properties

Configuration options for this integration:
```yaml
allegro-tech:
  rename-executor-idle-to-executor-queue-wait-time:
    enabled: true #default
```

#### Known limitations
Any thread pool which is monitored with:
```java
Executor monitoredExecutor = ExecutorServiceMetrics.monitor(registry, executor, executorName, tags); 
```

Will expose `executor.queue.wait.time` instead of `executor.idle` metric.

## Taming queues

The source of queueing or scheduler lag should be identified before introducing
workload isolation. Possible causes include blocking I/O, slow dependencies,
CPU saturation, or insufficient concurrency.

If unrelated operations compete for the same worker pool, workload isolation may
help prevent one operation from delaying another:

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
are not automatically transferred with a task to another thread.
Framework-provided context propagation should be used where available.
When manual propagation is required, the relevant context must be captured and
restored for each task. The worker thread's previous context must then be restored
to avoid leaking state between requests.

In reactive flows, Reactor Context is associated with the subscription rather
than a single thread. Appropriate propagation support is needed when bridging
it to `ThreadLocal`-based APIs.

The starter measures waiting and scheduling delays; it does not configure
workload isolation or context propagation.

#### Known limitations

These metrics reveal queueing and scheduling delays, but do not identify
the application code or operations responsible for them. Identifying and
fixing the underlying cause requires further investigation, such as
profiling or tracing.

At Allegro, approaches that have helped include:

- Isolating different endpoints in separate thread pools
- Moving JSON parsing to a dedicated thread pool

**Healthy scheduler samples and low queue wait time do not guarantee
low response time.**

For example, 200 worker threads parsing JSON on 10 CPU cores may show
little or no executor queue wait time, while task execution remains slow
because the threads compete for CPU time.

## Development

Use regular Gradle command, for example:
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
