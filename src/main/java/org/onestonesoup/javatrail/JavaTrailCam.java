package org.onestonesoup.javatrail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.sun.jdi.connect.IllegalConnectorArgumentsException;

import org.onestonesoup.core.data.EntityTree;

public class JavaTrailCam {

    private final java.util.concurrent.atomic.AtomicBoolean capturing = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile SimpleDebugVM activeVm;
    private volatile SimpleDebugVM.MethodTraceSession activeTraceSession;

    public SimpleDebugVM getActiveVm() {
        return activeVm;
    }

    public SimpleDebugVM.MethodTraceSession getActiveTraceSession() {
        return activeTraceSession;
    }

public static void main(String[] args) throws Exception {
    if (args.length != 1) throw new IllegalArgumentException("Usage: JavaTrailCam <config.json>");
    Path configPath = Paths.get(args[0]).toAbsolutePath();
    Config config = Config.load(configPath);
    String json = new JavaTrailCam().capture(config, null, true);
    if (config.outputFile != null && !config.outputFile.isEmpty()) {
        Path output = resolveOutput(configPath, config.outputFile);
        writeOutput(output, json);
        System.out.println("Trace written to " + output);
    } else System.out.println(json);
}

static Path resolveOutput(Path configPath, String fileName) {
    Path output = Paths.get(fileName);
    return (output.isAbsolute() ? output : configPath.toAbsolutePath().getParent().resolve(output)).normalize();
}

static void writeOutput(Path path, String content) throws IOException {
    Files.createDirectories(path.toAbsolutePath().getParent());
    Files.writeString(path, content, StandardCharsets.UTF_8);
}

/** The CLI shares the same capture path as integrations; file handling belongs to callers. */
public String capture(Config config, java.util.function.Consumer<SimpleDebugVM.MethodTraceEvent> sink,
                      boolean retainEvents) throws Exception {
    if (!capturing.compareAndSet(false, true)) throw new IllegalStateException("This camera already has an active capture");
    SimpleDebugVM vm = null;
    Thread shutdown = null;
    try {
        vm = connect(config.getHost(), config.getPort());
        activeVm = vm;
        final SimpleDebugVM connected = vm;
        shutdown = new Thread(connected::disconnect, "JavaTrailCam-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdown);
        SimpleDebugVM.MethodTraceSession session = vm.startMethodTrace(config.toMethodTraceConfig(sink, retainEvents));
        activeTraceSession = session;
        long awaitMillis = config.awaitCompletionMillis > 0 ? config.awaitCompletionMillis : config.maxDurationMillis;
        session.awaitCompletion(awaitMillis);
        return buildOutput(config, session, session.getEvents(), retainEvents);
    } finally {
        activeTraceSession = null;
        try {
            if (vm != null) vm.disconnect();
        } finally {
            activeVm = null;
            try {
                if (shutdown != null) Runtime.getRuntime().removeShutdownHook(shutdown);
            } catch (IllegalStateException ignored) {
                // JVM shutdown is already running the hook.
            } finally {
                capturing.set(false);
            }
        }
    }
}

public SimpleDebugVM connect(String host, String port) throws IllegalConnectorArgumentsException, IOException {
    return new SimpleDebugVM(host, port);
}

public String processUsingConfig(String configString) {
    try { return capture(Config.fromString(configString), null, true); }
    catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return "Exception: " + e;
    } catch (Exception e) { return "Exception: " + e; }
}

public SimpleDebugVM connect(Config config) throws IllegalConnectorArgumentsException, IOException {
    return connect(config.getHost(), config.getPort());
}

private static String buildOutput(Config config,
                                  SimpleDebugVM.MethodTraceSession session,
                                  List<SimpleDebugVM.MethodTraceEvent> events, boolean retainEvents) {
    EntityTree tree = new EntityTree("javaTrailCamTrace");
    EntityTree.TreeEntity root = tree.getRoot();

    addValue(root, "host", config.host);
    addValue(root, "port", config.port);
    addValue(root, "startClass", config.startClass);
    addValue(root, "startMethod", config.startMethod);
    addOptionalValue(root, "startSignature", config.startSignature);
    addValue(root, "started", Boolean.toString(session.hasStarted()));
    addValue(root, "completed", Boolean.toString(session.isCompleted()));
    addValue(root, "timedOut", Boolean.toString(session.isTimedOut()));
    addValue(root, "completionReason", session.getCompletionReason());

    if (session.getTraceStartTime() > 0) {
        addValue(root, "traceStartTime", Long.toString(session.getTraceStartTime()));
    }
    if (session.getTraceEndTime() > 0) {
        addValue(root, "traceEndTime", Long.toString(session.getTraceEndTime()));
        if (session.getTraceStartTime() > 0) {
            addValue(root, "traceDurationMillis", Long.toString(session.getTraceEndTime() - session.getTraceStartTime()));
        }
    }

    addValue(root, "eventCount", Integer.toString(Math.max(events.size(), session.getEventCount())));
    addValue(root, "retainedEventCount", Integer.toString(events.size()));
    addValue(root, "truncated", Boolean.toString(session.isTruncated()));
    addValue(root, "eventsRetained", Boolean.toString(retainEvents));
    addValue(root, "maxEvents", Integer.toString(config.maxEvents));
    addValue(root, "failed", Boolean.toString(session.getCompletionReason().startsWith("Processing failed:")
            || session.getCompletionReason().startsWith("Stream failed:")));
    addPackages(root.addChild("includePackages"), config.includePackages);
    addPackages(root.addChild("excludePackages"), config.excludePackages);

    EntityTree.TreeEntity eventsArray = root.addChild("events");
    eventsArray.setAttribute("array", "true");
    for (int i = 0; i < events.size(); i++) {
        SimpleDebugVM.MethodTraceEvent event = events.get(i);
        EntityTree.TreeEntity eventNode = eventsArray.addChild(Integer.toString(i));
        addEventValues(eventNode, event);
    }

    return TrailJson.stringifyObject(root);
}

static String eventJson(SimpleDebugVM.MethodTraceEvent event) {
    EntityTree tree = new EntityTree("event");
    addEventValues(tree.getRoot(), event);
    // Compact output with escaped string controls keeps each event on one JSONL line.
    return TrailJson.stringifyObject(tree.getRoot());
}

private static void addEventValues(EntityTree.TreeEntity node, SimpleDebugVM.MethodTraceEvent event) {
    addValue(node, "type", event.type.name());
    addValue(node, "className", event.className);
    addValue(node, "methodName", event.methodName);
    addOptionalValue(node, "signature", event.signature);
    addValue(node, "lineNumber", Integer.toString(event.lineNumber));
    addValue(node, "depth", Integer.toString(event.depth));
    addOptionalValue(node, "thread", event.threadName);
    addValue(node, "timestampMillis", Long.toString(event.timestampMillis));
    addValue(node, "elapsedMillis", Long.toString(event.elapsedMillis));
    addValue(node, "durationMillis", Long.toString(event.durationMillis));
}

private static void addPackages(EntityTree.TreeEntity parent, List<String> packages) {
    parent.setAttribute("array", "true");
    for (int i = 0; i < packages.size(); i++) {
        EntityTree.TreeEntity child = parent.addChild(Integer.toString(i));
        child.setValue(packages.get(i));
    }
}

private static void addOptionalValue(EntityTree.TreeEntity parent, String name, String value) {
    if (value != null && !value.isEmpty()) {
        addValue(parent, name, value);
    }
}

private static void addValue(EntityTree.TreeEntity parent, String name, String value) {
    EntityTree.TreeEntity child = parent.getChild(name);
    if (child == null) {
        child = parent.addChild(name);
    }
    child.setValue(value);
}

public static final class Config {
    private final String host;
    private final String port;
    private final String startClass;
    private final String startMethod;
    private final String startSignature;
    private final List<String> includePackages;
    private final List<String> excludePackages;
    private final boolean skipOutsideDomain;
    private final int maxDepth;
    private final long maxDurationMillis;
    private final long awaitCompletionMillis;
    private final String outputFile;
    private final boolean outputFileIsAbsolute;
    private int maxEvents = 100000;
    private String originalJson;

