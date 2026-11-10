package org.onestonesoup.javatrail;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import org.onestonesoup.core.data.EntityTree;

/** CLI orchestration; capture, rendering and the OpenForum adapter share the engine APIs. */
public final class JavaTrailCli {
    private JavaTrailCli() { }
    public static void main(String[] args) {
        int status=run(args,System.out,System.err);
        if(status!=0) System.exit(status);
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        if(args.length==0 || args[0].equals("--help") || args[0].equals("-h")) { usage(out,""); return 0; }
        if(args[0].equals("--version")) { out.println("Java Trail 1.6.0"); return 0; }
        try {
            if(!Arrays.asList("watch","trace","report").contains(args[0])) throw new IllegalArgumentException("Unknown command: "+args[0]);
            Options opts=new Options(Arrays.copyOfRange(args,1,args.length));
            if(opts.flag("help")) { usage(out,args[0]); return 0; }
            switch(args[0]) {
                case "watch": return watch(opts,out,err);
                case "trace": return trace(opts,out,err);
                case "report": return report(opts,out);
                default: throw new IllegalArgumentException("Unknown command");
            }
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt(); err.println("Java Trail interrupted"); return 130;
        } catch(IllegalArgumentException e) {
            err.println("Java Trail: "+e.getMessage()); usage(err,args[0]); return 2;
        } catch(Exception e) {
            err.println("Java Trail: "+e.getClass().getSimpleName()+": "+e.getMessage()); return 1;
        }
    }

