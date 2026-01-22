package ortus.boxlang.bxdebugger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.lsp4j.debug.DisconnectArguments;
import org.eclipse.lsp4j.debug.ExitedEventArguments;
import org.eclipse.lsp4j.debug.TerminatedEventArguments;
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for DAP disconnect handling in BoxDebugServer
 */
class DisconnectRequestHandlingTest {

	private TestDebugClient	testClient;
	private BoxDebugServer	debugServer;

	@BeforeEach
	void setUp() {
		testClient	= new TestDebugClient();
		debugServer	= new BoxDebugServer();
		debugServer.connect( testClient );
	}

	@Test
	void testDisconnectTerminateSendsBothEventsAndCleansUp() throws Exception {
		DisconnectArguments args = new DisconnectArguments();
		args.setTerminateDebuggee( true );

		CountDownLatch	terminatedLatch	= new CountDownLatch( 1 );
		CountDownLatch	exitedLatch		= new CountDownLatch( 1 );
		testClient.setTerminatedLatch( terminatedLatch );
		testClient.setExitLatch( exitedLatch );

		assertDoesNotThrow( () -> debugServer.disconnect( args ).join() );

		// If a VM isn't running in unit tests, we still ensure ordering via handleProgramExit
		assertTrue( terminatedLatch.await( 2, TimeUnit.SECONDS ), "terminated should be sent" );
		assertTrue( exitedLatch.await( 2, TimeUnit.SECONDS ), "exited should be sent" );
		assertTrue( debugServer.isSessionCleaned(), "session cleaned after disconnect terminate" );
	}

	@Test
	void testDisconnectDetachSendsTerminatedOnlyAndCleansUp() throws Exception {
		DisconnectArguments args = new DisconnectArguments();
		args.setTerminateDebuggee( false );

		CountDownLatch terminatedLatch = new CountDownLatch( 1 );
		testClient.setTerminatedLatch( terminatedLatch );
		testClient.enableCaptureExited( true );

		assertDoesNotThrow( () -> debugServer.disconnect( args ).join() );

		assertTrue( terminatedLatch.await( 2, TimeUnit.SECONDS ), "terminated should be sent" );
		// In detach mode we should not receive exited
		assertTrue( !testClient.wasExitedReceived(), "exited should not be sent on detach" );
		assertTrue( debugServer.isSessionCleaned(), "session cleaned after detach" );
	}

	@Test
	void testDisconnectRestartCleansUp() throws InterruptedException {
		DisconnectArguments args = new DisconnectArguments();
		args.setRestart( true );

		CountDownLatch terminatedLatch = new CountDownLatch( 1 );
		testClient.setTerminatedLatch( terminatedLatch );

		assertDoesNotThrow( () -> debugServer.disconnect( args ).join() );

		// We send terminated and cleanup, client will re-initialize/launch
		assertTrue( terminatedLatch.await( 2, TimeUnit.SECONDS ), "terminated should be sent on restart" );
		assertTrue( debugServer.isSessionCleaned(), "session cleaned after restart disconnect" );
	}

	private static class TestDebugClient implements IDebugProtocolClient {

		private CountDownLatch	exitLatch;
		private CountDownLatch	terminatedLatch;
		private boolean			exitedReceived	= false;
		private boolean			captureExited	= false;

		void setExitLatch( CountDownLatch latch ) {
			this.exitLatch = latch;
		}

		void setTerminatedLatch( CountDownLatch latch ) {
			this.terminatedLatch = latch;
		}

		void enableCaptureExited( boolean enable ) {
			this.captureExited = enable;
		}

		boolean wasExitedReceived() {
			return exitedReceived;
		}

		@Override
		public void terminated( TerminatedEventArguments args ) {
			if ( terminatedLatch != null )
				terminatedLatch.countDown();
		}

		@Override
		public void exited( ExitedEventArguments args ) {
			if ( captureExited )
				exitedReceived = true;
			if ( exitLatch != null )
				exitLatch.countDown();
		}

		// Unused callbacks
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