    Config(String host,
           String port,
           String startClass,
           String startMethod,
           String startSignature,
           List<String> includePackages,
           List<String> excludePackages,
           boolean skipOutsideDomain,
           int maxDepth,
           long maxDurationMillis,
           long awaitCompletionMillis,
           String outputFile,
           boolean outputFileIsAbsolute) {
        this.host = host;
        this.port = port;
        this.startClass = startClass;
        this.startMethod = startMethod;
        this.startSignature = startSignature;
        this.includePackages = includePackages;
        this.excludePackages = excludePackages;
        this.skipOutsideDomain = skipOutsideDomain;
        this.maxDepth = maxDepth;
        this.maxDurationMillis = maxDurationMillis;
        this.awaitCompletionMillis = awaitCompletionMillis;
        this.outputFile = outputFile;
        this.outputFileIsAbsolute = outputFileIsAbsolute;
    }

    private SimpleDebugVM.MethodTraceConfig toMethodTraceConfig(
            java.util.function.Consumer<SimpleDebugVM.MethodTraceEvent> sink, boolean retain) {
        return new SimpleDebugVM.MethodTraceConfig(
                startClass,
                startMethod,
                startSignature,
                includePackages,
                excludePackages,
                skipOutsideDomain,
                maxDepth,
                maxDurationMillis, maxEvents, sink, retain
        );
    }

    static Config load(Path configPath) throws IOException {
        if (!Files.isRegularFile(configPath)) throw new IllegalArgumentException("Configuration file not found: " + configPath);
        String json = Files.readString(configPath, StandardCharsets.UTF_8);
        return fromString(json);
    }

