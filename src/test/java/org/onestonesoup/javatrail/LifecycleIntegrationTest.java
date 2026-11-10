package org.onestonesoup.javatrail;

import java.io.*;
import java.net.ServerSocket;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class LifecycleIntegrationTest {
    static final String TARGET="org.onestonesoup.javatrail.sample.LifecycleApplication";
    static final class Target implements AutoCloseable {
        final int port; final Process process; final BufferedWriter input;
        Target() throws Exception {
            try(ServerSocket socket=new ServerSocket(0)) { port=socket.getLocalPort(); }
            process=new ProcessBuilder(Paths.get(System.getProperty("java.home"),"bin","java").toString(),
                    "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:"+port,
                    "-cp",Paths.get("target/test-classes").toAbsolutePath().toString(),TARGET).redirectErrorStream(true).start();
            input=new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
            BufferedReader output=new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line; do { line=output.readLine(); if(line==null) throw new IOException("Target exited before ready"); } while(!line.equals("ready"));
        }
        void command(String command) throws IOException { input.write(command+"\n"); input.flush(); }
        public void close() throws Exception { process.destroy(); if(!process.waitFor(2,TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(2,TimeUnit.SECONDS); } }
        SimpleDebugVM attach() throws Exception { return new JavaTrail().connect("127.0.0.1",Integer.toString(port)); }
    }
    static SimpleDebugVM.MethodTraceConfig config(String method,int max) {
        return new SimpleDebugVM.MethodTraceConfig(TARGET,method,null,List.of("org.onestonesoup.javatrail.sample"),List.of(),true,-1,3000,max,null,true);
    }

    @Test void registersWatchBeforeLateClassInitializersAndCanTimeout() throws Exception {
        try(Target target=new Target();SimpleDebugVM vm=target.attach()) {
            String late="org.onestonesoup.javatrail.sample.LateLoadedCounter";
            assertTrue(vm.getVirtualMachine().classesByName(late).isEmpty());
            ExecutorService pool=Executors.newSingleThreadExecutor();
            try {
                Future<?> watch=pool.submit(()->{try {return vm.watch(late,"counter",3000);}catch(Exception e){throw new RuntimeException(e);}});
                // Wait for the class-prepare request itself, rather than assuming a sleep was sufficient.
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
                while(vm.getVirtualMachine().eventRequestManager().classPrepareRequests().isEmpty() && System.nanoTime()<deadline) Thread.sleep(5);
                assertFalse(vm.getVirtualMachine().eventRequestManager().classPrepareRequests().isEmpty());
                target.command("late"); watch.get(5,TimeUnit.SECONDS);
                List<SimpleDebugVM.FieldChange> changes=new ArrayList<>();
                while(changes.size()<2 && System.nanoTime()<deadline+TimeUnit.SECONDS.toNanos(2)) { changes.addAll(Arrays.asList(vm.checkForChanges())); Thread.sleep(10); }
                assertTrue(changes.stream().anyMatch(c->c.currentValue.equals("0")&&c.newValue.equals("1")),"Must capture static initializer write");
                assertTrue(changes.stream().anyMatch(c->c.newValue.equals("2")),"Must capture subsequent write");
                assertThrows(IOException.class,()->vm.watch("never.Loaded","field",30));
            } finally { pool.shutdownNow(); }
        }
    }

    @Test void targetExitCompletesAnActiveTrace() throws Exception {
        try(Target target=new Target();SimpleDebugVM vm=target.attach()) {
            SimpleDebugVM.MethodTraceSession session=vm.startMethodTrace(config("exitFlow",100000)); target.command("exit");
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(!session.hasStarted() && System.nanoTime()<deadline) Thread.sleep(5);
            assertTrue(session.hasStarted()); target.process.destroy();
            session.awaitCompletion(5000);
            assertTrue(session.hasStarted()); assertFalse(session.isTimedOut()); assertEquals("VM disconnected",session.getCompletionReason());
            assertFalse(session.getEvents().isEmpty());
        }
    }

    @Test void highEventRateStopsAtBoundAndReleasesRequestsForAnotherTrace() throws Exception {
        try(Target target=new Target();SimpleDebugVM vm=target.attach()) {
            SimpleDebugVM.MethodTraceSession session=vm.startMethodTrace(config("flow",20)); target.command("flow"); session.awaitCompletion(5000);
            assertEquals(20,session.getEvents().size()); assertEquals("Reached maxEvents",session.getCompletionReason()); assertTrue(session.isTruncated());
            SimpleDebugVM.MethodTraceSession next=vm.startMethodTrace(config("flow",100000)); target.command("flow"); next.awaitCompletion(5000);
            assertEquals("Returned to start method",next.getCompletionReason()); assertEquals(402,next.getEvents().size());
        }
    }

    @Test void cancellationAndTargetExitWhileWaitingDoNotHang() throws Exception {
        try(Target target=new Target();SimpleDebugVM vm=target.attach()) {
            SimpleDebugVM.MethodTraceSession session=vm.startMethodTrace(config("neverCalled",100));
            session.requestStop("Cancelled by caller"); assertTrue(session.awaitCompletion(100)); assertTrue(session.getEvents().isEmpty());
            assertEquals("Cancelled by caller",session.getCompletionReason());
            SimpleDebugVM.MethodTraceSession waiting=vm.startMethodTrace(config("neverCalled",100)); target.process.destroy();
            waiting.awaitCompletion(5000); assertEquals("VM disconnected",waiting.getCompletionReason());
        }
    }

    @Test void connectionFailureIsReported() throws Exception {
        int port; try(ServerSocket socket=new ServerSocket(0)){port=socket.getLocalPort();}
        assertThrows(IOException.class,()->new JavaTrail().connect("127.0.0.1",Integer.toString(port)));
    }
}
