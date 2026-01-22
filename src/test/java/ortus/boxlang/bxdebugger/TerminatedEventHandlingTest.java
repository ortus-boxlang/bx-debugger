package ortus.boxlang.bxdebugger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.lsp4j.debug.ExitedEventArguments;
import org.eclipse.lsp4j.debug.TerminatedEventArguments;
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for terminated event handling functionality in BoxDebugServer
 */
class TerminatedEventHandlingTest {

	private TestDebugClient	testClient;
	private BoxDebugServer	debugServer;

	@BeforeEach
	void setUp() {
		testClient	= new TestDebugClient();
		debugServer	= new BoxDebugServer();
		debugServer.connect( testClient );
	}

	@Test
	void testHandleTerminationExists() {
		// Test that the handleTermination method exists and can be called
		assertDoesNotThrow( () -> {
			debugServer.handleTermination();
		} );
	}

	@Test
	void testTerminatedEventSending() throws InterruptedException {
		// Test that terminated events are sent to the client
		CountDownLatch terminatedLatch = new CountDownLatch( 1 );
		testClient.setTerminatedLatch( terminatedLatch );

		debugServer.handleTermination();

		// Wait for terminated event
		assertTrue( terminatedLatch.await( 5, TimeUnit.SECONDS ), "Terminated event should be sent" );
		assertTrue( testClient.wasTerminatedEventSent(), "Terminated event should have been sent" );
	}

	@Test
	void testProgramExitSendsBothEvents() throws InterruptedException {
		// Test that program exit sends both terminated and exited events
		CountDownLatch	terminatedLatch	= new CountDownLatch( 1 );
		CountDownLatch	exitedLatch		= new CountDownLatch( 1 );
		testClient.setTerminatedLatch( terminatedLatch );
		testClient.setExitLatch( exitedLatch );

		debugServer.handleProgramExit( 0 );

		// Wait for both events
		assertTrue( terminatedLatch.await( 5, TimeUnit.SECONDS ), "Terminated event should be sent" );
		assertTrue( exitedLatch.await( 5, TimeUnit.SECONDS ), "Exited event should be sent" );
		assertTrue( testClient.wasTerminatedEventSent(), "Terminated event should have been sent" );
		assertEquals( 0, testClient.getLastExitCode(), "Exit code should be 0" );
	}

	@Test
	void testEventOrderingTerminatedBeforeExited() throws InterruptedException {
		// Test that terminated event is sent before exited event
		testClient.enableEventOrderTracking();

		debugServer.handleProgramExit( 0 );

		// Give events time to be sent
		Thread.sleep( 100 );

		assertTrue( testClient.wasTerminatedSentBeforeExited(),
		    "Terminated event should be sent before exited event" );
	}

	@Test
	void testMultipleTerminationEventsIgnored() throws InterruptedException {
		// Test that multiple termination calls only send one event
		CountDownLatch terminatedLatch = new CountDownLatch( 1 );
		testClient.setTerminatedLatch( terminatedLatch );

		debugServer.handleTermination();
		debugServer.handleTermination(); // Second call should be ignored

		// Wait for terminated event
		assertTrue( terminatedLatch.await( 5, TimeUnit.SECONDS ), "Terminated event should be sent" );
		assertEquals( 1, testClient.getTerminatedEventCount(), "Only one terminated event should be sent" );
	}

	@Test
	void testTerminationCleansUpSession() {
		// Test that termination properly cleans up the session
		debugServer.handleTermination();

		// Should be cleaned up after termination
		assertTrue( debugServer.isSessionCleaned(), "Session should be cleaned after termination" );
	}

	/**
	 * Test client implementation for capturing terminated and exited events
	 */
	private static class TestDebugClient implements IDebugProtocolClient {

		private int				lastExitCode			= -1;
		private CountDownLatch	exitLatch;
		private CountDownLatch	terminatedLatch;
		private boolean			terminatedEventSent		= false;
		private int				terminatedEventCount	= 0;
		private boolean			eventOrderTracking		= false;
		private long			terminatedEventTime		= 0;
		private long			exitedEventTime			= 0;

		public void setExitLatch( CountDownLatch latch ) {
			this.exitLatch = latch;
		}

		public void setTerminatedLatch( CountDownLatch latch ) {
			this.terminatedLatch = latch;
		}

		public int getLastExitCode() {
			return lastExitCode;
		}

		public boolean wasTerminatedEventSent() {
			return terminatedEventSent;
		}

		public int getTerminatedEventCount() {
			return terminatedEventCount;
		}

		public void enableEventOrderTracking() {
			this.eventOrderTracking = true;
		}

		public boolean wasTerminatedSentBeforeExited() {
			return eventOrderTracking && terminatedEventTime > 0 && exitedEventTime > 0
			    && terminatedEventTime < exitedEventTime;
		}

		@Override
		public void terminated( TerminatedEventArguments args ) {
			terminatedEventSent = true;
			terminatedEventCount++;
			if ( eventOrderTracking ) {
				terminatedEventTime = System.nanoTime();
			}
			if ( terminatedLatch != null ) {
				terminatedLatch.countDown();
			}
		}

		@Override
		public void exited( ExitedEventArguments args ) {
			lastExitCode = args.getExitCode();
			if ( eventOrderTracking ) {
				exitedEventTime = System.nanoTime();
			}
			if ( exitLatch != null ) {
				exitLatch.countDown();
			}
		}

		// Other required methods with empty implementations
		@Override
		public void initialized() {
		}

		@Override
		public void stopped( org.eclipse.lsp4j.debug.StoppedEventArguments args ) {
		}

		@Override
		public void continued( org.eclipse.lsp4j.debug.ContinuedEventArguments args ) {
		}

		@Override
		public void thread( org.eclipse.lsp4j.debug.ThreadEventArguments args ) {
		}

		@Override
		public void output( org.eclipse.lsp4j.debug.OutputEventArguments args ) {
		}

		@Override
		public void breakpoint( org.eclipse.lsp4j.debug.BreakpointEventArguments args ) {
		}

		@Override
		public void module( org.eclipse.lsp4j.debug.ModuleEventArguments args ) {
		}

		@Override
		public void loadedSource( org.eclipse.lsp4j.debug.LoadedSourceEventArguments args ) {
		}

		@Override
		public void process( org.eclipse.lsp4j.debug.ProcessEventArguments args ) {
		}

		@Override
		public void capabilities( org.eclipse.lsp4j.debug.CapabilitiesEventArguments args ) {
		}

		@Override
		public void progressStart( org.eclipse.lsp4j.debug.ProgressStartEventArguments args ) {
		}

		@Override
		public void progressUpdate( org.eclipse.lsp4j.debug.ProgressUpdateEventArguments args ) {
		}

		@Override
		public void progressEnd( org.eclipse.lsp4j.debug.ProgressEndEventArguments args ) {
		}

		@Override
		public void invalidated( org.eclipse.lsp4j.debug.InvalidatedEventArguments args ) {
		}

		@Override
		public void memory( org.eclipse.lsp4j.debug.MemoryEventArguments args ) {
		}
	}
}
