# Java Trail

Java Trail helps you see what a running Java application is doing. It connects to the application's debug port using the Java Debug Interface (JDI) and provides two commands:

- **`watch`** monitors changes to a field, printing its old and new values and the stack where the change happened.
- **`trace`** records a method invocation and the calls it makes on the same thread, producing a JSON trace with entry/exit events, stack depths and timings.
- **`report`** turns an existing trace into a readable text call tree or a [self-contained HTML walkthrough](examples/sample-report.html), without connecting to a JVM.

Use it to investigate unexpected state changes, follow a request through your code, or capture a call sequence for later analysis. It runs independently of OpenForum and can also be used as a Java library.

## Requirements

- A full **JDK 11 or newer**, including the `jdk.jdi` module.
- **Maven** to build the project.
- `org.onestonesoup:core:0.1.4` available in your Maven repository. Java Trail retains its `EntityTree` data model and uses a local strict JSON codec for configuration, narrative metadata and trace output; Maven also resolves the core artifact's transitive dependencies.
- A target JVM started with debugging enabled and an accessible JDWP debug port.

## Build

From the project root:

```sh
mvn clean install
java -jar target/java-trail-1.6.0-cli.jar --help
```

The build produces two JARs:

| File | Purpose |
| --- | --- |
| `target/java-trail-1.6.0-cli.jar` | Executable command-line tool with runtime dependencies included. |
| `target/java-trail-1.6.0.jar` | Library for other Java projects. Maven coordinates: `org.onestonesoup:java-trail:1.6.0`. |

After building, you can copy the `-cli.jar` elsewhere and run it with a full JDK; the source checkout and Maven are not needed to run it.

## Quick start: trace the included sample

Open two terminals in the project root.

In the first terminal, start the sample application. The script enables debugging on port `5005` and runs the sample repeatedly:

```sh
./run-sample-application.sh
```

In the second terminal, capture a trace:

```sh
java -jar target/java-trail-1.6.0-cli.jar trace sample-tracing-config.json
```

This waits for the sample's `entryPoint` method, records calls in its domain package, and writes `sample-trace.json` next to the configuration file. The trace command finishes after that invocation completes or a configured limit is reached. The sample keeps running, so you can capture another trace. Press Ctrl+C in its terminal when finished.

## Enable debugging in your application

Add the JDWP option when starting your application, for example:

```sh
java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:5005 \
  -jar your-application.jar
```

This starts the application normally and accepts a debugger on local port `5005`. Keep the debug port on a trusted interface. For a JVM on another machine, the host and port passed to Java Trail must reach that JVM's debug listener.

Java Trail observes calls and changes that happen **after it attaches**. For request-driven applications, start the trace or watcher first, then trigger the request or action you want to inspect.

## Watch a field

```sh
java -jar target/java-trail-1.6.0-cli.jar watch com.example.App counter localhost 5005
```

The original positional arguments remain supported. You can also use named options, wait for a class that has not loaded yet, and stop after a fixed duration:

```sh
java -jar target/java-trail-1.6.0-cli.jar watch --class com.example.App --field counter \
  --host localhost --port 5005 --wait-class 10000 --duration 30000 --poll-interval 1000
```

All durations are in milliseconds. `--wait-class` defaults to `0` (fail immediately if the class is not ready), `--duration` to `0` (run until stopped or disconnected), and `--poll-interval` to `5000`. The duration starts after the watch is registered. Waiting uses class-prepare debugger events so initializer writes can be captured. Pending field changes are capped at 10,000; overflow is reported on standard error rather than silently consuming unbounded memory.

Positional arguments are ordered: fully qualified class name, field name, debug host, debug port. Without `--wait-class`, the class must already be loaded when Java Trail attaches. Both instance and static field modification events can be observed; the command selects the field on the class rather than a particular object instance.

Changes are collected and printed every five seconds. Example output:

```text
Class:com.example.App
 Field:counter
  From:7
  To:8
    Stack:com.example.App.increment#24
```

If no changes were collected, the command prints `No Changes`. It continues until stopped. **Ctrl+C** detaches the debugger and leaves the target application running.

