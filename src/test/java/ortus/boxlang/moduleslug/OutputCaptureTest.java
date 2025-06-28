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
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import org.eclipse.lsp4j.debug.Capabilities;
import org.eclipse.lsp4j.debug.InitializeRequestArguments;
import org.eclipse.lsp4j.debug.OutputEventArguments;
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient;
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Test for output capture functionality
 */
public class OutputCaptureTest {

	private static final Logger			LOGGER				= Logger.getLogger( OutputCaptureTest.class.getName() );
	private static final int			TEST_PORT			= 5010;
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
	@Timeout( value = 30, unit = TimeUnit.SECONDS )
	void testOutputCapture() throws Exception {
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

		// Create a test client that can capture output events
		OutputCapturingClient outputClient = new OutputCapturingClient();

		try ( SocketChannel clientSocket = SocketChannel.open() ) {
			clientSocket.connect( new InetSocketAddress( "localhost", TEST_PORT ) );
			assertTrue( clientSocket.isConnected(), "Should connect to debug server" );

			// Create LSP4J launcher
			Launcher<IDebugProtocolServer> launcher = Launcher.createLauncher(
			    outputClient,
			    IDebugProtocolServer.class,
			    clientSocket.socket().getInputStream(),
			    clientSocket.socket().getOutputStream()
			);

			launcher.startListening();
			IDebugProtocolServer		debugServer	= launcher.getRemoteProxy();

			// Initialize debug session
			InitializeRequestArguments	initArgs	= new InitializeRequestArguments();
			initArgs.setClientID( "output-test-client" );
			initArgs.setClientName( "Output Capture Test" );
			initArgs.setAdapterID( "boxlang" );

			CompletableFuture<Capabilities>	initResponse	= debugServer.initialize( initArgs );
			Capabilities					capabilities	= initResponse.get( 5, TimeUnit.SECONDS );

			assertNotNull( capabilities, "Should receive capabilities" );

			// Launch the breakpoint.bxs program which should produce output
			Map<String, Object>	launchArgs		= new HashMap<>();
			Path				breakpointFile	= Paths.get( "src/test/resources/output.bxs" ).toAbsolutePath();
			assertTrue( breakpointFile.toFile().exists(), "output.bxs should exist" );

			launchArgs.put( "program", breakpointFile.toString() );
			launchArgs.put( "type", "boxlang" );
			launchArgs.put( "bx-home", Paths.get( "src/test/resources/boxlang_home" ).toAbsolutePath().toString() );

			// Launch and wait for execution
			CompletableFuture<Void> launchResponse = debugServer.launch( launchArgs );
			launchResponse.get( 10, TimeUnit.SECONDS );

			// Wait for output to be captured
			Thread.sleep( 2000 );

			// Verify that we captured some output
			assertTrue( outputClient.hasReceivedOutput(),
			    "Should have received output from the BoxLang program" );

			assertThat( outputClient.getCapturedOutput() )
			    .contains( "This is output from boxlang" );

			LOGGER.info( "Output capture test completed successfully" );

		} catch ( Exception e ) {
			fail( "Output capture test failed: " + e.getMessage() );
		}
	}

	/**
	 * Debug client that captures output events
	 */
	private static class OutputCapturingClient implements IDebugProtocolClient {

		private static final Logger		CLIENT_LOGGER	= Logger.getLogger( OutputCapturingClient.class.getName() );
		private final CountDownLatch	outputReceived	= new CountDownLatch( 1 );
		private String					capturedOutput	= "";

		@Override
		public void output( OutputEventArguments args ) {
			CLIENT_LOGGER.info( "Received output: " + args.getOutput() );
			capturedOutput += args.getOutput();
			outputReceived.countDown();
		}

		public boolean hasReceivedOutput() {
			try {
				return outputReceived.await( 5, TimeUnit.SECONDS );
			} catch ( InterruptedException e ) {
				Thread.currentThread().interrupt();
				return false;
			}
		}

		public String getCapturedOutput() {
			return capturedOutput;
		}
	}
}
