package org.onestonesoup.javatrail;

import com.sun.jdi.*;
import com.sun.jdi.event.*;
import com.sun.jdi.request.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Timeout(10)
class DebuggerReliabilityTest {
    static MethodEntryEvent entry(String cls,String method,ThreadReference thread) {
        MethodEntryEvent event=mock(MethodEntryEvent.class); Method m=mock(Method.class); ReferenceType type=mock(ReferenceType.class);
        when(type.name()).thenReturn(cls); when(m.declaringType()).thenReturn(type); when(m.name()).thenReturn(method); when(m.signature()).thenReturn("()V");
        when(event.thread()).thenReturn(thread); when(event.method()).thenReturn(m); return event;
    }
    static SimpleDebugVM.MethodTraceSession session(int max,java.util.function.Consumer<SimpleDebugVM.MethodTraceEvent> sink,boolean retain,long duration) {
        EventRequestManager requests=mock(EventRequestManager.class);
        when(requests.createMethodEntryRequest()).thenAnswer(i->mock(MethodEntryRequest.class));
        when(requests.createMethodExitRequest()).thenAnswer(i->mock(MethodExitRequest.class));
        SimpleDebugVM.MethodTraceConfig config=new SimpleDebugVM.MethodTraceConfig("example.Root","run",null,List.of("example"),List.of(),true,-1,duration,max,sink,retain);
        SimpleDebugVM.MethodTraceSession s=new SimpleDebugVM.MethodTraceSession(config,requests); s.enableRequests(); return s;
    }

    @Test void eventSetResumesAfterHandlerFailureAndConnectionCloses() throws Exception {
        VirtualMachine machine=mock(VirtualMachine.class); EventQueue queue=mock(EventQueue.class); EventSet set=mock(EventSet.class);
        when(machine.eventQueue()).thenReturn(queue);
        when(queue.remove()).thenReturn(set).thenThrow(new VMDisconnectedException());
        when(set.iterator()).thenReturn(Collections.singletonList(mock(Event.class)).iterator());
        SimpleDebugVM vm=new SimpleDebugVM(machine) {
            @Override protected void handleEvent(Event event) { throw new IllegalStateException("Injected handler fault"); }
        };
        vm.run();
        verify(set).resume(); verify(machine).dispose(); assertFalse(vm.isConnected());
        vm.disconnect(); verify(machine,times(1)).dispose();
    }

    @Test void maximumEventsBoundsStorageAndStreamsInOrder() {
        List<SimpleDebugVM.MethodTraceEvent> streamed=new ArrayList<>();
        SimpleDebugVM.MethodTraceSession s=session(3,streamed::add,true,0);
        ThreadReference t=mock(ThreadReference.class); when(t.name()).thenReturn("main");
        for(int i=0;i<100;i++) s.onMethodEntry(entry("example.Root",i==0?"run":"child",t));
        assertEquals(3,s.getEvents().size()); assertEquals(3,streamed.size()); assertEquals(3,s.getEventCount());
        assertTrue(s.isCompleted()); assertTrue(s.isTruncated()); assertEquals("Reached maxEvents",s.getCompletionReason());
        assertEquals(0,streamed.get(0).depth); assertEquals(2,streamed.get(2).depth);
    }

    @Test void streamOnlyModeRetainsNoEventsButCountsThem() {
        AtomicInteger count=new AtomicInteger(); SimpleDebugVM.MethodTraceSession s=session(5,e->count.incrementAndGet(),false,0);
        ThreadReference t=mock(ThreadReference.class);
        for(int i=0;i<20;i++) s.onMethodEntry(entry("example.Root",i==0?"run":"child",t));
        assertEquals(5,count.get()); assertEquals(5,s.getEventCount()); assertTrue(s.getEvents().isEmpty()); assertFalse(s.isRetainingEvents());
    }

    @Test void idleSessionTimesOutEvenWithoutAnyDebuggerEvents() throws Exception {
        SimpleDebugVM.MethodTraceSession s=session(10,null,true,30);
        assertTrue(s.awaitCompletion(1000)); assertTrue(s.isTimedOut());
        assertEquals("Start method not reached within maxDurationMillis",s.getCompletionReason());
    }

