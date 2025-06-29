package ortus.boxlang.moduleslug;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.lsp4j.debug.ExitedEventArguments;
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for exit event handling functionality in BoxDebugServer
 */
class ExitEventHandlingTest {

    private TestDebugClient testClient;
    private BoxDebugServer debugServer;

    @BeforeEach
    void setUp() {
        testClient = new TestDebugClient();
        debugServer = new BoxDebugServer();
        debugServer.connect(testClient);
    }

    @Test
    void testHandleProgramExitExists() {
        // Test that the handleProgramExit method exists and can be called
        assertDoesNotThrow(() -> {
            debugServer.handleProgramExit(0);
        });
    }

    @Test
    void testIsSessionCleanedExists() {
        // Test that the isSessionCleaned method exists and returns a boolean
        assertDoesNotThrow(() -> {
            boolean cleaned = debugServer.isSessionCleaned();
            // Initially should not be cleaned
            assertFalse(cleaned);
        });
    }

    @Test
    void testExitEventSending() throws InterruptedException {
        // Test that exit events are sent to the client
        CountDownLatch latch = new CountDownLatch(1);
        testClient.setExitLatch(latch);
        
        debugServer.handleProgramExit(0);
        
        // Wait for exit event
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Exit event should be sent");
        assertEquals(0, testClient.getLastExitCode());
    }

    @Test
    void testCleanupAfterExit() {
        // Test that cleanup happens after exit
        debugServer.handleProgramExit(0);
        
        // Should be cleaned up after exit
        assertTrue(debugServer.isSessionCleaned(), "Session should be cleaned after exit");
    }

    /**
     * Test client implementation for capturing events
     */
    private static class TestDebugClient implements IDebugProtocolClient {
        private int lastExitCode = -1;
        private CountDownLatch exitLatch;

        public void setExitLatch(CountDownLatch latch) {
            this.exitLatch = latch;
        }

        public int getLastExitCode() {
            return lastExitCode;
        }

        @Override
        public void exited(ExitedEventArguments args) {
            lastExitCode = args.getExitCode();
            if (exitLatch != null) {
                exitLatch.countDown();
            }
        }

        // Other required methods with empty implementations
        @Override
        public void initialized() {}

        @Override
        public void stopped(org.eclipse.lsp4j.debug.StoppedEventArguments args) {}

        @Override
        public void continued(org.eclipse.lsp4j.debug.ContinuedEventArguments args) {}

        @Override
        public void thread(org.eclipse.lsp4j.debug.ThreadEventArguments args) {}

        @Override
        public void output(org.eclipse.lsp4j.debug.OutputEventArguments args) {}

        @Override
        public void breakpoint(org.eclipse.lsp4j.debug.BreakpointEventArguments args) {}

        @Override
        public void module(org.eclipse.lsp4j.debug.ModuleEventArguments args) {}

        @Override
        public void loadedSource(org.eclipse.lsp4j.debug.LoadedSourceEventArguments args) {}

        @Override
        public void process(org.eclipse.lsp4j.debug.ProcessEventArguments args) {}

        @Override
        public void capabilities(org.eclipse.lsp4j.debug.CapabilitiesEventArguments args) {}

        @Override
        public void progressStart(org.eclipse.lsp4j.debug.ProgressStartEventArguments args) {}

        @Override
        public void progressUpdate(org.eclipse.lsp4j.debug.ProgressUpdateEventArguments args) {}

        @Override
        public void progressEnd(org.eclipse.lsp4j.debug.ProgressEndEventArguments args) {}

        @Override
        public void invalidated(org.eclipse.lsp4j.debug.InvalidatedEventArguments args) {}

        @Override
        public void memory(org.eclipse.lsp4j.debug.MemoryEventArguments args) {}

        // Add terminate method if it exists
        public void terminated(org.eclipse.lsp4j.debug.TerminatedEventArguments args) {}
    }
}
