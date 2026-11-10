package org.onestonesoup.javatrail;

import org.junit.jupiter.api.Test;
import org.onestonesoup.core.data.EntityTree;
import static org.junit.jupiter.api.Assertions.*;
class TrailJsonTest {
    @Test void roundTripsNarrativeEscapesUnicodeAndEmptyContainers() {
        String json="{\"summary\":\"Commas, quotes \\\" and brackets [ ] { }, Unicode \\u2603 \\ud83d\\ude80\\nline two\",\"empty\":[],\"object\":{},\"number\":12,\"flag\":true,\"nothing\":null}";
        EntityTree tree=TrailJson.parseObject("metadata",json);
        String summary=tree.getRoot().getChild("summary").getValue();
        EntityTree again=TrailJson.parseObject("metadata",TrailJson.stringifyObject(tree.getRoot()));
        assertEquals(summary,again.getRoot().getChild("summary").getValue());
        assertTrue(again.getRoot().getChild("empty").getChildren().isEmpty());
        assertEquals("12",again.getRoot().getChild("number").getValue());
        assertTrue(TrailJson.stringifyObject(tree.getRoot()).contains("\"empty\":[]"));
    }
    @Test void rejectsMalformedOrAmbiguousInputs() {
        for(String json:new String[]{"[]","{bad}","{\"a\":1,}","{\"a\":1,\"a\":2}","{\"a\":01}","{\"a\":\"\\x\"}","{\"a\":true} trailing","{\"a\":\"line\nbreak\"}"})
            assertThrows(IllegalArgumentException.class,()->TrailJson.parseObject("input",json),json);
    }
    @Test void reportPreservesPunctuationInNarrativeAndDeclarations() {
        String metadata="{\"summary\":\"First, call the method; then, inspect [events].\",\"keyPoints\":[\"Order, and depth\"],\"mockedMethods\":[]}";
        String html=TraceReport.render(TraceReportTest.TRACE,metadata,"html");
        assertTrue(html.contains("First, call the method; then, inspect [events]."));assertTrue(html.contains("Order, and depth"));
    }
}