    public static Config fromString(String json) {
        EntityTree tree = TraceReport.parseObject(json, "configuration");
        EntityTree.TreeEntity root = tree.getRoot();

        String host = getString(root, "host", "localhost");
        String port = getRequiredString(root, "port", "Debug port is required");
        String startClass = getRequiredString(root, "startClass", "startClass is required");
        String startMethod = getRequiredString(root, "startMethod", "startMethod is required");
        String startSignature = getString(root, "startSignature", null);

        List<String> includePackages = getStringArray(root, "includePackages");
        List<String> excludePackages = getStringArray(root, "excludePackages");
        boolean skipOutsideDomain = getBoolean(root, "skipOutsideDomain", false);
        int maxDepth = getInt(root, "maxDepth", -1);
        long maxDurationMillis = getLong(root, "maxDurationMillis", 0L);
        long awaitCompletionMillis = getLong(root, "awaitCompletionMillis", maxDurationMillis);
        String outputFile = getString(root, "outputFile", null);
        boolean outputFileIsAbsolute = outputFile != null && Paths.get(outputFile).isAbsolute();

        Config config = new Config(
                host,
                port,
                startClass,
                startMethod,
                startSignature,
                includePackages,
                excludePackages,
                skipOutsideDomain,
                maxDepth,
                maxDurationMillis,
                awaitCompletionMillis,
                outputFile,
                outputFileIsAbsolute
        );
        config.maxEvents = getInt(root, "maxEvents", 100000);
        if (host.isBlank() || startClass.isBlank() || startMethod.isBlank()) throw new IllegalArgumentException("Host, startClass and startMethod must not be blank");
        validatePort(port);
        if (maxDepth < -1 || maxDurationMillis < 0 || awaitCompletionMillis < 0 || config.maxEvents < 1)
            throw new IllegalArgumentException("Invalid limits: durations must be nonnegative, maxDepth >= -1 and maxEvents positive");
        config.originalJson = json;
        return config;
    }

    public int getMaxEvents() { return maxEvents; }

    public Config withOverrides(java.util.Map<String, String> overrides) {
        EntityTree tree = TraceReport.parseObject(originalJson, "configuration");
        for (java.util.Map.Entry<String,String> entry : overrides.entrySet()) addValue(tree.getRoot(), entry.getKey(), entry.getValue());
        return fromString(TrailJson.stringifyObject(tree.getRoot()));
    }

    static void validatePort(String port) {
        try {
            int value = Integer.parseInt(port);
            if (value < 1 || value > 65535) throw new NumberFormatException();
        } catch (NumberFormatException e) { throw new IllegalArgumentException("Debug port must be a number from 1 to 65535"); }
    }

    private static String getRequiredString(EntityTree.TreeEntity root, String name, String message) {
        String value = getString(root, name, null);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private static String getString(EntityTree.TreeEntity root, String name, String defaultValue) {
        EntityTree.TreeEntity child = root.getChild(name);
        if (child == null || child.getValue() == null || child.getValue().isEmpty()) {
            return defaultValue;
        }
        return child.getValue();
    }

    private static long getLong(EntityTree.TreeEntity root, String name, long defaultValue) {
        String value = getString(root, name, null);
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid long value for " + name + ": " + value, e);
        }
    }

    private static int getInt(EntityTree.TreeEntity root, String name, int defaultValue) {
        String value = getString(root, name, null);
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid int value for " + name + ": " + value, e);
        }
    }

    private static boolean getBoolean(EntityTree.TreeEntity root, String name, boolean defaultValue) {
        String value = getString(root, name, null);
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value))
            throw new IllegalArgumentException("Invalid boolean value for " + name + ": " + value);
        return Boolean.parseBoolean(value);
    }

    private static List<String> getStringArray(EntityTree.TreeEntity root, String name) {
        EntityTree.TreeEntity child = root.getChild(name);
        if (child == null || child.getAttribute("array") == null) {
            return Collections.emptyList();
        }
        List<String> values = new ArrayList<>();
        for (EntityTree.TreeEntity element : child.getChildren()) {
            if (element.getValue() != null) {
                values.add(element.getValue());
            }
        }
        return Collections.unmodifiableList(values);
    }

    public String getHost() {
        return host;
    }

    public String getPort() {
        return port;
    }

    public String getStartClass() {
        return startClass;
    }

    public String getStartMethod() {
        return startMethod;
    }

    public String getStartSignature() {
        return startSignature;
    }

    public List<String> getIncludePackages() {
        return includePackages;
    }

    public List<String> getExcludePackages() {
        return excludePackages;
    }

    public boolean isSkipOutsideDomain() {
        return skipOutsideDomain;
    }

    public int getMaxDepth() {
        return maxDepth;
    }

    public long getMaxDurationMillis() {
        return maxDurationMillis;
    }

    public long getAwaitCompletionMillis() {
        return awaitCompletionMillis;
    }

    public String getOutputFile() {
        return outputFile;
    }

    public boolean isOutputFileAbsolute() {
        return outputFileIsAbsolute;
    }
}
}