    private static int watch(Options o,PrintStream out,PrintStream err) throws Exception {
        o.allow("class","field","host","port","wait-class","duration","poll-interval");
        String cls,field,host,port;
        if(o.positional.size()==4) {
            if(o.has("class") || o.has("field") || o.has("host") || o.has("port")) throw new IllegalArgumentException("Use positional or named watch connection arguments, not both");
            cls=o.positional.get(0); field=o.positional.get(1); host=o.positional.get(2); port=o.positional.get(3);
        } else if(o.positional.isEmpty()) {
            cls=o.required("class"); field=o.required("field"); host=o.get("host","localhost"); port=o.get("port","5005");
        } else throw new IllegalArgumentException("watch requires <class> <field> <host> <port>, or named options");
        if(cls.isBlank() || field.isBlank() || host.isBlank()) throw new IllegalArgumentException("Class, field and host must not be blank");
        JavaTrailCam.Config.validatePort(port);
        long wait=o.nonnegative("wait-class",0), duration=o.nonnegative("duration",0), poll=o.nonnegative("poll-interval",5000);
        if(poll==0) throw new IllegalArgumentException("poll-interval must be positive");
        SimpleDebugVM vm=new JavaTrail().connect(host,port);
        Thread shutdown=new Thread(vm::disconnect,"JavaTrail-watch-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdown);
        try {
            vm.watch(cls,field,wait);
            long start=System.nanoTime(), previousDropped=0;
            while(vm.isConnected()) {
                long elapsed=(System.nanoTime()-start)/1000000;
                if(duration>0 && elapsed>=duration) break;
                Thread.sleep(duration>0?Math.min(poll,duration-elapsed):poll);
                SimpleDebugVM.FieldChange[] changes=vm.checkForChanges();
                if(changes.length==0) out.println("No Changes");
                for(SimpleDebugVM.FieldChange change:changes) {
                    out.println("Class:"+change.className); out.println(" Field:"+change.fieldName);
                    out.println("  From:"+change.currentValue); out.println("  To:"+change.newValue);
                    for(String step:change.shortStack) out.println("    Stack:"+step);
                }
                long dropped=vm.getDroppedFieldChanges();
                if(dropped>previousDropped) err.println("Field event buffer limit reached; dropped "+(dropped-previousDropped)+" changes");
                previousDropped=dropped;
            }
            return 0;
        } finally {
            vm.disconnect();
            try { Runtime.getRuntime().removeShutdownHook(shutdown); } catch(IllegalStateException ignored) { }
        }
    }

    private static int trace(Options o,PrintStream out,PrintStream err) throws Exception {
        o.allow("host","port","output","max-events","max-depth","max-duration","await-completion","stream-jsonl","no-retain",
                "fail-on-timeout","report","report-output","metadata","ide-project");
        if(o.positional.size()!=1) throw new IllegalArgumentException("trace requires <config.json>");
        Path configPath=Paths.get(o.positional.get(0)).toAbsolutePath();
        Map<String,String> overrides=new HashMap<>();
        String[][] names={{"host","host"},{"port","port"},{"max-events","maxEvents"},{"max-depth","maxDepth"},
                {"max-duration","maxDurationMillis"},{"await-completion","awaitCompletionMillis"}};
        for(String[] pair:names) if(o.has(pair[0])) overrides.put(pair[1],o.get(pair[0],""));
        if(o.has("output")) overrides.put("outputFile",o.get("output","").equals("-")?"":Paths.get(o.get("output","")).toAbsolutePath().toString());
        JavaTrailCam.Config config=JavaTrailCam.Config.load(configPath).withOverrides(overrides);
        String format=o.get("report","");
        if(!format.isEmpty() && !Arrays.asList("html","text").contains(format)) throw new IllegalArgumentException("Report format must be html or text");
        if(format.isEmpty() && (o.has("report-output") || o.has("metadata") || o.has("ide-project"))) throw new IllegalArgumentException("Report options require --report html|text");
        boolean retain=!o.flag("no-retain");
        if(!retain && (!o.has("stream-jsonl") || !format.isEmpty())) throw new IllegalArgumentException("--no-retain requires --stream-jsonl and cannot generate a report");
        String metadata=metadata(o);
        TraceReport.render("{\"events\":[]}",metadata,"text"); // Validate metadata before connecting.
        Path output=config.getOutputFile()==null || config.getOutputFile().isEmpty()?null:JavaTrailCam.resolveOutput(configPath,config.getOutputFile());
        Path stream=o.has("stream-jsonl")?Paths.get(o.get("stream-jsonl","")).toAbsolutePath().normalize():null;
        Path reportPath=format.isEmpty()?null:Paths.get(o.get("report-output",configPath.toString()+ (format.equals("html")?".html":".txt"))).toAbsolutePath().normalize();
        distinctPaths(Arrays.asList(configPath,o.has("metadata")?Paths.get(o.get("metadata","")).toAbsolutePath():null),Arrays.asList(output,stream,reportPath));
        if(stream!=null) Files.createDirectories(stream.getParent());
        String json;
        try(BufferedWriter writer=stream==null?null:Files.newBufferedWriter(stream,StandardCharsets.UTF_8)) {
            Consumer<SimpleDebugVM.MethodTraceEvent> sink=writer==null?null:event->{
                try { writer.write(JavaTrailCam.eventJson(event)); writer.newLine(); writer.flush(); }
                catch(IOException e) { throw new UncheckedIOException(e); }
            };
            json=new JavaTrailCam().capture(config,sink,retain);
        }
        if(output!=null) { JavaTrailCam.writeOutput(output,json); out.println("Trace written to "+output); }
        else out.println(json);
        if(reportPath!=null) {
            JavaTrailCam.writeOutput(reportPath,TraceReport.render(json,metadata,format));
            err.println("Report written to "+reportPath);
        }
        EntityTree.TreeEntity result=TraceReport.parseObject(json,"trace").getRoot();
        if("true".equals(TraceReport.value(result,"failed","false"))) return 1;
        return o.flag("fail-on-timeout") && "true".equals(TraceReport.value(result,"timedOut","false"))?3:0;
    }

    private static int report(Options o,PrintStream out) throws IOException {
        o.allow("format","output","metadata","ide-project");
        if(o.positional.size()!=1) throw new IllegalArgumentException("report requires <trace.json>");
        Path input=Paths.get(o.positional.get(0)).toAbsolutePath();
        String content=TraceReport.render(Files.readString(input),metadata(o),o.get("format","html"));
        if(o.has("output") && !o.get("output","").equals("-")) {
            Path output=Paths.get(o.get("output","")).toAbsolutePath();
            distinctPaths(Arrays.asList(input,o.has("metadata")?Paths.get(o.get("metadata","")).toAbsolutePath():null),Collections.singletonList(output));
            JavaTrailCam.writeOutput(output,content); out.println("Report written to "+output);
        } else out.print(content);
        return 0;
    }

    private static String metadata(Options o) throws IOException {
        String json=o.has("metadata")?Files.readString(Paths.get(o.get("metadata",""))):"{}";
        EntityTree.TreeEntity root=TraceReport.parseObject(json,"report metadata").getRoot();
        if(o.has("ide-project")) {
            EntityTree.TreeEntity child=root.getChild("ideProject");
            if(child==null) child=root.addChild("ideProject");
            child.setValue(o.get("ide-project",""));
        }
        return TrailJson.stringifyObject(root);
    }

    private static void distinctPaths(List<Path> inputs,List<Path> outputs) throws IOException {
        Set<Path> seen=new HashSet<>();
        for(Path p:inputs) if(p!=null) seen.add(p.toRealPath());
        for(Path p:outputs) if(p!=null) {
            Path resolved=Files.exists(p)?p.toRealPath():canonicalFuturePath(p);
            if(!seen.add(resolved)) throw new IllegalArgumentException("Input/output paths must be distinct: "+p);
        }
    }

    private static Path canonicalFuturePath(Path path) throws IOException {
        Path absolute=path.toAbsolutePath().normalize(), ancestor=absolute;
        while(!Files.exists(ancestor)) ancestor=ancestor.getParent();
        return ancestor.toRealPath().resolve(ancestor.relativize(absolute)).normalize();
    }

    private static void usage(PrintStream out,String command) {
        out.println("Usage: java -jar java-trail-1.6.0-cli.jar <command>");
        if(command.isEmpty() || command.equals("watch")) {
            out.println("  watch <class> <field> <host> <port>  Watch field changes until Ctrl+C");
            out.println("  watch --class NAME --field NAME [--host localhost] [--port 5005]");
            out.println("    --wait-class MS --duration MS --poll-interval MS (default 5000)");
        }
        if(command.isEmpty() || command.equals("trace")) {
            out.println("  trace <config.json>                 Trace methods to JSON");
            out.println("    --host HOST --port PORT --output FILE|- --max-events N --max-depth N");
            out.println("    --max-duration MS --await-completion MS --fail-on-timeout (exit 3)");
            out.println("    --stream-jsonl FILE [--no-retain] --report html|text --report-output FILE");
            out.println("    --metadata FILE --ide-project NAME");
        }
        if(command.isEmpty() || command.equals("report")) {
            out.println("  report <trace.json> --format html|text [--output FILE|-]");
            out.println("    --metadata FILE --ide-project NAME (offline; no debugger connection)");
        }
        out.println("  --help / <command> --help           Show help");
        out.println("  --version                          Show version");
    }

    private static final class Options {
        final List<String> positional=new ArrayList<>();
        final Map<String,String> values=new LinkedHashMap<>();
        final Set<String> flags=new HashSet<>();
        static final Set<String> BOOLEAN=new HashSet<>(Arrays.asList("help","no-retain","fail-on-timeout"));
        Options(String[] args) {
            for(int i=0;i<args.length;i++) {
                String token=args[i];
                if(token.equals("-h")) token="--help";
                if(!token.startsWith("--")) { positional.add(token); continue; }
                String key=token.substring(2);
                if(values.containsKey(key)||flags.contains(key)) throw new IllegalArgumentException("Duplicate option --"+key);
                if(BOOLEAN.contains(key)) flags.add(key);
                else {
                    if(++i>=args.length || args[i].startsWith("--")) throw new IllegalArgumentException("Missing value for --"+key);
                    values.put(key,args[i]);
                }
            }
        }
        void allow(String... allowed) {
            Set<String> known=new HashSet<>(Arrays.asList(allowed)); known.add("help");
            for(String key:values.keySet()) if(!known.contains(key)) throw new IllegalArgumentException("Unknown option --"+key);
            for(String key:flags) if(!known.contains(key)) throw new IllegalArgumentException("Unknown option --"+key);
        }
        boolean has(String key) { return values.containsKey(key); }
        boolean flag(String key) { return flags.contains(key); }
        String get(String key,String fallback) { return values.getOrDefault(key,fallback); }
        String required(String key) { if(!has(key)) throw new IllegalArgumentException("Missing --"+key); return get(key,""); }
        long nonnegative(String key,long fallback) {
            try { long n=Long.parseLong(get(key,Long.toString(fallback))); if(n<0) throw new NumberFormatException(); return n; }
            catch(NumberFormatException e) { throw new IllegalArgumentException("--"+key+" must be a nonnegative integer"); }
        }
    }
}