## Trace method calls

Create a configuration file, such as `trace-config.json`:

```json
{
  "host": "localhost",
  "port": "5005",
  "startClass": "com.example.OrderService",
  "startMethod": "submitOrder",
  "includePackages": ["com.example"],
  "excludePackages": ["com.example.thirdparty"],
  "skipOutsideDomain": true,
  "maxDepth": 8,
  "maxEvents": 100000,
  "maxDurationMillis": 30000,
  "awaitCompletionMillis": 35000,
  "outputFile": "traces/order-trace.json"
}
```

Run it, then trigger `submitOrder` in your application:

```sh
java -jar target/java-trail-1.6.0-cli.jar trace trace-config.json
```

The trace follows the first matching invocation and its calls on that thread. It normally finishes when execution returns from the selected start method. Calls on other threads are not included.

### Configuration reference

| Setting | Meaning and default |
| --- | --- |
| `host` | Debug host. Defaults to `localhost`. |
| `port` | Required debug port. |
| `startClass` | Required fully qualified name of the class declaring the start method. |
| `startMethod` | Required method name to begin tracing. |
| `startSignature` | Optional JVM method descriptor to select an overload, for example `()V` for a no-argument method returning `void`. Omit or leave empty to match by name. |
| `includePackages` | Class-name prefixes to record. An empty list includes all classes unless excluded. |
| `excludePackages` | Class-name prefixes to omit. Exclusions take precedence over inclusions, except the start class is always recorded. |
| `skipOutsideDomain` | Domain-filter flag retained for compatibility; defaults to `false`. The include/exclude lists determine which calls are recorded. |
| `maxDepth` | Stops tracing when this call depth is reached. The start method has depth `0`; filtered calls still count toward depth. Defaults to `-1`, meaning no depth limit. |
| `maxDurationMillis` | Timeout checked while waiting for the start method and while processing trace events. Defaults to `0`, meaning no duration limit. |
| `awaitCompletionMillis` | Overall completion wait, including waiting for the start method. Defaults to `maxDurationMillis`; a nonpositive effective wait has no deadline. |
| `maxEvents` | Maximum recorded entry/exit events, default `100000`. Must be positive. Reaching the cap stops the trace with `truncated: "true"` and reason `Reached maxEvents`. |
| `outputFile` | Optional output path. Relative paths resolve against the configuration file's directory; parent directories are created. Omit to print JSON to standard output. |

Package filters are prefix matches, not regular expressions. For example, `com.example.orders.` restricts matches to classes below that package. These filters control recorded output; debugger event processing can still involve code outside those packages. While waiting, requests are restricted to the start class. Once the method starts, requests are restricted to its thread. Calls outside the recorded packages still contribute to depth, preserving the original depth semantics.

### Reading the output

The JSON contains session metadata (`started`, `completed`, `timedOut`, `completionReason`, `eventCount`) and an `events` array. Each event identifies an `ENTER` or `EXIT`, class, method, signature when available, source line, call depth, thread and timestamps. Exit events include the observed call duration in milliseconds.

Additional status fields include `truncated`, `failed`, `maxEvents`, `retainedEventCount` and `eventsRetained`. If `failed` is true, check `completionReason` for an event-processing or stream-write failure. If a JVM disconnects or the trace stops early, some entries may have no recorded exit; their outcome and duration are unknown.

For compatibility, output numbers and booleans are serialized as **strings**, such as `"eventCount": "12"` and `"timedOut": "false"`. Check `completionReason` and `timedOut` to distinguish a complete invocation from a trace stopped by a limit. Debugger suspension adds overhead, so timings describe the observed debug run rather than production performance.

To print JSON to the terminal or redirect it, remove `outputFile` from the configuration:

```sh
java -jar target/java-trail-1.6.0-cli.jar trace trace-config.json > trace.json
```

### Named overrides, automation and streaming

Override a valid config without editing it:

```sh
java -jar target/java-trail-1.6.0-cli.jar trace trace-config.json \
  --host localhost --port 5005 --max-depth 10 --max-events 50000 \
  --max-duration 10000 --await-completion 12000 --output trace.json --fail-on-timeout
```

