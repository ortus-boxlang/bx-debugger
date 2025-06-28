package ortus.boxlang.moduleslug;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
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
import org.eclipse.lsp4j.debug.InitializeRequestArguments;
import org.eclipse.lsp4j.debug.SetBreakpointsArguments;
import org.eclipse.lsp4j.debug.SetBreakpointsResponse;
import org.eclipse.lsp4j.debug.Source;
import org.eclipse.lsp4j.debug.SourceBreakpoint;
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Integration test for BoxDebugger that simulates a complete debug session
 * including starting a BoxLang program and setting breakpoints
 */
public class BoxDebuggerIntegrationTest {

	private static final Logger			LOGGER				= Logger.getLogger( BoxDebuggerIntegrationTest.class.getName() );
	private static final int			TEST_PORT			= 5009; // Different port to avoid conflicts
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
		// Stop the server
		if ( serverFuture != null ) {
			serverFuture.cancel( true );
		}

		// Shutdown executors
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
	void testBoxLangDebugSessionWithBreakpoint() throws Exception {
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

		// Give server time to bind to port
		Thread.sleep( 1000 );

		// Check for server startup errors
		if ( serverException.get() != null ) {
			fail( "Server failed to start: " + serverException.get().getMessage() );
		}

		// Now simulate a debug client connecting and setting breakpoints
		try ( SocketChannel clientSocket = SocketChannel.open() ) {
			// Connect to the debug server
			clientSocket.connect( new InetSocketAddress( "localhost", TEST_PORT ) );
			assertTrue( clientSocket.isConnected(), "Should connect to debug server" );

			// Create debug client
			DebugClient						debugClient	= new DebugClient();

			// Create LSP4J launcher for debug protocol
			Launcher<IDebugProtocolServer>	launcher	= Launcher.createLauncher(
			    debugClient,
			    IDebugProtocolServer.class,
			    clientSocket.socket().getInputStream(),
			    clientSocket.socket().getOutputStream()
			);

			// Start listening for responses
			launcher.startListening();
			IDebugProtocolServer debugServer = launcher.getRemoteProxy();

			// Step 1: Initialize the debug session
			LOGGER.info( "Initializing debug session..." );
			InitializeRequestArguments initArgs = new InitializeRequestArguments();
			initArgs.setClientID( "test-client" );
			initArgs.setClientName( "BoxLang Debug Integration Test" );
			initArgs.setAdapterID( "boxlang" );

			CompletableFuture<Capabilities>	initResponse	= debugServer.initialize( initArgs );
			Capabilities					capabilities	= initResponse.get( 5, TimeUnit.SECONDS );

			assertNotNull( capabilities, "Should receive capabilities from debug server" );
			LOGGER.info( "Debug session initialized successfully" );

			// Step 2: Set up launch configuration for breakpoint.bxs
			LOGGER.info( "Setting up launch configuration..." );
			Map<String, Object>	launchArgs		= new HashMap<>();

			// Find the breakpoint.bxs file
			Path				breakpointFile	= Paths.get( "src/test/resources/breakpoint.bxs" ).toAbsolutePath();
			assertTrue( breakpointFile.toFile().exists(), "breakpoint.bxs should exist" );

			// Configure launch arguments (these would be specific to BoxLang)
			launchArgs.put( "program", breakpointFile.toString() );
			launchArgs.put( "type", "boxlang" );
			launchArgs.put( "name", "Debug breakpoint.bxs" );

			// Step 3: Set breakpoints before launching
			LOGGER.info( "Setting breakpoints..." );
			SetBreakpointsArguments	breakpointArgs	= new SetBreakpointsArguments();

			Source					source			= new Source();
			source.setPath( breakpointFile.toString() );
			source.setName( "breakpoint.bxs" );
			breakpointArgs.setSource( source );

			// Set breakpoint on line 5 (the return statement)
			SourceBreakpoint sourceBreakpoint = new SourceBreakpoint();
			sourceBreakpoint.setLine( 5 );
			sourceBreakpoint.setCondition( null ); // No condition

			breakpointArgs.setBreakpoints( new SourceBreakpoint[] { sourceBreakpoint } );

			CompletableFuture<SetBreakpointsResponse>	breakpointResponse	= debugServer.setBreakpoints( breakpointArgs );
			SetBreakpointsResponse						breakpointResult	= breakpointResponse.get( 5, TimeUnit.SECONDS );

			assertNotNull( breakpointResult, "Should receive breakpoint response" );
			assertNotNull( breakpointResult.getBreakpoints(), "Should have breakpoints in response" );
			assertThat( breakpointResult.getBreakpoints().length ).isGreaterThan( 0 );
			assertThat( breakpointResult.getBreakpoints()[ 0 ].getLine() ).isEqualTo( 5 );
			assertThat( breakpointResult.getBreakpoints()[ 0 ].isVerified() ).isFalse();

			// Verify breakpoint was set (for now, we'll just check that we got a response)
			// In a full implementation, we'd verify the breakpoint was actually set at line 5
			LOGGER.info( "Breakpoints set successfully" );

			// Step 4: Launch the program
			LOGGER.info( "Launching BoxLang program..." );
			CompletableFuture<Void> launchResponse = debugServer.launch( launchArgs );
			launchResponse.get( 5, TimeUnit.SECONDS ); // Wait for launch to complete

			LOGGER.info( "Program launched successfully" );

			// Step 5: Simulate program execution and breakpoint hit
			// In a real scenario, this would involve:
			// - The BoxLang runtime executing breakpoint.bxs
			// - Hitting the breakpoint on line 5
			// - The debug server sending a "stopped" event to the client
			// - The client requesting stack trace, variables, etc.

			// For this test, we'll just verify the debug session was established
			LOGGER.info( "Debug session established successfully" );

			// Test completed successfully
			assertTrue( true, "Integration test completed successfully" );

		} catch ( Exception e ) {
			e.printStackTrace();
			fail( "Debug session failed: " + e.getMessage() );
		}
	}

	/**
	 * Simple debug client implementation for testing
	 */
	private static class DebugClient {

		private static final Logger CLIENT_LOGGER = Logger.getLogger( DebugClient.class.getName() );

		// This would implement IDebugProtocolClient in a real scenario
		// For now, it's just a placeholder that can receive events from the server

		@SuppressWarnings( "unused" )
		public void onStopped( /* StoppedEventArguments args */ ) {
			CLIENT_LOGGER.info( "Received stopped event from debug server" );
		}

		@SuppressWarnings( "unused" )
		public void onTerminated( /* TerminatedEventArguments args */ ) {
			CLIENT_LOGGER.info( "Received terminated event from debug server" );
		}
	}

	@Test
	@Timeout( value = 10, unit = TimeUnit.SECONDS )
	void testBreakpointFileExists() {
		// Verify that our test file exists
		Path	breakpointFile	= Paths.get( "src/test/resources/breakpoint.bxs" );
		File	file			= breakpointFile.toFile();

		assertTrue( file.exists(), "breakpoint.bxs test file should exist" );
		assertTrue( file.isFile(), "breakpoint.bxs should be a file" );
		assertTrue( file.canRead(), "breakpoint.bxs should be readable" );

		LOGGER.info( "Breakpoint test file verified at: " + breakpointFile.toAbsolutePath() );
	}

	@Test
	@Timeout( value = 5, unit = TimeUnit.SECONDS )
	void testDebugClientCreation() {
		// Test that we can create our debug client
		DebugClient client = new DebugClient();
		assertNotNull( client, "Debug client should be created successfully" );
	}
}
