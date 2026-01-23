package ortus.boxlang.bxdebugger.boxlangIntegration;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import org.eclipse.lsp4j.debug.Capabilities;
import org.eclipse.lsp4j.debug.ConfigurationDoneArguments;
import org.eclipse.lsp4j.debug.ExceptionInfoArguments;
import org.eclipse.lsp4j.debug.ExceptionInfoResponse;
import org.eclipse.lsp4j.debug.InitializeRequestArguments;
import org.eclipse.lsp4j.debug.SetExceptionBreakpointsArguments;
import org.eclipse.lsp4j.debug.SetExceptionBreakpointsResponse;
import org.eclipse.lsp4j.debug.StoppedEventArguments;
import org.eclipse.lsp4j.debug.launch.DSPLauncher;
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient;
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import ortus.boxlang.bxdebugger.BoxDebugger;

/**
 * Test for exception breakpoint functionality
 * This test verifies that the debugger can pause on BoxRuntimeException
 * when exception breakpoints are enabled.
 */
public class ExceptionBreakpointTest {

	private static final Logger			LOGGER				= Logger.getLogger( ExceptionBreakpointTest.class.getName() );
	private static final int			TEST_PORT			= 5014;
	private static final int			STARTUP_TIMEOUT_MS	= 100000;

	private ExecutorService				serverExecutor;
	private ExecutorService				testExecutor;
	private AtomicReference<Exception>	serverException		= new AtomicReference<>();
	private CompletableFuture<Void>		serverFuture;

	@BeforeEach
	void setUp() {
		serverExecutor	= Executors.newSingleThreadExecutor();
		testExecutor	= Executors.newCachedThreadPool();
		serverException.set( null );
	}

	@AfterEach
	void tearDown() {
		if ( serverFuture != null ) {
			serverFuture.cancel( true );
		}

		if ( serverExecutor != null ) {
			serverExecutor.shutdownNow();
			try {
				serverExecutor.awaitTermination( 2, TimeUnit.SECONDS );
			} catch ( InterruptedException e ) {
				Thread.currentThread().interrupt();
			}
		}

		if ( testExecutor != null ) {
			testExecutor.shutdownNow();
			try {
				testExecutor.awaitTermination( 2, TimeUnit.SECONDS );
			} catch ( InterruptedException e ) {
				Thread.currentThread().interrupt();
			}
		}
	}

