package org.onestonesoup.javatrail;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class FieldWatchIntegrationTest {
    @Test
    void watchesPrimitiveFieldInAOneFrameStackAndCanDetach() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        String className = "org.onestonesoup.javatrail.sample.FieldWatchApplication";
        Process target = new ProcessBuilder(
            Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
            "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:" + port,
            "-cp", Paths.get("target", "test-classes").toAbsolutePath().toString(), className)
            .redirectErrorStream(true).start();
        SimpleDebugVM vm = null;
        try {
            BufferedReader output = new BufferedReader(new InputStreamReader(target.getInputStream()));
            String line;
            do {
                line = output.readLine();
                assertNotNull(line, "Sample JVM exited before becoming ready");
            } while (!"ready".equals(line));
            vm = new JavaTrail().connect("127.0.0.1", Integer.toString(port));
            vm.watch(className, "counter");
            SimpleDebugVM.FieldChange[] changes = new SimpleDebugVM.FieldChange[0];
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (changes.length == 0 && System.nanoTime() < deadline) {
                Thread.sleep(50);
                changes = vm.checkForChanges();
            }
            assertTrue(changes.length > 0, "Expected a field event from a shallow stack");
            assertEquals(className, changes[0].className);
            assertEquals("counter", changes[0].fieldName);
            assertEquals(Integer.parseInt(changes[0].currentValue) + 1, Integer.parseInt(changes[0].newValue));
            assertTrue(changes[0].shortStack.length > 0);
            vm.disconnect();
            vm = null;
            assertTrue(target.isAlive(), "Detaching must leave the target application running");
        } finally {
            if (vm != null) vm.disconnect();
            target.destroy();
            if (!target.waitFor(3, TimeUnit.SECONDS)) {
                target.destroyForcibly();
                target.waitFor(3, TimeUnit.SECONDS);
            }
        }
    }
}
