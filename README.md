# Java Trail

Java Trail helps you see what a running Java application is doing. It connects to the application's debug port using the Java Debug Interface (JDI) and provides two commands:

- **`watch`** monitors changes to a field, printing its old and new values and the stack where the change happened.
- **`trace`** records a method invocation and the calls it makes on the same thread, producing a JSON trace with entry/exit events, stack depths and timings.

Use it to investigate unexpected state changes, follow a request through your code, or capture a call sequence for later analysis. It runs independently of OpenForum and can also be used as a Java library.

## Requirements

- A full **JDK 11 or newer**, including the `jdk.jdi` module.
- **Maven** to build the project.
- `org.onestonesoup:core:0.1.4` available in your Maven repository. Java Trail uses its JSON helpers; Maven also resolves its transitive dependencies.
- A target JVM started with debugging enabled and an accessible JDWP debug port.

## Build

From the project root:

```sh
mvn clean install
java -jar target/java-trail-1.5.0-cli.jar --help
```

The build produces two JARs:

| File | Purpose |
| --- | --- |
| `target/java-trail-1.5.0-cli.jar` | Executable command-line tool with runtime dependencies included. |
| `target/java-trail-1.5.0.jar` | Library for other Java projects. Maven coordinates: `org.onestonesoup:java-trail:1.5.0`. |

After building, you can copy the `-cli.jar` elsewhere and run it with a full JDK; the source checkout and Maven are not needed to run it.

## Quick start: trace the included sample

Open two terminals in the project root.

In the first terminal, start the sample application. The script enables debugging on port `5005` and runs the sample repeatedly:

```sh
./run-sample-application.sh
```

In the second terminal, capture a trace:

```sh
java -jar target/java-trail-1.5.0-cli.jar trace sample-tracing-config.json
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
java -jar target/java-trail-1.5.0-cli.jar watch com.example.App counter localhost 5005
```

Arguments are ordered: fully qualified class name, field name, debug host, debug port. The class must already be loaded when Java Trail attaches. Both instance and static field modification events can be observed; the command selects the field on the class rather than a particular object instance.

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
  "maxDurationMillis": 30000,
  "awaitCompletionMillis": 35000,
  "outputFile": "traces/order-trace.json"
}
```

Run it, then trigger `submitOrder` in your application:

```sh
java -jar target/java-trail-1.5.0-cli.jar trace trace-config.json
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
| `outputFile` | Optional output path. Relative paths resolve against the configuration file's directory; parent directories are created. Omit to print JSON to standard output. |

Package filters are prefix matches, not regular expressions. For example, `com.example.orders.` restricts matches to classes below that package. These filters control recorded output; debugger event processing can still involve code outside those packages.

### Reading the output

The JSON contains session metadata (`started`, `completed`, `timedOut`, `completionReason`, `eventCount`) and an `events` array. Each event identifies an `ENTER` or `EXIT`, class, method, signature when available, source line, call depth, thread and timestamps. Exit events include the observed call duration in milliseconds.

For compatibility, output numbers and booleans are serialized as **strings**, such as `"eventCount": "12"` and `"timedOut": "false"`. Check `completionReason` and `timedOut` to distinguish a complete invocation from a trace stopped by a limit. Debugger suspension adds overhead, so timings describe the observed debug run rather than production performance.

To print JSON to the terminal or redirect it, remove `outputFile` from the configuration:

```sh
java -jar target/java-trail-1.5.0-cli.jar trace trace-config.json > trace.json
```

### Command results

- `0`: Help displayed, or the trace command produced a result. A trace that reaches its timeout can still return `0`; inspect the JSON completion fields.
- `1`: Connection or other execution error.
- `2`: Invalid command arguments, missing configuration file, or configuration validation error.

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
  <version>1.5.0</version>
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

Run `mvn test` for configuration, command-line, debugger-session and real-JVM integration tests. See [VERIFICATION.txt](VERIFICATION.txt) for the extraction's build and packaged-JAR checks.

Java Trail was extracted from the OpenForum java-trail addon, version `1.4.1`. The separate OpenForum adapter remains at `../open-forum/addons/java-trail` and preserves the legacy page API class names. Build/install this library before building that adapter. OpenForum is optional and is not a dependency of the standalone project.

The original design notes remain in `tracing-requirements.html` and `trace-implementation-steps.html`. A working example configuration is included in [sample-tracing-config.json](sample-tracing-config.json).