	@Test
	@Timeout( value = 30, unit = TimeUnit.SECONDS )
	void testExceptionBreakpointPausesOnBoxRuntimeException() throws Exception {
		// Start the debug server
		CountDownLatch serverStartedLatch = new CountDownLatch( 1 );

		serverFuture = CompletableFuture.runAsync( () -> {
			try {
				serverStartedLatch.countDown();
				System.setProperty( "BOX_DEBUGGER_FALSEEXIT", "true" );
				BoxDebugger.main( new String[] { String.valueOf( TEST_PORT ) } );
			} catch ( Exception e ) {
				serverException.set( e );
				LOGGER.severe( "Server startup failed: " + e.getMessage() );
			}
		}, serverExecutor );

		// Wait for server to start
		assertTrue( serverStartedLatch.await( STARTUP_TIMEOUT_MS, TimeUnit.MILLISECONDS ),
		    "Debug server should start within timeout" );

		Thread.sleep( 1000 );

		if ( serverException.get() != null ) {
			fail( "Server failed to start: " + serverException.get().getMessage() );
		}

		// Create a test client that can capture stopped events
		ExceptionCapturingClient exceptionClient = new ExceptionCapturingClient();

		try ( SocketChannel clientSocket = SocketChannel.open() ) {
			clientSocket.connect( new InetSocketAddress( "localhost", TEST_PORT ) );
			assertTrue( clientSocket.isConnected(), "Should connect to debug server" );

			Launcher<IDebugProtocolServer> launcher = DSPLauncher.createClientLauncher(
			    exceptionClient,
			    clientSocket.socket().getInputStream(),
			    clientSocket.socket().getOutputStream()
			);

			launcher.startListening();
			IDebugProtocolServer		debugServer	= launcher.getRemoteProxy();

			// Initialize debug session
			InitializeRequestArguments	initArgs	= new InitializeRequestArguments();
			initArgs.setClientID( "exception-test-client" );
			initArgs.setClientName( "Exception Breakpoint Test Integration" );
			initArgs.setAdapterID( "boxlang" );

			CompletableFuture<Capabilities>	initResponse	= debugServer.initialize( initArgs );
			Capabilities					capabilities	= initResponse.get( 5, TimeUnit.SECONDS );

			assertNotNull( capabilities, "Should receive capabilities" );
			assertTrue( capabilities.getSupportsExceptionInfoRequest(), "Should support exception info request" );
			assertNotNull( capabilities.getExceptionBreakpointFilters(), "Should have exception breakpoint filters" );
			assertTrue( capabilities.getExceptionBreakpointFilters().length > 0, "Should have at least one exception filter" );

			LOGGER.info( "Capabilities received, exception support confirmed" );

			// Enable exception breakpoints for "caught" and "uncaught" filters
			SetExceptionBreakpointsArguments exceptionArgs = new SetExceptionBreakpointsArguments();
			exceptionArgs.setFilters( new String[] { "caught", "uncaught" } );

			CompletableFuture<SetExceptionBreakpointsResponse>	exceptionResponse	= debugServer.setExceptionBreakpoints( exceptionArgs );
			SetExceptionBreakpointsResponse						exceptionResult		= exceptionResponse.get( 5, TimeUnit.SECONDS );

			assertNotNull( exceptionResult, "Should receive exception breakpoints response" );

			LOGGER.info( "Exception breakpoints enabled, launching program..." );

			// Launch the exception-test.bxs program which should throw an exception
		Path exceptionTestFile = Paths.get( "src/test/java/ortus/boxlang/bxdebugger/boxlangIntegration/exception-test.bxs" )
		    .toAbsolutePath();
			assertTrue( exceptionTestFile.toFile().exists(), "exception-test.bxs should exist" );

			Map<String, Object> launchArgs = new HashMap<>();
			launchArgs.put( "program", exceptionTestFile.toString() );
			launchArgs.put( "type", "boxlang" );
			launchArgs.put( "name", "Debug exception-test.bxs" );

			CompletableFuture<Void> launchResponse = debugServer.launch( launchArgs );
			launchResponse.get( 10, TimeUnit.SECONDS );

			LOGGER.info( "Program launched, sending configuration done..." );

			// Send configuration done request
			ConfigurationDoneArguments	configArgs			= new ConfigurationDoneArguments();
			CompletableFuture<Void>		configDoneResult	= debugServer.configurationDone( configArgs );
			configDoneResult.get( 5, TimeUnit.SECONDS );

			LOGGER.info( "Waiting for stopped event due to exception..." );

			// Wait for the stopped event (exception should be caught)
			assertTrue( exceptionClient.waitForStoppedEvent( 1500 ),
			    "Should receive stopped event when exception is thrown" );

			// Verify the stopped event details
			StoppedEventArguments stoppedEvent = exceptionClient.getStoppedEvent();
			assertNotNull( stoppedEvent, "Stopped event should not be null" );
			assertTrue( "exception".equals( stoppedEvent.getReason() ),
			    "Stopped reason should be 'exception', but was: " + stoppedEvent.getReason() );

			LOGGER.info( "Successfully received stopped event for exception: " + stoppedEvent.getReason() );

			// Request exception info
			ExceptionInfoArguments exceptionInfoArgs = new ExceptionInfoArguments();
			exceptionInfoArgs.setThreadId( stoppedEvent.getThreadId() );

			CompletableFuture<ExceptionInfoResponse>	exceptionInfoResponse	= debugServer.exceptionInfo( exceptionInfoArgs );
			ExceptionInfoResponse						exceptionInfo			= exceptionInfoResponse.get( 5, TimeUnit.SECONDS );

			assertNotNull( exceptionInfo, "Exception info should not be null" );
			assertNotNull( exceptionInfo.getExceptionId(), "Exception ID should not be null" );
			assertNotNull( exceptionInfo.getDescription(), "Exception description should not be null" );

			LOGGER.info( "Exception info: id=" + exceptionInfo.getExceptionId() +
			    ", description=" + exceptionInfo.getDescription() );

			LOGGER.info( "Exception breakpoint test integration completed successfully" );

		} catch ( Exception e ) {
			fail( e );
		}
	}

	/**
	 * Debug client that captures stopped events for testing exception breakpoint functionality
	 */
	private static class ExceptionCapturingClient implements IDebugProtocolClient {

		private static final Logger		CLIENT_LOGGER			= Logger.getLogger( ExceptionCapturingClient.class.getName() );
		private final CountDownLatch	stoppedEventReceived	= new CountDownLatch( 1 );
		private StoppedEventArguments	stoppedEvent			= null;

		@Override
		public void stopped( StoppedEventArguments args ) {
			CLIENT_LOGGER.info( "Received stopped event: " + args.getReason() );
			this.stoppedEvent = args;
			stoppedEventReceived.countDown();
		}

		public boolean waitForStoppedEvent( int timeoutSeconds ) {
			try {
				return stoppedEventReceived.await( timeoutSeconds, TimeUnit.SECONDS );
			} catch ( InterruptedException e ) {
				Thread.currentThread().interrupt();
				return false;
			}
		}

		public StoppedEventArguments getStoppedEvent() {
			return stoppedEvent;
		}

		// Implement other required methods as no-ops
		@Override
		public void initialized() {
		}

		@Override
		public void continued( org.eclipse.lsp4j.debug.ContinuedEventArguments args ) {
		}

		@Override
		public void exited( org.eclipse.lsp4j.debug.ExitedEventArguments args ) {
		}

		@Override
		public void terminated( org.eclipse.lsp4j.debug.TerminatedEventArguments args ) {
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
