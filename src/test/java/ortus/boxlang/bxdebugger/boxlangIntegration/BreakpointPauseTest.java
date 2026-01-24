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
import org.eclipse.lsp4j.debug.InitializeRequestArguments;
import org.eclipse.lsp4j.debug.SetBreakpointsArguments;
import org.eclipse.lsp4j.debug.SetBreakpointsResponse;
import org.eclipse.lsp4j.debug.Source;
import org.eclipse.lsp4j.debug.SourceBreakpoint;
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
 * Test for breakpoint-pause-test.bxs integration
 * This test verifies that the debugger can set a breakpoint on line 6 of the add function
 * and properly pause execution when the breakpoint is hit, triggering a stopped event.
 */
public class BreakpointPauseTest {

	private static final Logger			LOGGER				= Logger.getLogger( BreakpointPauseTest.class.getName() );
	private static final int			TEST_PORT			= 5013;
	private static final int			STARTUP_TIMEOUT_MS	= 10000;

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
	@Timeout( value = 90, unit = TimeUnit.SECONDS )
	void testBreakpointPauseOnAddFunction() throws Exception {
		// Start the debug server
		CountDownLatch serverStartedLatch = new CountDownLatch( 1 );

		serverFuture = CompletableFuture.runAsync( () -> {
			try {
				serverStartedLatch.countDown();
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
		BreakpointCapturingClient breakpointClient = new BreakpointCapturingClient();

		try ( SocketChannel clientSocket = SocketChannel.open() ) {
			clientSocket.connect( new InetSocketAddress( "localhost", TEST_PORT ) );
			assertTrue( clientSocket.isConnected(), "Should connect to debug server" );

			Launcher<IDebugProtocolServer> launcher = DSPLauncher.createClientLauncher(
			    breakpointClient,
			    clientSocket.socket().getInputStream(),
			    clientSocket.socket().getOutputStream()
			);

			launcher.startListening();
			IDebugProtocolServer		debugServer	= launcher.getRemoteProxy();

			// Initialize debug session
			InitializeRequestArguments	initArgs	= new InitializeRequestArguments();
			initArgs.setClientID( "breakpoint-pause-test-client" );
			initArgs.setClientName( "Breakpoint Pause Test Integration" );
			initArgs.setAdapterID( "boxlang" );

			CompletableFuture<Capabilities>	initResponse	= debugServer.initialize( initArgs );
			Capabilities					capabilities	= initResponse.get( 5, TimeUnit.SECONDS );

			assertNotNull( capabilities, "Should receive capabilities" );

			// Set a breakpoint on line 6 (the return statement in the add function)
			SetBreakpointsArguments	breakpointArgs			= new SetBreakpointsArguments();
			Source					source					= new Source();
			Path					breakpointPauseTestFile	= Paths.get( "src/test/java/ortus/boxlang/bxdebugger/boxlangIntegration/breakpoint-pause-test.bxs" )
			    .toAbsolutePath();
			assertTrue( breakpointPauseTestFile.toFile().exists(), "breakpoint-pause-test.bxs should exist" );

			source.setPath( breakpointPauseTestFile.toString() );
			source.setName( "breakpoint-pause-test.bxs" );
			breakpointArgs.setSource( source );

			SourceBreakpoint sourceBreakpoint = new SourceBreakpoint();
			sourceBreakpoint.setLine( 7 ); // Line with return statement in add function
			breakpointArgs.setBreakpoints( new SourceBreakpoint[] { sourceBreakpoint } );

			CompletableFuture<SetBreakpointsResponse>	breakpointResponse	= debugServer.setBreakpoints( breakpointArgs );
			SetBreakpointsResponse						breakpointResult	= breakpointResponse.get( 5, TimeUnit.SECONDS );

			assertNotNull( breakpointResult, "Should receive breakpoint response" );
			assertTrue( breakpointResult.getBreakpoints().length > 0, "Should have breakpoints" );
			// Initially breakpoints may not be verified until the program runs
			assertNotNull( breakpointResult.getBreakpoints()[ 0 ], "First breakpoint should not be null" );

			LOGGER.info( "Breakpoint set successfully on line 6, launching program..." );

			// Launch the breakpoint-pause-test.bxs program which should hit the breakpoint
			Map<String, Object> launchArgs = new HashMap<>();
			launchArgs.put( "program", breakpointPauseTestFile.toString() );
			launchArgs.put( "type", "boxlang" );
			launchArgs.put( "name", "Debug breakpoint-pause-test.bxs" );

			CompletableFuture<Void> launchResponse = debugServer.launch( launchArgs );
			launchResponse.get( 10, TimeUnit.SECONDS );

			LOGGER.info( "Program launched, waiting for stopped event..." );

			// Send configuration done request
			LOGGER.info( "Sending configuration done request" );
			ConfigurationDoneArguments	configArgs			= new ConfigurationDoneArguments();
			CompletableFuture<Void>		configDoneResult	= debugServer.configurationDone( configArgs );
			configDoneResult.get( 5, TimeUnit.SECONDS );

			// Wait for the stopped event (breakpoint should be hit when add function is called)
			// BoxLang runtime takes significant time to initialize, so we need a longer timeout
			assertTrue( breakpointClient.waitForStoppedEvent( 60 ),
			    "Should receive stopped event when breakpoint is hit in add function" );

			// Verify the stopped event details
			StoppedEventArguments stoppedEvent = breakpointClient.getStoppedEvent();
			assertNotNull( stoppedEvent, "Stopped event should not be null" );
			assertTrue( "breakpoint".equals( stoppedEvent.getReason() ) || "pause".equals( stoppedEvent.getReason() ),
			    "Stopped reason should be 'breakpoint' or 'pause', but was: " + stoppedEvent.getReason() );

			LOGGER.info( "Successfully received stopped event: " + stoppedEvent.getReason() );
			LOGGER.info( "Breakpoint pause test integration completed successfully" );

		} catch ( Exception e ) {
			fail( e );
		}
	}

	/**
	 * Debug client that captures stopped events for testing breakpoint functionality
	 */
	private static class BreakpointCapturingClient implements IDebugProtocolClient {

		private static final Logger		CLIENT_LOGGER			= Logger.getLogger( BreakpointCapturingClient.class.getName() );
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
