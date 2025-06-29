package ortus.boxlang.moduleslug;

import static com.google.common.truth.Truth.assertThat;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import org.eclipse.lsp4j.debug.Capabilities;
import org.eclipse.lsp4j.debug.ConfigurationDoneArguments;
import org.eclipse.lsp4j.debug.ContinueArguments;
import org.eclipse.lsp4j.debug.ContinueResponse;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Test class for proper breakpoint pause and continue functionality
 */
public class BreakpointPauseAndContinueTest {

	private static final Logger			LOGGER			= Logger.getLogger( BreakpointPauseAndContinueTest.class.getName() );
	private static final int			TEST_PORT		= 5011; // Different port to avoid conflicts

	private ExecutorService				serverExecutor;
	private ExecutorService				testExecutor;
	private AtomicReference<Exception>	serverException	= new AtomicReference<>();

	@BeforeEach
	public void setUp() {
		serverExecutor	= Executors.newSingleThreadExecutor();
		testExecutor	= Executors.newSingleThreadExecutor();
	}

	@AfterEach
	public void tearDown() throws InterruptedException {
		if ( serverExecutor != null && !serverExecutor.isShutdown() ) {
			serverExecutor.shutdownNow();
			serverExecutor.awaitTermination( 5, TimeUnit.SECONDS );
		}
		if ( testExecutor != null && !testExecutor.isShutdown() ) {
			testExecutor.shutdownNow();
			testExecutor.awaitTermination( 5, TimeUnit.SECONDS );
		}
	}

