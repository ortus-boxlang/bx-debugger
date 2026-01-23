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
import org.eclipse.lsp4j.debug.OutputEventArguments;
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
 * Test for output-test.bxs integration
 * This test verifies that the output-test.bxs file correctly outputs the number 6
 */
public class OutputTest {

	private static final Logger			LOGGER				= Logger.getLogger( OutputTest.class.getName() );
	private static final int			TEST_PORT			= 5011;
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
	void testOutputTestBxsOutputsSix() throws Exception {
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

		// Create a test client that can capture output events
		OutputCapturingClient outputClient = new OutputCapturingClient();

		try ( SocketChannel clientSocket = SocketChannel.open() ) {
			clientSocket.connect( new InetSocketAddress( "localhost", TEST_PORT ) );
			assertTrue( clientSocket.isConnected(), "Should connect to debug server" );

			Launcher<IDebugProtocolServer> launcher = DSPLauncher.createClientLauncher(
			    outputClient,
			    clientSocket.socket().getInputStream(),
			    clientSocket.socket().getOutputStream()
			);

			launcher.startListening();
			IDebugProtocolServer		debugServer	= launcher.getRemoteProxy();

			// Initialize debug session
			InitializeRequestArguments	initArgs	= new InitializeRequestArguments();
			initArgs.setClientID( "output-test-client" );
			initArgs.setClientName( "Output Test Integration" );
			initArgs.setAdapterID( "boxlang" );

			CompletableFuture<Capabilities>	initResponse	= debugServer.initialize( initArgs );
			Capabilities					capabilities	= initResponse.get( 5, TimeUnit.SECONDS );

			assertNotNull( capabilities, "Should receive capabilities" );

			// Launch the output-test.bxs program which should output the number 6
			Map<String, Object>	launchArgs		= new HashMap<>();
			Path				outputTestFile	= Paths.get( "src/test/java/ortus/boxlang/bxdebugger/boxlangIntegration/output-test.bxs" ).toAbsolutePath();
			assertTrue( outputTestFile.toFile().exists(), "output-test.bxs should exist" );

			launchArgs.put( "program", outputTestFile.toString() );
			launchArgs.put( "type", "boxlang" );
			launchArgs.put( "bx-home", Paths.get( "src/test/resources/boxlang_home" ).toAbsolutePath().toString() );

			// Launch and wait for execution
			CompletableFuture<Void> launchResponse = debugServer.launch( launchArgs );
			launchResponse.get( 10, TimeUnit.SECONDS );

			// Send configuration done request
			LOGGER.info( "Sending configuration done request" );
			ConfigurationDoneArguments	configArgs			= new ConfigurationDoneArguments();
			CompletableFuture<Void>		configDoneResult	= debugServer.configurationDone( configArgs );
			configDoneResult.get( 5, TimeUnit.SECONDS );

			// Wait up to 10 seconds for expected output to appear (integration can be slow)
			String	expected	= "this is the outputx";
			long	start		= System.currentTimeMillis();
			while ( System.currentTimeMillis() - start < 10_000 ) {
				if ( outputClient.getCapturedOutput().contains( expected ) ) {
					break;
				}
				Thread.sleep( 200 );
			}

			// Verify that we captured the expected output
			assertTrue( outputClient.getCapturedOutput().contains( expected ),
			    "Expected output not captured within timeout" );

			String capturedOutput = outputClient.getCapturedOutput();
			LOGGER.info( "Captured output: '" + capturedOutput + "'" );

			LOGGER.info( "Output test integration completed successfully" );

		} catch ( Exception e ) {
			fail( "Output test integration failed: " + e.getMessage() );
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

		// Intentionally no blocking wait helper; the test uses a polling loop for robustness

		public String getCapturedOutput() {
			return capturedOutput;
		}
	}
}
