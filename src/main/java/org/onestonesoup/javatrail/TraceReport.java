package org.onestonesoup.javatrail;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.onestonesoup.core.data.EntityTree;

/** Offline rendering of observed events. It never guesses test assertions or mock provenance. */
public final class TraceReport {
    private TraceReport() { }

    static EntityTree parseObject(String json, String label) {
        return TrailJson.parseObject(label, json);
    }

    static String value(EntityTree.TreeEntity node, String name, String fallback) {
        EntityTree.TreeEntity child = node.getChild(name);
        return child == null || child.getValue() == null ? fallback : child.getValue();
    }

    static List<String> strings(EntityTree.TreeEntity node, String name) {
        List<String> values = new ArrayList<>();
        EntityTree.TreeEntity array = node.getChild(name);
        if (array != null) for (EntityTree.TreeEntity item : array.getChildren())
            if (item.getValue() != null) values.add(item.getValue());
        return values;
    }

    public static String render(String traceJson, String metadataJson, String format) {
        Data data = new Data(parseObject(traceJson, "trace").getRoot(),
                parseObject(metadataJson == null ? "{}" : metadataJson, "report metadata").getRoot());
        if ("html".equals(format)) return html(data);
        if ("text".equals(format)) return text(data);
        throw new IllegalArgumentException("Report format must be html or text");
    }

    private static final class Event {
        final String type, className, methodName, signature, thread;
        final int depth, line;
        final long elapsed, duration;
        Event(EntityTree.TreeEntity node) {
            type = value(node,"type",""); className = value(node,"className","");
            methodName = value(node,"methodName",""); signature = value(node,"signature","");
            thread = value(node,"thread",""); depth = integer(node,"depth",0); line = integer(node,"lineNumber",-1);
            elapsed = number(node,"elapsedMillis",0); duration = number(node,"durationMillis",0);
            if ((!"ENTER".equals(type) && !"EXIT".equals(type)) || className.isBlank() || methodName.isBlank() || depth < 0)
                throw new IllegalArgumentException("Invalid trace event");
        }
        String label() { return className + "." + methodName + signature; }
        String key() { return label(); }
    }