Configuration `outputFile` paths resolve beside the config. Paths explicitly supplied through CLI options resolve from the current working directory. `--output -` prints JSON to standard output; report notifications go to standard error.

For event-by-event JSONL output (one JSON object per line, flushed as captured):

```sh
java -jar target/java-trail-1.6.0-cli.jar trace trace-config.json \
  --stream-jsonl events.jsonl --no-retain --max-events 1000000 --output summary.json
```

`--no-retain` requires a stream file and keeps no event list in memory. The JSON result then contains session status and the total event count, with an empty `events` array and `eventsRetained: "false"`. Reports require retained events, so this mode cannot be combined with `--report`; use a suitably bounded retained trace when you need a report. Streaming still stops at `maxEvents`. Stream failures terminate tracing with an explicit failure result. On process interruption, flushed JSONL records can survive even when the final summary/report was not written.

Input, JSON output, stream output and report output must use distinct files. Existing output files are replaced. Use `trace --help`, `watch --help`, `report --help` and `--version` for command details.

## Readable trace reports

Generate HTML or text from an existing retained JSON trace, entirely offline:

```sh
java -jar target/java-trail-1.6.0-cli.jar report sample-trace.json \
  --format html --metadata examples/report-metadata.json --output walkthrough.html

java -jar target/java-trail-1.6.0-cli.jar report sample-trace.json \
  --format text --output walkthrough.txt
```

Or capture and render in one command:

```sh
java -jar target/java-trail-1.6.0-cli.jar trace sample-tracing-config.json \
  --report html --report-output walkthrough.html --metadata examples/report-metadata.json
```

Without `--report-output`, capture writes the report next to the config as `<config-file>.html` or `<config-file>.txt`. The offline `report` command defaults to HTML on standard output when no output file is supplied.

The HTML is a self-contained, script-free document using the dark emerald/blue style from `software-developer/testing/flow-test-walkthrough.prompt.txt`. It includes context, Key Highlights, called/mock summaries, a two-column narrative and implementation-reference table, observed method counts/durations, and a closing summary. It works offline and adapts to mobile widths. An inline SVG interaction sequence diagram shows class participants, observed entry/exit arrows and self-calls. Each arrow links to its narrative step; its tooltip includes the full method and thread. Lane headings stay pinned at the top of the diagram while you scroll vertically and remain aligned with their lifelines when scrolling horizontally. Use the diagram's scrollable region to view wide or long sequences, or expand its participant legend for full class names. Filtered depth gaps and exits without a matching entry connect to an explicit unobserved caller, rather than inventing a direct call or return. Missing exit events are never drawn as returns. The diagram shows at most the first 80 events and 12 participants; any cutoff is labelled and the full retained flow remains in the narrative. Participants represent classes, not individual object instances. Partial invocations are marked at their entry step. Text output includes an indented event tree and method summary. Durations are inclusive and can overlap; they are not production profiling measurements.

Optional metadata supplies narrative context and explicit mock/override declarations:

```json
{
  "title": "Order submission",
  "summary": "Observe submission calls and their completion. No test result is inferred.",
  "ideProject": "orders",
  "sourceRoot": "src/main/java",
  "keyPoints": ["Observed call order", "Completion and limits", "Declared mocks and overrides"],
  "mockedMethods": [
    {
      "className": "com.example.PaymentGateway",
      "methodName": "charge",
      "signature": "()V",
      "kind": "overridden",
      "notes": "Declared by the test author: a fixture override replaces this method."
    }
  ],
  "futureConsiderations": ["Compare with a capture against the real gateway."]
}
```

Declarations must name the class and method; `kind` is `mocked` or `overridden`. An optional JVM signature narrows the declaration to an overload. They are labels provided by the report author, not discoveries made by Java Trail. A declaration with no matching events is identified as unobserved. If `mockedMethods` is absent or empty, the mock/override section and per-call status labels are omitted. When declarations are supplied, other undeclared methods show unknown mock/override status. Reports never invent assertions, application behaviour or test pass/fail outcomes.