	@Test
	@Timeout( value = 45, unit = TimeUnit.SECONDS )
	@DisplayName( "Test breakpoint pauses execution and continue resumes" )
	public void testBreakpointPauseAndContinue() throws Exception {
		LOGGER.info( "Starting breakpoint pause and continue test" );

		// Latch to wait for server startup
		CountDownLatch serverStartupLatch = new CountDownLatch( 1 );

		// Start the debug server in a separate thread
		serverExecutor.submit( () -> {
			try {
				serverStartupLatch.countDown();
				BoxDebugger.main( new String[] { String.valueOf( TEST_PORT ) } );
			} catch ( Exception e ) {
				serverException.set( e );
				LOGGER.severe( "Server startup failed: " + e.getMessage() );
				e.printStackTrace();
			}
		} );

		// Wait for server startup signal
		assertTrue( serverStartupLatch.await( 5, TimeUnit.SECONDS ), "Server should signal startup" );
		Thread.sleep( 2000 );

		// Connect to the debug server
		try ( SocketChannel clientSocket = SocketChannel.open() ) {
			clientSocket.connect( new InetSocketAddress( "localhost", TEST_PORT ) );
			assertTrue( clientSocket.isConnected(), "Should be connected to debug server" );

			// Create the DAP client with stopped event handling
			TestDebugClientWithStoppedHandler	testClient		= new TestDebugClientWithStoppedHandler();
			Launcher<IDebugProtocolServer>		launcher		= DSPLauncher.createClientLauncher(
			    testClient,
			    clientSocket.socket().getInputStream(),
			    clientSocket.socket().getOutputStream()
			);

			// Get the debug server proxy
			IDebugProtocolServer				server			= launcher.getRemoteProxy();

			// Start the launcher
			java.util.concurrent.Future<Void>	launcherFuture	= launcher.startListening();

			// Test the breakpoint pause and continue flow
			LOGGER.info( "Starting breakpoint pause and continue test flow" );

			// 1. Initialize
			InitializeRequestArguments initArgs = new InitializeRequestArguments();
			initArgs.setAdapterID( "boxlang" );
			initArgs.setClientName( "BreakpointPauseAndContinueTest" );

			CompletableFuture<Capabilities>	initResult		= server.initialize( initArgs );
			Capabilities					capabilities	= initResult.get( 5, TimeUnit.SECONDS );

			assertNotNull( capabilities, "Capabilities should not be null" );
			assertTrue( capabilities.getSupportsConfigurationDoneRequest(), "Should support configuration done requests" );

			// 2. Set breakpoint on a line that will be executed
			SetBreakpointsArguments	breakpointArgs	= new SetBreakpointsArguments();
			Source					source			= new Source();

			// Use the test breakpoint file that has executable code
			Path					testFilePath	= Paths.get( "src/test/resources/breakpoint.bxs" ).toAbsolutePath();
			source.setPath( testFilePath.toString() );
			breakpointArgs.setSource( source );

			SourceBreakpoint bp = new SourceBreakpoint();
			bp.setLine( 5 ); // Set breakpoint on line 6 of the add function

			breakpointArgs.setBreakpoints( new SourceBreakpoint[] { bp } );

			CompletableFuture<SetBreakpointsResponse>	breakpointResult	= server.setBreakpoints( breakpointArgs );
			SetBreakpointsResponse						breakpointResponse	= breakpointResult.get( 5, TimeUnit.SECONDS );

			assertNotNull( breakpointResponse, "Breakpoint response should not be null" );
			assertThat( breakpointResponse.getBreakpoints() ).hasLength( 1 );

			// 4. Launch the program (this should hit the breakpoint)
			Map<String, Object> launchArgs = new HashMap<>();
			launchArgs.put( "program", testFilePath.toString() );

			LOGGER.info( "Launching program that should hit breakpoint" );
			CompletableFuture<Void> launchResult = server.launch( launchArgs );

			// Launch should start but not complete because breakpoint should pause execution
			try {
				launchResult.get( 10, TimeUnit.SECONDS );
				LOGGER.info( "Launch completed (may indicate program finished or breakpoint hit)" );
			} catch ( Exception e ) {
				LOGGER.info( "Launch may still be running (expected if breakpoint hit): " + e.getMessage() );
				// This is often expected if the breakpoint pauses execution
			}

			// Send configuration done request
			LOGGER.info( "Sending configuration done request" );
			ConfigurationDoneArguments	configArgs			= new ConfigurationDoneArguments();
			CompletableFuture<Void>		configDoneResult	= server.configurationDone( configArgs );
			configDoneResult.get( 5, TimeUnit.SECONDS );

			// 5. Wait for stopped event (breakpoint should be hit)
			LOGGER.info( "Waiting for stopped event from breakpoint..." );
			boolean stoppedEventReceived = testClient.waitForStoppedEvent( 15, TimeUnit.SECONDS );
			assertTrue( stoppedEventReceived, "Should receive stopped event when breakpoint is hit" );

			StoppedEventArguments stoppedEvent = testClient.getStoppedEvent();
			assertNotNull( stoppedEvent, "Stopped event should not be null" );
			assertThat( stoppedEvent.getReason() ).isEqualTo( "breakpoint" );

			LOGGER.info( "Breakpoint hit! Thread " + stoppedEvent.getThreadId() + " is paused" );

			// 6. Program should be paused at this point - verify it doesn't continue automatically
			Thread.sleep( 2000 ); // Wait a bit to ensure it stays paused

			// 7. Send continue request to resume execution
			ContinueArguments continueArgs = new ContinueArguments();
			continueArgs.setThreadId( stoppedEvent.getThreadId() );

			LOGGER.info( "Sending continue request for thread " + stoppedEvent.getThreadId() );
			CompletableFuture<ContinueResponse>	continueResult		= server.continue_( continueArgs );
			ContinueResponse					continueResponse	= continueResult.get( 5, TimeUnit.SECONDS );

			assertNotNull( continueResponse, "Continue response should not be null" );
			LOGGER.info( "Continue request completed, execution should resume" );

			// 8. Wait a bit for program to complete after continue
			Thread.sleep( 3000 );

			LOGGER.info( "Breakpoint pause and continue test completed successfully" );

			// Cleanup
			launcherFuture.cancel( true );

		} catch ( Exception e ) {
			LOGGER.severe( "Test failed: " + e.getMessage() );
			e.printStackTrace();
			fail( "Breakpoint pause and continue test failed: " + e.getMessage() );
		}

		// Check if server had any exceptions
		Exception serverEx = serverException.get();
		if ( serverEx != null ) {
			LOGGER.severe( "Server exception: " + serverEx.getMessage() );
			serverEx.printStackTrace();
			fail( "Server encountered an exception: " + serverEx.getMessage() );
		}
	}

	/**
	 * Test debug client implementation that can handle stopped events
	 */
	public static class TestDebugClientWithStoppedHandler implements IDebugProtocolClient {

		private final CountDownLatch							stoppedEventLatch		= new CountDownLatch( 1 );
		private final AtomicReference<StoppedEventArguments>	stoppedEvent			= new AtomicReference<>();
		private final AtomicBoolean								stoppedEventReceived	= new AtomicBoolean( false );

		@Override
		public void stopped( StoppedEventArguments args ) {
			LOGGER.info( "Received stopped event: " + args.getReason() + " on thread " + args.getThreadId() );
			stoppedEvent.set( args );
			stoppedEventReceived.set( true );
			stoppedEventLatch.countDown();
		}

		public boolean waitForStoppedEvent( long timeout, TimeUnit unit ) throws InterruptedException {
			return stoppedEventLatch.await( timeout, unit );
		}

		public StoppedEventArguments getStoppedEvent() {
			return stoppedEvent.get();
		}

		public boolean hasReceivedStoppedEvent() {
			return stoppedEventReceived.get();
		}

		// Implement other required methods with minimal functionality for testing
		// These will be called by the debug adapter but we don't need to handle them for this test
	}
}