    private static long number(EntityTree.TreeEntity node, String field, long fallback) {
        try { return Long.parseLong(value(node, field, Long.toString(fallback))); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid event field " + field); }
    }
    private static int integer(EntityTree.TreeEntity node, String field, int fallback) {
        long value = number(node, field, fallback);
        if (value > Integer.MAX_VALUE || value < Integer.MIN_VALUE) throw new IllegalArgumentException("Invalid event field " + field);
        return (int) value;
    }

    private static final class MockDeclaration {
        final String className, methodName, signature, kind, notes;
        MockDeclaration(EntityTree.TreeEntity node) {
            className=value(node,"className",""); methodName=value(node,"methodName","");
            signature=value(node,"signature",""); kind=value(node,"kind","mocked"); notes=value(node,"notes","");
            if(className.isBlank() || methodName.isBlank() || !(kind.equals("mocked") || kind.equals("overridden")))
                throw new IllegalArgumentException("Mock declarations require className, methodName and kind mocked/overridden");
        }
        boolean matches(Event e) { return className.equals(e.className) && methodName.equals(e.methodName)
                && (signature.isEmpty() || signature.equals(e.signature)); }
        String label() { return className+"."+methodName+signature; }
    }

    private static final class Stats {
        int entries, exits;
        long duration;
        final Event first;
        Stats(Event event) { first=event; }
    }

    private static final class Data {
        final EntityTree.TreeEntity trace, meta;
        final List<Event> events=new ArrayList<>();
        final Map<String,Stats> methods=new LinkedHashMap<>();
        final List<MockDeclaration> mocks=new ArrayList<>();
        final List<String> highlights;
        final String title, summary, project, sourceRoot, reason;
        int unmatchedEntries;
        final Set<Event> incomplete = Collections.newSetFromMap(new IdentityHashMap<>());
        Data(EntityTree.TreeEntity trace, EntityTree.TreeEntity meta) {
            this.trace=trace; this.meta=meta;
            EntityTree.TreeEntity array=trace.getChild("events");
            if(array==null) throw new IllegalArgumentException("Trace JSON must contain an events array");
            if("false".equals(value(trace,"eventsRetained","true")))
                throw new IllegalArgumentException("This trace did not retain events; generate reports from a retained trace");
            Map<String,Deque<Event>> open=new HashMap<>();
            for(EntityTree.TreeEntity node:array.getChildren()) {
                Event e=new Event(node); events.add(e);
                Stats stat=methods.computeIfAbsent(e.key(),k->new Stats(e));
                String invocation=e.key()+"#"+e.depth+"#"+e.thread;
                if(e.type.equals("ENTER")) { stat.entries++; open.computeIfAbsent(invocation,k->new ArrayDeque<>()).push(e); }
                else { stat.exits++; stat.duration+=e.duration;
                    Deque<Event> stack=open.get(invocation); if(stack!=null && !stack.isEmpty()) stack.pop();
                }
            }
            for(Deque<Event> stack:open.values()) incomplete.addAll(stack);
            unmatchedEntries=incomplete.size();
            EntityTree.TreeEntity declared=meta.getChild("mockedMethods");
            if(declared!=null) for(EntityTree.TreeEntity node:declared.getChildren()) mocks.add(new MockDeclaration(node));
            title=value(meta,"title",value(trace,"startClass","Java Trail")+"."+value(trace,"startMethod","trace"));
            summary=value(meta,"summary","Observed runtime method flow. Entries and exits come from debugger events; test assertions and method bodies are not reconstructed.");
            project=value(meta,"ideProject",""); sourceRoot=value(meta,"sourceRoot","src/main/java");
            reason=value(trace,"completionReason","Unknown");
            highlights=strings(meta,"keyPoints");
            if(highlights.isEmpty()) {
                highlights.addAll(Arrays.asList("Observed call order, including thread and call depth.",
                        "Completion reason, timeouts and limits; incomplete calls remain explicit."));
                if(!mocks.isEmpty()) highlights.add("Mocked or overridden methods are declared metadata, never inferred from runtime events.");
            }
        }
        MockDeclaration mock(Event e) { for(MockDeclaration m:mocks) if(m.matches(e)) return m; return null; }
    }

    private static String text(Data d) {
        StringBuilder out=new StringBuilder(d.title+" — Trace Walkthrough\n\n"+d.summary+"\n\n");
        out.append("Completion: ").append(d.reason).append("\nTimed out: ").append(value(d.trace,"timedOut","false"))
           .append("\nTruncated: ").append(value(d.trace,"truncated","false")).append("\nEvents: ").append(d.events.size())
           .append("\nCalls without recorded exit: ").append(d.unmatchedEntries).append("\n\nKey Highlights\n");
        for(String point:d.highlights) out.append("- ").append(point).append('\n');
        out.append("\nCall Summary (inclusive observed durations; debugger overhead included)\n");
        for(Stats stat:d.methods.values()) out.append(stat.first.label()).append(" | entries=").append(stat.entries)
                .append(" exits=").append(stat.exits).append(" duration=").append(stat.duration).append(" ms\n");
        if(!d.mocks.isEmpty()) out.append("\nMocked/Overridden Methods (declared metadata)\n");
        for(MockDeclaration m:d.mocks) out.append(m.label()).append(" — declared ").append(m.kind).append("; ").append(m.notes).append('\n');
        out.append("\nNarrative Flow / Call Tree\n");
        if(d.events.isEmpty()) out.append("No method events were recorded.\n");
        int step=0;
        for(Event e:d.events) {
            MockDeclaration m=d.mock(e);
            out.append(++step).append(". ").append("  ".repeat(Math.min(e.depth,40))).append(e.type).append(' ').append(e.label())
               .append(" @ line ").append(e.line).append(" [thread=").append(e.thread).append(", depth=").append(e.depth)
               .append(", +").append(e.elapsed).append(" ms");
            if(e.type.equals("EXIT")) out.append(", duration=").append(e.duration).append(" ms");
            out.append("]").append(m==null ? (d.mocks.isEmpty() ? "" : " [mock status unknown]") : " [declared "+m.kind+"]")
               .append(d.incomplete.contains(e)?" [no recorded exit]":"").append('\n');
        }
        out.append("\nSummary: ").append(d.reason).append(". ").append(d.unmatchedEntries)
           .append(" calls have no recorded exit; this can result from limits, cancellation or disconnection.\n");
        return out.toString();
    }

    private static String html(Data d) {
        StringBuilder out=new StringBuilder("<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><title>");
        out.append(esc(d.title)).append(" — Test Flow Walkthrough</title><style>")
           .append("*{box-sizing:border-box}body{margin:0;background:#0b0d10;color:#e7ecf3}.wrap{max-width:1100px;margin:0 auto;padding:40px 24px 80px;font-family:system-ui;line-height:1.6}h1{font-size:clamp(1.6rem,4vw,2.4rem);line-height:1.2}h2{font-size:1.15rem}h3{font-size:1rem}a{color:#7fb9ff;overflow-wrap:anywhere}a:focus-visible,summary:focus-visible{outline:3px solid #56e0ba;outline-offset:4px}.panel,.callout{background:#12161b;border:1px solid #293440;border-radius:10px;padding:18px;margin:18px 0}.callout{border-left:4px solid #56e0ba}.muted{color:#acb8c8}.columns{display:grid;grid-template-columns:1fr 1fr;gap:18px}.columns.single{grid-template-columns:1fr}.badge{display:inline-block;padding:2px 8px;border-radius:4px;background:#17392f;color:#83efcc}.declared{background:#23334b;color:#a8cfff}table{width:100%;border-collapse:collapse;table-layout:fixed;margin:20px 0}th,td{text-align:left;vertical-align:top;padding:16px;border:1px solid #293440;overflow-wrap:anywhere}th{background:#12161b;color:#83efcc}th:first-child{width:63%}code{font-size:.9em;overflow-wrap:anywhere}li{margin:6px 0}footer{border-top:1px solid #293440;margin-top:32px;padding-top:18px;font-size:.85rem;color:#acb8c8}summary{cursor:pointer;color:#7fb9ff}@media(max-width:680px){.wrap{padding:24px 14px 48px}.columns{grid-template-columns:1fr;gap:0}table,tbody,tr,td{display:block;width:100%}thead{display:none}td{padding:12px}td+td{border-top:0;margin-bottom:18px}}")
           .append("</style></head><body><main class=\"wrap\"><h1>").append(esc(d.title)).append(" — Test Flow Walkthrough</h1><p>")
           .append(esc(d.summary)).append("</p><section class=\"panel\"><h2>Context Overview</h2><ul><li>Start method: <code>")
           .append(esc(value(d.trace,"startClass","")+"."+value(d.trace,"startMethod",""))).append("</code></li><li>Completion: ")
           .append(esc(d.reason)).append("</li><li>Timed out: ").append(esc(value(d.trace,"timedOut","false")))
           .append(" · Truncated: ").append(esc(value(d.trace,"truncated","false"))).append(" · Recorded events: ").append(d.events.size())
           .append("</li><li>Calls without a recorded exit: ").append(d.unmatchedEntries)
           .append("</li></ul><p class=\"muted\">Single-thread observed flow; filtered calls may create depth gaps. Durations include debugger overhead.</p></section>")
           .append("<section class=\"callout\"><h2>Key Highlights</h2><ul>");
        for(String point:d.highlights) out.append("<li>").append(esc(point)).append("</li>");
        out.append("</ul></section><section><h2>Call Summary</h2><div class=\"")
           .append(d.mocks.isEmpty() ? "columns single" : "columns")
           .append("\"><div class=\"panel\"><h3>Classes &amp; Methods Called</h3><ul>");
        for(Stats stat:d.methods.values()) out.append("<li>").append(reference(d,stat.first)).append("<br><span class=\"muted\">")
                .append(stat.entries).append(" entries · ").append(stat.exits).append(" exits · ").append(stat.duration).append(" ms inclusive</span></li>");
        if(d.methods.isEmpty()) out.append("<li>No method events recorded.</li>");
        out.append("</ul></div>");
        if(!d.mocks.isEmpty()) {
            out.append("<div class=\"panel\"><h3>Classes &amp; Methods Mocked/Overridden</h3><ul>");
            for(MockDeclaration m:d.mocks) {
                boolean observed=d.events.stream().anyMatch(m::matches);
                out.append("<li>").append(methodReference(d,m.className,m.label(),-1)).append(" <span class=\"badge declared\">Declared ").append(esc(m.kind))
                   .append("</span><p>").append(esc(m.notes)).append("</p><p class=\"muted\">")
                   .append(observed ? "Corresponding events observed; declaration supplied by the report author." : "Declared but no corresponding event was recorded.").append("</p></li>");
            }
            out.append("</ul></div>");
        }
        out.append("</div></section><table><thead><tr><th scope=\"col\">Narrative Flow</th><th scope=\"col\">Implementation References</th></tr></thead><tbody>");
        int step=0;
        for(Event e:d.events) {
            MockDeclaration m=d.mock(e);
            out.append("<tr><td><section id=\"step-").append(++step).append("\"><h2>").append(step).append(". ")
               .append(e.type.equals("ENTER") ? "Enter " : "Exit ").append(esc(e.className+"."+e.methodName)).append("</h2><p>At +")
               .append(e.elapsed).append(" ms, thread <code>").append(esc(e.thread)).append("</code> ")
               .append(e.type.equals("ENTER") ? "entered " : "exited ").append(reference(d,e)).append(" at call depth ").append(e.depth).append(".</p>");
            if(d.incomplete.contains(e)) out.append("<p class=\"callout\">No matching EXIT was recorded for this call. Its outcome and duration are unknown.</p>");
            if(e.type.equals("EXIT")) out.append("<p>Observed duration: ").append(e.duration).append(" ms, including debugger overhead.</p>");
            if(m!=null) out.append("<p><span class=\"badge declared\">Declared ").append(esc(m.kind)).append("</span> ").append(esc(m.notes)).append("</p>");
            else if(!d.mocks.isEmpty()) out.append("<p class=\"muted\">Execution observed; mock/override status was not declared for this method.</p>");
            out.append("</section></td><td><h3>Key Implementation</h3><ul><li>").append(reference(d,e))
               .append("</li></ul><p class=\"muted\">Source line: ").append(e.line>0?Integer.toString(e.line):"unavailable")
               .append(". Source paths are derived from class names and the declared source root; verify them for nested classes or custom layouts.</p></td></tr>");
        }
        if(d.events.isEmpty()) out.append("<tr><td colspan=\"2\">No method events recorded. Check the completion reason and start-method settings.</td></tr>");
        out.append("</tbody></table><section class=\"panel\"><h2>Method Counts and Observed Durations</h2><p>Inclusive durations can overlap; they must not be added to estimate wall-clock time.</p><ul>");
        for(Stats stat:d.methods.values()) out.append("<li>").append(reference(d,stat.first)).append(": ").append(stat.entries).append(" entries, ")
            .append(stat.exits).append(" recorded exits, ").append(stat.duration).append(" ms total inclusive duration.</li>");
        out.append("</ul></section>");
        List<String> future=strings(d.meta,"futureConsiderations");
        if(!future.isEmpty()) { out.append("<section><h2>Future Considerations</h2><ul>"); for(String f:future) out.append("<li>").append(esc(f)).append("</li>"); out.append("</ul></section>"); }
        out.append("<section id=\"summary\"><h2>Summary</h2><p>").append(esc(d.reason)).append(". Captured ").append(d.events.size())
           .append(" events; ").append(d.unmatchedEntries).append(" calls have no recorded exit. Limits, cancellation or disconnection may leave partial calls. No test pass/fail outcome is inferred.</p></section>")
           .append("<footer>Generated via <a href=\"https://github.com/nikcross/ai-prompts\">https://github.com/nikcross/ai-prompts</a> — Prompt: software-developer/testing/flow-test-walkthrough.prompt.txt — Agent: Codex</footer></main></body></html>\n");
        return out.toString();
    }

    private static String reference(Data d, Event e) {
        return methodReference(d,e.className,e.label(),e.line);
    }
    private static String methodReference(Data d,String className,String methodLabel,int line) {
        String outer=className.split("\\$",2)[0];
        String path=d.sourceRoot+"/"+outer.replace('.','/')+".java";
        String label=esc(methodLabel+(line>0?":"+line:""));
        if(!path.matches("[a-zA-Z0-9_./$ -]+") || path.contains("..") || path.startsWith("/")) return "<code>"+label+"</code>";
        String href;
        if(!d.project.isBlank()) href="jetbrains://idea/navigate/reference?project="+url(d.project)+"&path="+url(path+(line>0?":"+line:""));
        else href=path;
        return "<a href=\""+esc(href)+"\"><code>"+label+"</code></a>";
    }
    private static String url(String value) { return URLEncoder.encode(value,StandardCharsets.UTF_8).replace("+","%20"); }
    private static String esc(String value) {
        return value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");
    }
}