When `ideProject` (or `--ide-project`) is provided, references use `jetbrains://idea/navigate/reference?project=...&path=...`. Otherwise they use repo-relative source paths. Source paths are derived from class names and `sourceRoot`, so verify them for your checkout, generated sources or custom layouts; the report does not resolve source files from the target JVM. Nested classes link to the outer source file. If moving the report outside the repository, use IDE links or account for its location when following relative references.

A captured sample and its HTML/text walkthroughs are in [examples](examples/). They contain real sample-application debugger events; mock metadata is not supplied.

### Command results

- `0`: Help displayed, or the trace command produced a result. A trace that reaches its timeout can still return `0`; inspect the JSON completion fields.
- `1`: Connection, stream/event-processing or other execution error.
- `2`: Invalid command arguments, missing configuration file, or configuration validation error.
- `3`: Trace timeout when `--fail-on-timeout` is requested; the JSON/report is still written.
- `130`: Interruption handled by the command; OS signal termination can use platform-specific codes.

Ctrl+C stops the command and releases its debugger connection.

## Troubleshooting

| Symptom | What to check |
| --- | --- |
| Connection refused | The target JVM is running with JDWP enabled, and the host/port match its listener. |
| Class not found when watching | Load the class in the target application before attaching; check its fully qualified name. |
| No field changes | Trigger a write to the selected field after attachment and allow five seconds for output. |
| Trace times out before starting | Trigger the method after attachment; check class, method and optional overload signature. |
| Trace contains only a few events | Review package prefixes, exclusions, maximum depth and completion reason. |
| Missing dependency during the build | Make `org.onestonesoup:core:0.1.4` available in your Maven repository. |

## Use as a Java library

Add the dependency after installing or publishing the library to a Maven repository accessible to your project:

```xml
<dependency>
  <groupId>org.onestonesoup</groupId>
  <artifactId>java-trail</artifactId>
  <version>1.6.0</version>
</dependency>
```

A field watcher can use the same engine directly:

```java
import org.onestonesoup.javatrail.JavaTrail;
import org.onestonesoup.javatrail.SimpleDebugVM;

SimpleDebugVM vm = new JavaTrail().connect("localhost", "5005");
try {
    vm.watch("com.example.App", "counter");
    // Trigger the application action, then poll vm.checkForChanges().
} finally {
    vm.disconnect();
}
```

For method tracing, `new JavaTrailCam().processUsingConfig(configJson)` accepts the same configuration as a JSON string and returns trace JSON. This library method returns the JSON directly rather than writing `outputFile`; errors retain the `Exception: ...` string contract. The engine classes are in `org.onestonesoup.javatrail`.

## Tests and project background

Additional packaged/browser verification (Python 3; Chrome and Node 22+ for the browser check):

```sh
mvn clean install
python3 scripts/verify-packages.py
node scripts/check-report.cjs
```

The packaged check creates disposable local JVMs, refreshes the sample trace/reports in `examples`, and records results under `target`. The browser check validates the generated report at desktop/mobile sizes, verifies escaping and offline behavior, and saves screenshots under `target`. Both need permission to open local sockets/processes in restricted environments. To inspect a separately built plugin too, pass `--plugin /path/to/java-trail-openforum-1.6.0-plugin.jar` to the Python command.

Run `mvn test` for configuration, CLI options, report escaping/provenance, debugger-session and real-JVM integration tests. Coverage includes late-loaded class initializers, disconnects, cancellation, event limits, high event rates and failure-safe event resumption. See [VERIFICATION.txt](VERIFICATION.txt) for the extraction's build and packaged-JAR checks.

Java Trail was extracted from the OpenForum java-trail addon, version `1.4.1`. The separate OpenForum adapter remains at `../open-forum/addons/java-trail` and preserves the legacy page API class names. Build/install this library before building that adapter. OpenForum is optional and is not a dependency of the standalone project.

The original design notes remain in `tracing-requirements.html` and `trace-implementation-steps.html`. A working example configuration is included in [sample-tracing-config.json](sample-tracing-config.json).
