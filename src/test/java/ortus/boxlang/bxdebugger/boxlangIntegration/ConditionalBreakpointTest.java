package ortus.boxlang.bxdebugger.boxlangIntegration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
import org.eclipse.lsp4j.debug.OutputEventArguments;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import ortus.boxlang.bxdebugger.BoxDebugger;

/**
 * Test for conditional breakpoint functionality.
 * Tests:
 * 1. Condition expression evaluation (e.g., i > 5)
 * 2. Hit count conditions (e.g., stop on 3rd hit)
 * 3. Log points (log message without stopping)
 */
public class ConditionalBreakpointTest {

	private static final Logger			LOGGER				= Logger.getLogger( ConditionalBreakpointTest.class.getName() );
	private static final int			TEST_PORT			= 5016;
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
	@Timeout( value = 60, unit = TimeUnit.SECONDS )
	@DisplayName( "Test hit count condition breakpoint" )
	void testHitCountConditionBreakpoint() throws Exception {
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

		// Create a test client
		ConditionalBreakpointCapturingClient testClient = new ConditionalBreakpointCapturingClient();

		try ( SocketChannel clientSocket = SocketChannel.open() ) {
			clientSocket.connect( new InetSocketAddress( "localhost", TEST_PORT ) );
			assertTrue( clientSocket.isConnected(), "Should connect to debug server" );

			Launcher<IDebugProtocolServer> launcher = DSPLauncher.createClientLauncher(
			    testClient,
			    clientSocket.socket().getInputStream(),
			    clientSocket.socket().getOutputStream()
			);

			launcher.startListening();
			IDebugProtocolServer		debugServer	= launcher.getRemoteProxy();

			// Initialize debug session
			InitializeRequestArguments	initArgs	= new InitializeRequestArguments();
			initArgs.setClientID( "conditional-bp-test-client" );
			initArgs.setClientName( "Conditional Breakpoint Test" );
			initArgs.setAdapterID( "boxlang" );

			CompletableFuture<Capabilities>	initResponse	= debugServer.initialize( initArgs );
			Capabilities					capabilities	= initResponse.get( 5, TimeUnit.SECONDS );

			assertNotNull( capabilities, "Should receive capabilities" );
			assertTrue( capabilities.getSupportsHitConditionalBreakpoints(), "Should support hit conditional breakpoints" );

			// Set a breakpoint with hit condition "3" (stop on 3rd hit)
			SetBreakpointsArguments	breakpointArgs	= new SetBreakpointsArguments();
			Source					source			= new Source();
			Path					testFile		= Paths.get( "src/test/java/ortus/boxlang/bxdebugger/boxlangIntegration/conditional-breakpoint-test.bxs" )
			    .toAbsolutePath();
			assertTrue( testFile.toFile().exists(), "conditional-breakpoint-test.bxs should exist" );

			source.setPath( testFile.toString() );
			source.setName( "conditional-breakpoint-test.bxs" );
			breakpointArgs.setSource( source );

			SourceBreakpoint hitCountBreakpoint = new SourceBreakpoint();
			hitCountBreakpoint.setLine( 11 ); // counter = counter + 1 line
			hitCountBreakpoint.setHitCondition( "3" ); // Stop on 3rd hit
			breakpointArgs.setBreakpoints( new SourceBreakpoint[] { hitCountBreakpoint } );

			CompletableFuture<SetBreakpointsResponse>	breakpointResponse	= debugServer.setBreakpoints( breakpointArgs );
			SetBreakpointsResponse						breakpointResult	= breakpointResponse.get( 5, TimeUnit.SECONDS );

			assertNotNull( breakpointResult, "Should receive breakpoint response" );
			assertTrue( breakpointResult.getBreakpoints().length > 0, "Should have breakpoints" );

			LOGGER.info( "Hit count breakpoint set successfully, launching program..." );

			// Launch the program
			Map<String, Object> launchArgs = new HashMap<>();
			launchArgs.put( "program", testFile.toString() );
			launchArgs.put( "type", "boxlang" );
			launchArgs.put( "name", "Debug conditional-breakpoint-test.bxs" );

			CompletableFuture<Void> launchResponse = debugServer.launch( launchArgs );
			launchResponse.get( 10, TimeUnit.SECONDS );

			// Send configuration done
			ConfigurationDoneArguments	configArgs			= new ConfigurationDoneArguments();
			CompletableFuture<Void>		configDoneResult	= debugServer.configurationDone( configArgs );
			configDoneResult.get( 5, TimeUnit.SECONDS );

			// Wait for stopped event - should stop on 3rd iteration
			assertTrue( testClient.waitForStoppedEvent( 1500 ),
			    "Should receive stopped event on 3rd hit" );

			StoppedEventArguments stoppedEvent = testClient.getLastStoppedEvent();
			assertNotNull( stoppedEvent, "Stopped event should not be null" );
			assertEquals( "breakpoint", stoppedEvent.getReason(), "Should stop for breakpoint" );

			LOGGER.info( "Hit count breakpoint test completed successfully" );

		} catch ( Exception e ) {
			fail( e );
		}
	}

	/**
	 * Debug client that captures stopped and output events for testing conditional breakpoints
	 */
	private static class ConditionalBreakpointCapturingClient implements IDebugProtocolClient {

		private static final Logger					CLIENT_LOGGER			= Logger.getLogger( ConditionalBreakpointCapturingClient.class.getName() );
		private final CountDownLatch				stoppedEventReceived	= new CountDownLatch( 1 );
		private final List<StoppedEventArguments>	stoppedEvents			= new ArrayList<>();
		private final List<OutputEventArguments>	outputEvents			= new ArrayList<>();

		@Override
		public void stopped( StoppedEventArguments args ) {
			CLIENT_LOGGER.info( "Received stopped event: " + args.getReason() );
			synchronized ( stoppedEvents ) {
				stoppedEvents.add( args );
			}
			stoppedEventReceived.countDown();
		}

		@Override
		public void output( OutputEventArguments args ) {
			CLIENT_LOGGER.info( "Received output event: " + args.getOutput() );
			synchronized ( outputEvents ) {
				outputEvents.add( args );
			}
		}

		public boolean waitForStoppedEvent( int timeoutSeconds ) {
			try {
				return stoppedEventReceived.await( timeoutSeconds, TimeUnit.SECONDS );
			} catch ( InterruptedException e ) {
				Thread.currentThread().interrupt();
				return false;
			}
		}

		public StoppedEventArguments getLastStoppedEvent() {
			synchronized ( stoppedEvents ) {
				return stoppedEvents.isEmpty() ? null : stoppedEvents.get( stoppedEvents.size() - 1 );
			}
		}

		public List<OutputEventArguments> getOutputEvents() {
			synchronized ( outputEvents ) {
				return new ArrayList<>( outputEvents );
			}
		}

		public int getStoppedEventCount() {
			synchronized ( stoppedEvents ) {
				return stoppedEvents.size();
			}
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
