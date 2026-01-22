package ortus.boxlang.bxdebugger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.eclipse.lsp4j.debug.Capabilities;
import org.eclipse.lsp4j.debug.DisconnectArguments;
import org.eclipse.lsp4j.debug.InitializeRequestArguments;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Integration test skeleton for attaching the debugger to an already-running
 * BoxLang process (BLIDE-168). Disabled until attach(Map<String,Object>) is
 * implemented on BoxDebugServer.
 */
@Execution( ExecutionMode.SAME_THREAD )
@EnabledIfEnvironmentVariable( named = "BOX_DEBUGGER_ATTACH_TEST", matches = ".*", disabledReason = "Set BOX_DEBUGGER_ATTACH_TEST to run attach integration test" )
public class AttachRunningProcessTest {

	private static final Logger	LOGGER		= Logger.getLogger( AttachRunningProcessTest.class.getName() );
	private static Process		commandBoxProcess;
	private static final int	TEST_PORT	= 9999;
	private static final int	TIMEOUT		= 9999;

	private Thread				debuggerThread;
	private CountDownLatch		serverStartupLatch;

	@BeforeEach
	public void setUp() throws Exception {
		serverStartupLatch	= new CountDownLatch( 1 );

		// Start the debug server in a separate thread
		debuggerThread		= DebugServerTestUtils.createDebuggerThread( TEST_PORT, serverStartupLatch );
	}

	@BeforeAll
	static void startCommandBoxSite() throws Exception {
		// Run `box server start` in src/test/resources/commandbox-site
		File workingDir = Paths.get( "src/test/resources/commandbox-site" ).toAbsolutePath().toFile();
		if ( !workingDir.exists() ) {
			LOGGER.warning( "commandbox-site directory not found: " + workingDir );
			return;
		}
		ProcessBuilder			pb			= new ProcessBuilder()
		    .command( detectBoxExecutable(), "server", "start" )
		    .directory( workingDir )
		    .redirectErrorStream( true );

		CompletableFuture<Void>	serverReady	= new CompletableFuture<>();
		try {
			commandBoxProcess = pb.start();
			// Read limited startup output asynchronously to avoid blocking
			CompletableFuture.runAsync( () -> {
				try ( java.io.BufferedReader r = new java.io.BufferedReader( new java.io.InputStreamReader( commandBoxProcess.getInputStream() ) ) ) {
					String line;
					while ( ( line = r.readLine() ) != null ) {
						LOGGER.info( "[BOX] " + line );
						if ( line != null && line.contains( "Server is up" ) ) {
							serverReady.complete( null );
						}
					}

				} catch ( IOException e ) {
					LOGGER.warning( "Error reading box output: " + e.getMessage() );
				}
			} );
			serverReady.get( 5, TimeUnit.MINUTES );

			LOGGER.info( "CommandBox server start initiated (process still running)." );
		} catch ( IOException e ) {
			LOGGER.warning( "Failed to start CommandBox server: " + e.getMessage() );
		}
	}

	@AfterAll
	static void stopCommandBoxSite() throws Exception {
		if ( commandBoxProcess == null ) {
			LOGGER.info( "No CommandBox process recorded; nothing to stop." );
			return;
		}
		File			workingDir	= Paths.get( "src/test/resources/commandbox-site" ).toAbsolutePath().toFile();
		ProcessBuilder	pb			= new ProcessBuilder()
		    .command( detectBoxExecutable(), "server", "stop" )
		    .directory( workingDir )
		    .redirectErrorStream( true );
		try {
			Process stopProc = pb.start();
			LOGGER.info( "Issued 'box server stop' command." );
			boolean finished = stopProc.waitFor( 10, TimeUnit.SECONDS );
			if ( finished ) {
				LOGGER.info( "Stop command completed with exit code: " + stopProc.exitValue() );
			} else {
				LOGGER.info( "Stop command did not finish within timeout; will check original process state." );
			}
			// Give original process grace period to exit
			if ( commandBoxProcess.isAlive() ) {
				LOGGER.info( "Waiting additional 5s for CommandBox process to exit gracefully..." );
				commandBoxProcess.waitFor( 5, TimeUnit.SECONDS );
			}
		} catch ( IOException e ) {
			LOGGER.warning( "Failed to invoke server stop: " + e.getMessage() );
		}
		// Fallback termination if still alive
		if ( commandBoxProcess.isAlive() ) {
			LOGGER.info( "CommandBox process still alive; attempting gentle destroy." );
			commandBoxProcess.destroy();
			if ( commandBoxProcess.isAlive() ) {
				LOGGER.info( "Process did not terminate; forcing termination." );
				commandBoxProcess.destroyForcibly();
			}
		}
		LOGGER.info( "CommandBox process termination sequence complete." );
	}

