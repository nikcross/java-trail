package org.onestonesoup.javatrail;

import java.io.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CliOptionsTest {
    @TempDir Path temp;
    private int run(String... args) {return JavaTrailCli.run(args,new PrintStream(new ByteArrayOutputStream()),new PrintStream(new ByteArrayOutputStream()));}
    @Test void commandHelpAndVersionNeedNoConnection() {
        assertEquals(0,run("watch","--help")); assertEquals(0,run("trace","-h")); assertEquals(0,run("report","--help")); assertEquals(0,run("--version"));
    }
    @Test void invalidNamedArgumentsFailBeforeConnecting() {
        for(String[] args:new String[][]{{"watch","--class","App"},{"watch","--class","App","--field","f","--port","bad"},
                {"watch","--class","App","--field","f","--wait-class","-1"}, {"watch","--class","App","--field","f","--poll-interval","0"},
                {"watch","--unknown","x"},{"trace","--host"},{"trace","x","--host","a","--host","b"}}) assertEquals(2,run(args));
    }
    @Test void outputCollisionsAndIncompatibleStreamingOptionsAreRejectedBeforeAttachment() throws Exception {
        Path config=temp.resolve("config.json");String json="{\"port\":\"5005\",\"startClass\":\"App\",\"startMethod\":\"run\"}";Files.writeString(config,json);
        assertEquals(2,run("trace",config.toString(),"--output",config.toString()));
        assertEquals(json,Files.readString(config));
        assertEquals(2,run("trace",config.toString(),"--no-retain"));
        assertEquals(2,run("trace",config.toString(),"--report","pdf"));
        assertEquals(2,run("trace",config.toString(),"--max-events","0"));
        assertEquals(2,run("trace",config.toString(),"--report-output","x"));
    }
    @Test void validatesLimitsAndOverridesWithoutChangingOriginalConfiguration() {
        JavaTrailCam.Config config=JavaTrailCam.Config.fromString("{\"port\":\"5005\",\"startClass\":\"App\",\"startMethod\":\"run\"}");
        JavaTrailCam.Config changed=config.withOverrides(java.util.Map.of("port","6000","maxEvents","42"));
        assertEquals("6000",changed.getPort());assertEquals(42,changed.getMaxEvents());assertEquals("5005",config.getPort());
        for(String field:new String[]{"maxDurationMillis","awaitCompletionMillis","maxEvents"})
            assertThrows(IllegalArgumentException.class,()->config.withOverrides(java.util.Map.of(field,"-1")));
        assertThrows(IllegalArgumentException.class,()->config.withOverrides(java.util.Map.of("skipOutsideDomain","maybe")));
    }
}
