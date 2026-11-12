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
           .append("*{box-sizing:border-box}body{margin:0;background:#0b0d10;color:#e7ecf3}.wrap{max-width:1100px;margin:0 auto;padding:40px 24px 80px;font-family:system-ui;line-height:1.6}h1{font-size:clamp(1.6rem,4vw,2.4rem);line-height:1.2}h2{font-size:1.15rem}h3{font-size:1rem}a{color:#7fb9ff;overflow-wrap:anywhere}a:focus-visible,summary:focus-visible{outline:3px solid #56e0ba;outline-offset:4px}.panel,.callout{background:#12161b;border:1px solid #293440;border-radius:10px;padding:18px;margin:18px 0}.callout{border-left:4px solid #56e0ba}.muted{color:#acb8c8}.columns{display:grid;grid-template-columns:1fr 1fr;gap:18px}.columns.single{grid-template-columns:1fr}.interaction-scroll{overflow:auto;max-height:700px;max-width:100%;border:1px solid #293440;border-radius:8px;background:#0b0d10;margin:18px 0}.interaction-scroll:focus-visible{outline:3px solid #56e0ba;outline-offset:3px}.interaction-scroll svg{display:block;max-width:none;font-family:system-ui}.interaction-message:focus text,.interaction-message:hover text{fill:#fff;text-decoration:underline}.badge{display:inline-block;padding:2px 8px;border-radius:4px;background:#17392f;color:#83efcc}.declared{background:#23334b;color:#a8cfff}table{width:100%;border-collapse:collapse;table-layout:fixed;margin:20px 0}th,td{text-align:left;vertical-align:top;padding:16px;border:1px solid #293440;overflow-wrap:anywhere}th{background:#12161b;color:#83efcc}th:first-child{width:63%}code{font-size:.9em;overflow-wrap:anywhere}li{margin:6px 0}footer{border-top:1px solid #293440;margin-top:32px;padding-top:18px;font-size:.85rem;color:#acb8c8}summary{cursor:pointer;color:#7fb9ff}@media(max-width:680px){.wrap{padding:24px 14px 48px}.columns{grid-template-columns:1fr;gap:0}table,tbody,tr,td{display:block;width:100%}thead{display:none}td{padding:12px}td+td{border-top:0;margin-bottom:18px}}")
           .append("</style></head><body><main class=\"wrap\"><h1>").append(esc(d.title)).append(" — Test Flow Walkthrough</h1><p>")
           .append(esc(d.summary)).append("</p><section class=\"panel\"><h2>Context Overview</h2><ul><li>Start method: <code>")
           .append(esc(value(d.trace,"startClass","")+"."+value(d.trace,"startMethod",""))).append("</code></li><li>Completion: ")
           .append(esc(d.reason)).append("</li><li>Timed out: ").append(esc(value(d.trace,"timedOut","false")))
           .append(" · Truncated: ").append(esc(value(d.trace,"truncated","false"))).append(" · Recorded events: ").append(d.events.size())
           .append("</li><li>Calls without a recorded exit: ").append(d.unmatchedEntries)
           .append("</li></ul><p class=\"muted\">Single-thread observed flow; filtered calls may create depth gaps. Durations include debugger overhead.</p></section>")
           .append("<section class=\"callout\"><h2>Key Highlights</h2><ul>");
        for(String point:d.highlights) out.append("<li>").append(esc(point)).append("</li>");
        out.append("</ul></section>").append(interactionDiagram(d));
        out.append("<section><h2>Call Summary</h2><div class=\"")
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

    private static final String TRACE_ENTRY = "@trace-entry";
    private static final String UNOBSERVED_CALLER = "@unobserved";
    private static final int DIAGRAM_EVENTS = 80;
    private static final int DIAGRAM_PARTICIPANTS = 12;

    private static final class DiagramCall {
        final Event event;
        final String caller;
        DiagramCall(Event event, String caller) { this.event = event; this.caller = caller; }
    }

    private static final class DiagramMessage {
        final Event event;
        final String from, to;
        final int step;
        DiagramMessage(Event event, String from, String to, int step) {
            this.event = event; this.from = from; this.to = to; this.step = step;
        }
    }

    /** A bounded projection of recorded events, never an inferred application call graph. */
    private static String interactionDiagram(Data data) {
        StringBuilder out = new StringBuilder("<section id=\"interaction-diagram\" class=\"panel\"><h2>Interaction Diagram</h2>");
        if (data.events.isEmpty()) return out.append("<p>No method events were recorded, so there are no interactions to draw.</p></section>").toString();
        Map<String, Deque<DiagramCall>> stacks = new HashMap<>();
        Map<String, Integer> participants = new LinkedHashMap<>();
        List<DiagramMessage> messages = new ArrayList<>();
        boolean participantLimit = false;
        for (int i = 0; i < Math.min(data.events.size(), DIAGRAM_EVENTS); i++) {
            Event event = data.events.get(i);
            Deque<DiagramCall> stack = stacks.computeIfAbsent(event.thread, key -> new ArrayDeque<>());
            String from, to;
            if (event.type.equals("ENTER")) {
                while (!stack.isEmpty() && stack.peek().event.depth >= event.depth) stack.pop();
                from = event.depth == 0 ? TRACE_ENTRY
                        : !stack.isEmpty() && stack.peek().event.depth == event.depth - 1
                        ? stack.peek().event.className : UNOBSERVED_CALLER;
                to = event.className;
                stack.push(new DiagramCall(event, from));
            } else {
                DiagramCall match = null;
                for (DiagramCall call : stack) {
                    if (call.event.depth == event.depth && call.event.key().equals(event.key())) { match = call; break; }
                }
                from = event.className;
                to = match == null ? UNOBSERVED_CALLER : match.caller;
                if (match != null) while (stack.pop() != match) { /* discard unwound calls without inventing exits */ }
            }
            Set<String> additional = new HashSet<>(Arrays.asList(from, to));
            additional.removeAll(participants.keySet());
            if (participants.size() + additional.size() > DIAGRAM_PARTICIPANTS) { participantLimit = true; break; }
            if (!participants.containsKey(from)) participants.put(from, participants.size());
            if (!participants.containsKey(to)) participants.put(to, participants.size());
            messages.add(new DiagramMessage(event, from, to, i + 1));
        }
        out.append("<p>Observed calls between classes, in recorded order. Select an arrow to open its narrative step.</p>")
           .append("<p class=\"muted\">Solid emerald arrows show entries; dashed blue arrows show recorded exits, not return values. Self-calls loop back to the same class. Classes are grouped, not individual object instances. Trace entry marks the capture boundary. A filtered depth gap or unmatched exit uses an unobserved caller; missing exits are never drawn as returns.</p>");
        if (messages.size() < data.events.size()) out.append("<p class=\"callout\" data-diagram-limited=\"true\">Showing the first ")
                .append(messages.size()).append(" of ").append(data.events.size()).append(" events (diagram limits: ")
                .append(DIAGRAM_EVENTS).append(" events, ").append(DIAGRAM_PARTICIPANTS).append(" participants")
                .append(participantLimit ? "; participant limit reached" : "; event limit reached")
                .append("). The complete recorded flow remains in the narrative below. A diagram cutoff is not a trace timeout or event-storage truncation.</p>");
        int width = Math.max(700, participants.size() * 220 + 100);
        int height = 150 + messages.size() * 46;
        out.append("<div class=\"interaction-scroll\" tabindex=\"0\" role=\"region\" aria-label=\"Interaction sequence diagram; scroll to view classes and events\">")
           .append("<svg xmlns=\"http://www.w3.org/2000/svg\" role=\"group\" aria-labelledby=\"interaction-title interaction-description\" width=\"")
           .append(width).append("\" height=\"").append(height).append("\" viewBox=\"0 0 ").append(width).append(' ').append(height)
           .append("\"><title id=\"interaction-title\">Observed method interaction sequence</title><desc id=\"interaction-description\">Class lifelines with call and recorded-exit arrows. Each message links to its numbered narrative step. Unobserved callers represent gaps in the trace.</desc>")
           .append("<defs><marker id=\"interaction-call-arrow\" viewBox=\"0 0 10 10\" refX=\"9\" refY=\"5\" markerWidth=\"7\" markerHeight=\"7\" orient=\"auto-start-reverse\"><path d=\"M 0 0 L 10 5 L 0 10 z\" fill=\"#56e0ba\"/></marker>")
           .append("<marker id=\"interaction-return-arrow\" viewBox=\"0 0 10 10\" refX=\"9\" refY=\"5\" markerWidth=\"7\" markerHeight=\"7\" orient=\"auto-start-reverse\"><path d=\"M 0 0 L 10 5 L 0 10 z\" fill=\"#7fb9ff\"/></marker></defs>");
        for (Map.Entry<String, Integer> participant : participants.entrySet()) {
            int x = 110 + participant.getValue() * 220;
            String label = participantLabel(participant.getKey());
            out.append("<g class=\"interaction-participant\" data-participant=\"").append(esc(participant.getKey())).append("\"><title>")
               .append(esc(label)).append("</title><rect x=\"").append(x - 96).append("\" y=\"12\" width=\"192\" height=\"56\" rx=\"8\" fill=\"#12161b\" stroke=\"#3b4f68\"/>")
               .append("<text x=\"").append(x).append("\" y=\"36\" text-anchor=\"middle\" fill=\"#e7ecf3\" font-size=\"12\">")
               .append(esc(shortLabel(label.substring(label.lastIndexOf('.') + 1), 24))).append("</text><text x=\"").append(x)
               .append("\" y=\"55\" text-anchor=\"middle\" fill=\"#acb8c8\" font-size=\"10\">Participant ").append(participant.getValue() + 1)
               .append("</text><line x1=\"").append(x).append("\" y1=\"68\" x2=\"").append(x).append("\" y2=\"")
               .append(height - 20).append("\" stroke=\"#3b4f68\" stroke-dasharray=\"5 6\"/></g>");
        }
        for (int row = 0; row < messages.size(); row++) {
            DiagramMessage message = messages.get(row);
            Event event = message.event;
            int x1 = 110 + participants.get(message.from) * 220, x2 = 110 + participants.get(message.to) * 220;
            int y = 112 + row * 46;
            boolean returning = event.type.equals("EXIT"), self = x1 == x2;
            String kind = returning ? "return" : "call", color = returning ? "#7fb9ff" : "#56e0ba";
            String label = message.step + ". " + (returning ? "return " : "") + event.methodName
                    + (returning ? " · " + event.duration + " ms" : " · +" + event.elapsed + " ms");
            String description = "Step " + message.step + ": " + event.type + " " + event.label() + ", from "
                    + participantLabel(message.from) + " to " + participantLabel(message.to) + ", thread " + event.thread;
            out.append("<a class=\"interaction-message\" href=\"#step-").append(message.step).append("\" aria-label=\"")
               .append(esc(description)).append("\" data-kind=\"").append(kind).append("\" data-from=\"")
               .append(esc(message.from)).append("\" data-to=\"").append(esc(message.to)).append("\" data-self=\"").append(self)
               .append("\"><title>").append(esc(description)).append("</title><g fill=\"").append(color).append("\">");
            if (self) out.append("<path d=\"M ").append(x1).append(' ').append(y).append(" h 58 v 20 h -58\"");
            else out.append("<line x1=\"").append(x1).append("\" y1=\"").append(y).append("\" x2=\"").append(x2).append("\" y2=\"").append(y).append('"');
            out.append(" fill=\"none\" stroke=\"").append(color).append("\" stroke-width=\"2\" marker-end=\"url(#interaction-").append(kind).append("-arrow)\"")
               .append(returning ? " stroke-dasharray=\"6 4\"" : "").append("/>")
               .append("<text x=\"").append(self ? x1 + 66 : (x1 + x2) / 2).append("\" y=\"").append(y - 8)
               .append("\" text-anchor=\"").append(self ? "start" : "middle").append("\" font-size=\"11\">")
               .append(esc(shortLabel(label, self ? 22 : 48))).append("</text></g></a>");
        }
        out.append("</svg></div><details><summary>Participants (full class names)</summary><ol>");
        for (String participant : participants.keySet()) out.append("<li><code>").append(esc(participantLabel(participant))).append("</code></li>");
        return out.append("</ol></details></section>").toString();
    }

    private static String participantLabel(String key) {
        return key.equals(TRACE_ENTRY) ? "Trace entry" : key.equals(UNOBSERVED_CALLER) ? "Unobserved caller" : key;
    }
    private static String shortLabel(String label, int limit) {
        return label.length() <= limit ? label : label.substring(0, limit - 1) + "…";
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
