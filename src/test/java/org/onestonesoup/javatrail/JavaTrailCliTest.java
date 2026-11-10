package org.onestonesoup.javatrail;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JavaTrailCliTest {
    @Test
    void helpExplainsBothCommandsWithoutConnecting() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        assertEquals(0, JavaTrailCli.run(new String[]{"--help"}, new PrintStream(output), new PrintStream(error)));
        assertTrue(output.toString().contains("watch <class> <field> <host> <port>"));
        assertTrue(output.toString().contains("trace <config.json>"));
        assertEquals("", error.toString());
    }

    @Test
    void invalidCommandsAndInputsFailWithoutConnecting() {
        String[][] inputs = {
            {"unknown"}, {"watch"}, {"trace"}, {"trace", "one", "two"},
            {"watch", "App", "field", "localhost", "abc"},
            {"watch", "App", "field", "localhost", "0"},
            {"watch", "App", "field", "localhost", "65536"},
            {"trace", "does-not-exist-config.json"}
        };
        for (String[] input : inputs) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ByteArrayOutputStream error = new ByteArrayOutputStream();
            assertEquals(2, JavaTrailCli.run(input, new PrintStream(output), new PrintStream(error)));
            assertTrue(error.toString().contains("Java Trail:"));
            assertEquals("", output.toString());
        }
    }
}
