package org.onestonesoup.javatrail;

import java.nio.file.*;
import java.io.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class TraceReportTest {
    @TempDir Path temp;
    static final String TRACE="{\"startClass\":\"example.App\",\"startMethod\":\"run\",\"completionReason\":\"Reached maxEvents\",\"truncated\":\"true\",\"events\":["
        +"{\"type\":\"ENTER\",\"className\":\"example.App\",\"methodName\":\"run\",\"lineNumber\":\"12\",\"depth\":\"0\",\"thread\":\"main\"},"
        +"{\"type\":\"ENTER\",\"className\":\"example.Service\",\"methodName\":\"save\",\"depth\":\"1\"},"
        +"{\"type\":\"EXIT\",\"className\":\"example.Service\",\"methodName\":\"save\",\"depth\":\"1\",\"durationMillis\":\"7\"}]}";

    @Test void walkthroughHasPromptStyleAndExplicitPartialMockProvenance() {
        String metadata="{\"title\":\"Save order\",\"ideProject\":\"Order App\",\"keyPoints\":[\"Call order\",\"Limits\",\"Declared mocks\"],"
            +"\"mockedMethods\":[{\"className\":\"example.Service\",\"methodName\":\"save\",\"kind\":\"overridden\",\"notes\":\"Fixture override\"}]}";
        String html=TraceReport.render(TRACE,metadata,"html");
        for(String required:new String[]{"<!doctype html>","#0b0d10","#12161b","max-width:1100px","Key Highlights","Narrative Flow",
                "Implementation References","Classes &amp; Methods Mocked/Overridden","Declared overridden","Fixture override","Calls without a recorded exit: 1",
                "jetbrains://idea/navigate/reference?project=Order%20App&amp;path=src%2Fmain%2Fjava%2Fexample%2FApp.java%3A12",
                "href=\"https://github.com/nikcross/java-trail\"","href=\"https://tools.wet-wired.com\"","No test pass/fail outcome is inferred"})
            assertTrue(html.contains(required),required);
        assertFalse(html.contains("<script"));
    }

    @Test void escapesMetadataAndTraceValuesAndNeverCreatesJavascriptLinks() {
        String metadata="{\"title\":\"<img src=x onerror=alert(1)>\",\"summary\":\"</p><script>alert(1)</script>\",\"sourceRoot\":\"javascript:alert(1)\"}";
        String html=TraceReport.render(TRACE.replace("example.Service","evil<script>"),metadata,"html");
        assertFalse(html.contains("<script>")); assertFalse(html.contains("<img src=x")); assertFalse(html.contains("href=\"javascript:"));
        assertTrue(html.contains("&lt;script&gt;"));
        assertFalse(html.contains("Classes &amp; Methods Mocked/Overridden"));
    }

    @Test void textIncludesTreeCountsAndIncompleteCallsWithoutInventedOutcomes() {
        String text=TraceReport.render(TRACE,null,"text");
        assertTrue(text.contains("ENTER example.Service.save"));
        assertTrue(text.contains("entries=1 exits=1 duration=7 ms"));
        assertTrue(text.contains("Calls without recorded exit: 1"));
        assertFalse(text.contains("mock status unknown"));
        assertThrows(IllegalArgumentException.class,()->TraceReport.render(TRACE,null,"pdf"));
        assertThrows(IllegalArgumentException.class,()->TraceReport.render("{\"eventsRetained\":\"false\",\"events\":[]}",null,"html"));
    }

    @Test void offlineCliCreatesReportAndRefusesToOverwriteInput() throws Exception {
        Path input=temp.resolve("trace.json"), output=temp.resolve("reports/trace.html"); Files.writeString(input,TRACE);
        ByteArrayOutputStream error=new ByteArrayOutputStream();
        assertEquals(0,JavaTrailCli.run(new String[]{"report",input.toString(),"--output",output.toString()},System.out,new PrintStream(error)));
        assertTrue(Files.readString(output).contains("Narrative Flow"));
        assertEquals(2,JavaTrailCli.run(new String[]{"report",input.toString(),"--output",input.toString()},System.out,new PrintStream(error)));
        assertEquals(TRACE,Files.readString(input));
    }
    @Test void absentOrEmptyMockDeclarationsOmitSectionAndUnknownLabelsInBothFormats() {
        for(String metadata:new String[]{null,"{}","{\"mockedMethods\":[]}"}) {
            String html=TraceReport.render(TRACE,metadata,"html");
            String text=TraceReport.render(TRACE,metadata,"text");
            assertFalse(html.contains("Mocked/Overridden"));
            assertFalse(html.contains("Not supplied"));
            assertFalse(html.contains("mock/override status"));
            assertTrue(html.contains("class=\"columns single\""));
            assertTrue(html.contains("Classes &amp; Methods Called"));
            assertFalse(text.contains("Mocked/Overridden"));
            assertFalse(text.contains("mock status unknown"));
            assertFalse(text.contains("Mocked or overridden methods are declared"));
            assertTrue(text.contains("Narrative Flow / Call Tree"));
        }
    }

    @Test void suppliedDeclarationsRetainTextSectionAndUnknownStatusForUndeclaredCalls() {
        String metadata="{\"mockedMethods\":[{\"className\":\"example.Service\",\"methodName\":\"save\",\"kind\":\"mocked\"}]}";
        String text=TraceReport.render(TRACE,metadata,"text");
        String html=TraceReport.render(TRACE,metadata,"html");
        assertTrue(text.contains("Mocked/Overridden Methods"));
        assertTrue(text.contains("[declared mocked]"));
        assertTrue(text.contains("[mock status unknown]"));
        assertTrue(html.contains("Declared mocked"));
        assertTrue(html.contains("mock/override status was not declared"));
        assertFalse(html.contains("class=\"columns single\""));
    }

    @Test void sequenceDiagramShowsCallsObservedReturnsAndNarrativeLinks() {
        String html=TraceReport.render(sequenceTrace(),null,"html");
        assertTrue(html.contains("<h2>Interaction Diagram</h2>"));
        assertTrue(html.contains("<svg xmlns=\"http://www.w3.org/2000/svg\""));
        assertEquals(2,occurrences(html,"data-kind=\"call\""));
        assertEquals(1,occurrences(html,"data-kind=\"return\""));
        assertTrue(html.contains("href=\"#step-2\""));
        assertTrue(html.contains("data-from=\"example.App\" data-to=\"example.Service\""));
        assertTrue(html.contains("data-from=\"example.Service\" data-to=\"example.App\""));
        assertFalse(html.contains("data-kind=\"return\" data-from=\"example.App\""),"No invented root exit for a partial trace");
        assertFalse(html.contains("<script"));
    }

    @Test void diagramUsesUnknownCallerForFilteredDepthGapsAndUnmatchedExits() {
        String gap=sequenceTrace().replace("\"depth\":\"1\"","\"depth\":\"2\"");
        String html=TraceReport.render(gap,null,"html");
        assertTrue(html.contains("data-from=\"@unobserved\" data-to=\"example.Service\""));
        assertFalse(html.contains("data-from=\"example.App\" data-to=\"example.Service\""));
        String orphan="{\"events\":[{\"type\":\"EXIT\",\"className\":\"example.Service\",\"methodName\":\"save\",\"depth\":\"1\"}]}";
        assertTrue(TraceReport.render(orphan,null,"html").contains("data-from=\"example.Service\" data-to=\"@unobserved\""));
    }

    @Test void selfCallsUseLoopsAndDifferentThreadsDoNotShareCallStacks() {
        String self=TraceReport.render(sequenceTrace().replace("example.Service","example.App"),null,"html");
        assertEquals(2,occurrences(self,"data-self=\"true\""));
        assertTrue(self.contains(" h 58 v 20 h -58"));
        String otherThread=TRACE.replace("\"methodName\":\"save\",\"depth\"","\"methodName\":\"save\",\"thread\":\"worker\",\"depth\"");
        String html=TraceReport.render(otherThread,null,"html");
        assertTrue(html.contains("data-from=\"@unobserved\" data-to=\"example.Service\""));
        assertFalse(html.contains("data-from=\"example.App\" data-to=\"example.Service\""));
    }

    @Test void diagramBoundsLargeSequencesAndHandlesEmptyInput() {
        StringBuilder trace=new StringBuilder("{\"events\":[");
        for(int i=0;i<100;i++) {
            if(i>0)trace.append(',');
            trace.append("{\"type\":\"ENTER\",\"className\":\"example.App\",\"methodName\":\"run\",\"depth\":\"").append(i).append("\"}");
        }
        trace.append("]}");
        String html=TraceReport.render(trace.toString(),null,"html");
        assertEquals(80,occurrences(html,"class=\"interaction-message\""));
        assertTrue(html.contains("Showing the first 80 of 100 events"));
        assertTrue(html.contains("id=\"step-100\""),"Full narrative must remain available");
        String empty=TraceReport.render("{\"events\":[]}",null,"html");
        assertTrue(empty.contains("no interactions to draw"));
        assertFalse(empty.contains("<svg"));
    }

    @Test void diagramParticipantLimitAndMarkupEscapingRemainExplicit() {
        StringBuilder trace=new StringBuilder("{\"events\":[");
        for(int i=0;i<15;i++) {
            if(i>0)trace.append(',');
            trace.append("{\"type\":\"ENTER\",\"className\":\"example.Type").append(i)
                .append("\",\"methodName\":\"run\",\"depth\":\"").append(i).append("\"}");
        }
        trace.append("]}");
        String html=TraceReport.render(trace.toString(),null,"html");
        assertTrue(html.contains("participant limit reached"));
        assertEquals(12,occurrences(html,"class=\"interaction-participant\""));
        assertTrue(html.contains("id=\"step-15\""));
        String hostile=TraceReport.render(TRACE.replace("example.Service","evil<script>"),null,"html");
        assertFalse(hostile.contains("<script>"));
        assertTrue(hostile.contains("data-to=\"evil&lt;script&gt;\""));
    }

    private static String sequenceTrace() {
        // The older fixture omits child thread names; sequence relationships need explicit identity.
        return TRACE.replace("\"methodName\":\"save\",\"depth\"", "\"methodName\":\"save\",\"thread\":\"main\",\"depth\"");
    }

    private static int occurrences(String text,String value) {
        return (text.length()-text.replace(value,"").length())/value.length();
    }

}