	private static String detectBoxExecutable() {
		// Basic heuristic: allow environment PATH resolution (just 'box')
		// Optionally look for BOX_HOME or box.exe; keep simple for now.
		return System.getProperty( "os.name" ).toLowerCase().contains( "win" ) ? "box.exe" : "box";
	}

	/**
	 * Placeholder test – will be enabled once attach support exists.
	 * Steps (documented for future implementation):
	 * 1. Start target BoxLang process with JDWP (suspend=y) on an ephemeral port.
	 * 2. Start BoxDebugger server on a separate port.
	 * 3. Initialize DAP client, call server.attach({ host, port }).
	 * 4. Set a breakpoint, send configurationDone, verify threads & breakpoint verification.
	 */
	@Test
	// @Disabled( "BLIDE-168: Attach functionality not yet implemented in BoxDebugServer" )
	// @Timeout( value = 5, unit = TimeUnit.SECONDS )
	void testAttachToRunningProcess() throws Exception {

		assertTrue( serverStartupLatch.await( TIMEOUT, TimeUnit.SECONDS ), "Server should signal startup" );

		// Simple HTTP GET to the CommandBox site to verify it's responding
		int		port		= 10111; // from server.json
		String	urlString	= "http://localhost:" + port + "/";
		LOGGER.info( "Issuing HTTP GET to: " + urlString );

		String responseBody = doHttpGetWithRetry( urlString, 5, 1000 );
		assertTrue( responseBody.length() > 0, "Expected non-empty response body" );
		LOGGER.info( "Received response body length: " + responseBody.length() );

		Path breakpointFile = Paths.get( "src/test/resources/commandbox-site/index.bxm" ).toAbsolutePath();

		DebugServerTestUtils.getServerProxy( TEST_PORT, ( server, client ) -> {
			try {

				// INITIALIZE
				InitializeRequestArguments initArgs = new InitializeRequestArguments();
				initArgs.setClientID( "test-client" );
				initArgs.setClientName( "BoxLang Debug Integration Test" );
				initArgs.setAdapterID( "boxlang" );

				CompletableFuture<Capabilities>	initResponse	= server.initialize( initArgs );
				Capabilities					capabilities	= initResponse.get( TIMEOUT, TimeUnit.SECONDS );

				client.waitForInitializedEvent().get( TIMEOUT, TimeUnit.SECONDS );

				Map<String, Object> attachArgs = new HashMap<>();
				attachArgs.put( "serverName", "commandbox-site" );
				// attachArgs.put( "port", 10110 );

				server.attach( attachArgs ).get( TIMEOUT, TimeUnit.SECONDS );

				DisconnectArguments disconnectArguments = new DisconnectArguments();
				server.disconnect( disconnectArguments );

			} catch ( Exception e ) {
				fail( e );
			}
		} );
	}

	private String doHttpGetWithRetry( String urlString, int maxAttempts, long delayMs ) throws Exception {
		Exception lastEx = null;
		for ( int attempt = 1; attempt <= maxAttempts; attempt++ ) {
			try {
				@SuppressWarnings( "deprecation" )
				URL					url		= new URL( urlString );
				HttpURLConnection	conn	= ( HttpURLConnection ) url.openConnection();
				conn.setConnectTimeout( 2000 );
				conn.setReadTimeout( 5000 );
				conn.setRequestMethod( "GET" );
				int status = conn.getResponseCode();
				if ( status == 200 ) {
					try ( java.io.InputStream is = conn.getInputStream() ) {
						byte[] data = is.readAllBytes();
						assertEquals( 200, status, "HTTP status should be 200" );
						return new String( data, StandardCharsets.UTF_8 );
					}
				} else {
					LOGGER.warning( "Attempt " + attempt + " got HTTP status " + status );
				}
			} catch ( Exception e ) {
				lastEx = e;
				LOGGER.warning( "HTTP attempt " + attempt + " failed: " + e.getMessage() );
			}
			Thread.sleep( delayMs );
		}
		throw lastEx != null ? lastEx : new IOException( "Failed HTTP GET without exception" );
	}
}