    @Test void cancellationAndSnapshotsCanRaceWithEventsWithoutDeadlockOrPostCompletionWrites() throws Exception {
        SimpleDebugVM.MethodTraceSession s=session(10000,null,true,0); ThreadReference t=mock(ThreadReference.class);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        CountDownLatch go=new CountDownLatch(1);
        try {
            Future<?> producer=pool.submit(()->{ try { go.await(); } catch(InterruptedException e) { throw new RuntimeException(e); }
                for(int i=0;i<200;i++) s.onMethodEntry(entry("example.Root",i==0?"run":"child",t)); });
            Future<?> stopper=pool.submit(()->{go.countDown(); for(int i=0;i<100;i++) s.getEvents(); s.requestStop("Cancelled");});
            producer.get(5,TimeUnit.SECONDS); stopper.get(5,TimeUnit.SECONDS);
            int size=s.getEvents().size(); s.onMethodEntry(entry("example.Root","child",t));
            assertEquals(size,s.getEvents().size()); assertEquals("Cancelled",s.getCompletionReason());
        } finally { pool.shutdownNow(); }
    }

    @Test void startsWithClassFilterThenChangesToSelectedThreadWithoutClassFiltering() {
        EventRequestManager requests=mock(EventRequestManager.class); MethodEntryRequest start=mock(MethodEntryRequest.class), active=mock(MethodEntryRequest.class);
        MethodExitRequest exit=mock(MethodExitRequest.class); when(requests.createMethodEntryRequest()).thenReturn(start,active); when(requests.createMethodExitRequest()).thenReturn(exit);
        SimpleDebugVM.MethodTraceConfig config=new SimpleDebugVM.MethodTraceConfig("example.Root","run",null,List.of(),List.of(),false,-1,0);
        SimpleDebugVM.MethodTraceSession s=new SimpleDebugVM.MethodTraceSession(config,requests); s.enableRequests();
        verify(start).addClassFilter("example.Root"); verify(requests,never()).createMethodExitRequest();
        ThreadReference t=mock(ThreadReference.class); s.onMethodEntry(entry("example.Root","run",t));
        verify(requests).deleteEventRequest(start); verify(active).addThreadFilter(t); verify(exit).addThreadFilter(t);
        verify(active,never()).addClassFilter(anyString());
    }
    @Test void fieldEventBacklogIsBoundedAndDroppedCountIsExplicit() throws Exception {
        VirtualMachine machine=mock(VirtualMachine.class); SimpleDebugVM vm=new SimpleDebugVM(machine);
        ModificationWatchpointEvent event=mock(ModificationWatchpointEvent.class);
        Field field=mock(Field.class); ReferenceType type=mock(ReferenceType.class); ThreadReference thread=mock(ThreadReference.class);
        when(event.field()).thenReturn(field); when(field.declaringType()).thenReturn(type); when(type.name()).thenReturn("example.App");
        when(field.name()).thenReturn("counter"); when(event.thread()).thenReturn(thread); when(thread.frames(0,0)).thenReturn(List.of());
        for(int i=0;i<10005;i++) vm.handleEvent(event);
        assertEquals(10000,vm.checkForChanges().length); assertEquals(5,vm.getDroppedFieldChanges()); assertEquals(0,vm.checkForChanges().length);
    }

    @Test void sinkFailureCompletesTraceAsFailedAndStillResumesTargetEvents() throws Exception {
        VirtualMachine machine=mock(VirtualMachine.class); EventQueue queue=mock(EventQueue.class); EventSet set=mock(EventSet.class);
        EventRequestManager requests=mock(EventRequestManager.class);
        when(machine.eventQueue()).thenReturn(queue); when(machine.eventRequestManager()).thenReturn(requests);
        when(requests.createMethodEntryRequest()).thenAnswer(i->mock(MethodEntryRequest.class));
        when(requests.createMethodExitRequest()).thenAnswer(i->mock(MethodExitRequest.class));
        SimpleDebugVM vm=new SimpleDebugVM(machine);
        SimpleDebugVM.MethodTraceConfig config=new SimpleDebugVM.MethodTraceConfig("example.Root","run",null,List.of(),List.of(),false,-1,0,10,
            e->{throw new java.io.UncheckedIOException(new java.io.IOException("disk full"));},true);
        SimpleDebugVM.MethodTraceSession s=vm.startMethodTrace(config);
        Event event=entry("example.Root","run",mock(ThreadReference.class));
        when(set.iterator()).thenReturn(Collections.singletonList(event).iterator());
        when(queue.remove()).thenReturn(set).thenThrow(new VMDisconnectedException());
        vm.run(); verify(set).resume(); assertTrue(s.isCompleted()); assertTrue(s.getCompletionReason().startsWith("Processing failed:"));
    }

}
