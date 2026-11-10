package org.onestonesoup.javatrail;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Timeout(10)
class CaptureLifecycleTest {
    private JavaTrailCam.Config config() {return JavaTrailCam.Config.fromString("{\"port\":\"5005\",\"startClass\":\"App\",\"startMethod\":\"run\"}");}
    @Test void sameCameraRejectsOverlappingCaptureAndClearsActiveState() throws Exception {
        CountDownLatch started=new CountDownLatch(1),finish=new CountDownLatch(1);
        SimpleDebugVM vm=mock(SimpleDebugVM.class);SimpleDebugVM.MethodTraceSession session=mock(SimpleDebugVM.MethodTraceSession.class);
        when(vm.startMethodTrace(any())).thenReturn(session);when(session.getCompletionReason()).thenReturn("Cancelled by caller");when(session.getEvents()).thenReturn(List.of());
        when(session.awaitCompletion(anyLong())).thenAnswer(i->{started.countDown();finish.await();return true;});
        JavaTrailCam cam=new JavaTrailCam(){@Override public SimpleDebugVM connect(String host,String port){return vm;}};
        ExecutorService executor=Executors.newSingleThreadExecutor();
        try {
            Future<String> capture=executor.submit(()->cam.capture(config(),null,true));
            assertTrue(started.await(3,TimeUnit.SECONDS));
            assertSame(vm,cam.getActiveVm());
            assertThrows(IllegalStateException.class,()->cam.capture(config(),null,true));
            finish.countDown();assertTrue(capture.get(3,TimeUnit.SECONDS).contains("Cancelled by caller"));
            assertNull(cam.getActiveVm());assertNull(cam.getActiveTraceSession());verify(vm).disconnect();
        } finally {finish.countDown();executor.shutdownNow();}
    }
    @Test void failedConnectionDoesNotLeaveTheCameraMarkedActive() {
        JavaTrailCam cam=new JavaTrailCam(){@Override public SimpleDebugVM connect(String host,String port)throws IOException {throw new IOException("offline");}};
        assertThrows(IOException.class,()->cam.capture(config(),null,true));
        assertThrows(IOException.class,()->cam.capture(config(),null,true));
        assertNull(cam.getActiveVm());
    }
}
