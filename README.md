# Java Trail

A standalone Java debugger tracing library and command-line tool. Java Trail attaches to an existing JVM over JDWP; OpenForum is not required.

Requires a full JDK 11 or newer (including `jdk.jdi`) and Maven. The runtime dependency is `org.onestonesoup:core:0.1.4` for JSON helpers, with its transitive dependencies. That artifact must be available in your Maven repository. No OpenForum server, JavaScript engine or Rhino is used by this project.

## Build

```sh
mvn clean install
java -jar target/java-trail-1.5.0-cli.jar --help
```

The build produces:

- `java-trail-1.5.0.jar`: library, Maven coordinates `org.onestonesoup:java-trail:1.5.0`.
- `java-trail-1.5.0-cli.jar`: executable with runtime dependencies bundled.

## Trace a sample application

In one terminal, start the included sample with debugging enabled:

```sh
./run-sample-application.sh
```

In another terminal:

```sh
java -jar target/java-trail-1.5.0-cli.jar trace sample-tracing-config.json
```

The sample config selects an entry method, include/exclude packages, maximum depth, duration and output path. A relative `outputFile` is resolved against the configuration file's directory. When omitted, trace JSON goes to standard output. Existing JSON configuration and output formats are preserved, including string-valued numbers and booleans. Completion reason and timeout are reported in that JSON; a completed trace command returns zero even if the trace reached its timeout. Invalid usage/configuration returns 2; connection or execution errors return 1. Ctrl+C stops the command and detaches.

For another application, enable JDWP on its JVM, for example:

```sh
java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:5005 -jar your-application.jar
```

Use its fully qualified class/method names in your config. Keep the debug port on a trusted interface. The target application must execute the configured start method after attachment.

## Watch a field

```sh
java -jar target/java-trail-1.5.0-cli.jar watch com.example.App counter localhost 5005
```

The class must already be loaded when the watcher attaches. Changes are printed every five seconds with old/new values and stack information. Ctrl+C releases the debugger connection.

## Use as a library

The engine classes are `org.onestonesoup.javatrail.JavaTrail`, `JavaTrailCam` and `SimpleDebugVM`. `JavaTrail.connect(host, port)` returns a debugger connection. `JavaTrailCam.processUsingConfig(json)` runs a trace and returns the existing trace JSON or `Exception: ...` error string. `JavaTrailCam.Config` and its getters are public for adapters. Active VM/session accessors support cancellation by integrations. Call `disconnect()` on connections you own.

## OpenForum adapter

The separate project at `../open-forum/addons/java-trail` builds `org.onestonesoup:java-trail-openforum:1.5.0` and preserves the `org.onestonesoup.openforum.javatrail` API class names used by existing server pages. Build/install this library first, then build that plugin. This project can be built and run without the plugin or the OpenForum source tree.

Extracted from the OpenForum java-trail addon, version 1.4.1. Original tracing design notes, configuration and sample sources are retained here. Existing engine/configuration/JDWP integration tests moved with the engine; OpenForum session and compatibility tests live in the adapter project.
