package ortus.boxlang.moduleslug;

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
import org.eclipse.lsp4j.debug.SetBreakpointsArguments;
import org.eclipse.lsp4j.debug.SetBreakpointsResponse;
import org.eclipse.lsp4j.debug.Source;
import org.eclipse.lsp4j.debug.SourceBreakpoint;
import org.eclipse.lsp4j.debug.StackFrame;
import org.eclipse.lsp4j.debug.StackTraceArguments;
import org.eclipse.lsp4j.debug.StackTraceResponse;
import org.eclipse.lsp4j.debug.StoppedEventArguments;
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient;
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Test for stack information functionality - verifies that the debugger
 * can provide stack trace information when paused and respond to stackTrace requests
 */
public class StackInformationTest {

	private static final Logger			LOGGER				= Logger.getLogger( StackInformationTest.class.getName() );
	private static final int			TEST_PORT			= 5012;
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
	void testStackTraceRequest() throws Exception {
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

		// Create a test client that can capture stopped events and handle stack requests
		StackCapturingClient stackClient = new StackCapturingClient();

		try ( SocketChannel clientSocket = SocketChannel.open() ) {
			clientSocket.connect( new InetSocketAddress( "localhost", TEST_PORT ) );
			assertTrue( clientSocket.isConnected(), "Should connect to debug server" );

			// Create LSP4J launcher
			Launcher<IDebugProtocolServer> launcher = Launcher.createLauncher(
			    stackClient,
			    IDebugProtocolServer.class,
			    clientSocket.socket().getInputStream(),
			    clientSocket.socket().getOutputStream()
			);

			launcher.startListening();
			IDebugProtocolServer		debugServer	= launcher.getRemoteProxy();

			// Initialize debug session
			InitializeRequestArguments	initArgs	= new InitializeRequestArguments();
			initArgs.setClientID( "stack-test-client" );
			initArgs.setClientName( "Stack Information Test" );
			initArgs.setAdapterID( "boxlang" );

			CompletableFuture<Capabilities>	initResponse	= debugServer.initialize( initArgs );
			Capabilities					capabilities	= initResponse.get( 5, TimeUnit.SECONDS );

			assertNotNull( capabilities, "Should receive capabilities" );

			// Set a breakpoint on the TestOutputProducer class (line 41 - the return statement in add method)
			SetBreakpointsArguments	breakpointArgs		= new SetBreakpointsArguments();
			Source					source				= new Source();
			Path					testProducerFile	= Paths.get( "src/main/java/ortus/boxlang/moduleslug/TestOutputProducer.java" ).toAbsolutePath();
			source.setPath( testProducerFile.toString() );
			source.setName( "TestOutputProducer.java" );
			breakpointArgs.setSource( source );

			SourceBreakpoint sourceBreakpoint = new SourceBreakpoint();
			sourceBreakpoint.setLine( 41 ); // Line with return statement in add method
			breakpointArgs.setBreakpoints( new SourceBreakpoint[] { sourceBreakpoint } );

			CompletableFuture<SetBreakpointsResponse>	breakpointResponse	= debugServer.setBreakpoints( breakpointArgs );
			SetBreakpointsResponse						breakpointResult	= breakpointResponse.get( 5, TimeUnit.SECONDS );

			assertNotNull( breakpointResult, "Should receive breakpoint response" );
			assertTrue( breakpointResult.getBreakpoints().length > 0, "Should have breakpoints" );

			LOGGER.info( "Breakpoint set successfully, launching program..." );

			// Launch the program that should hit the breakpoint
			Map<String, Object> launchArgs = new HashMap<>();
			launchArgs.put( "program", testProducerFile.toString() );
			launchArgs.put( "type", "boxlang" );

			CompletableFuture<Void> launchResponse = debugServer.launch( launchArgs );
			launchResponse.get( 10, TimeUnit.SECONDS );

			LOGGER.info( "Program launched, waiting for stopped event..." );

			// Wait for the stopped event (breakpoint should be hit)
			assertTrue( stackClient.waitForStoppedEvent( 15 ),
			    "Should receive stopped event when breakpoint is hit" );

			// Verify the stopped event details
			StoppedEventArguments stoppedEvent = stackClient.getStoppedEvent();
			assertNotNull( stoppedEvent, "Stopped event should not be null" );

			LOGGER.info( "Successfully received stopped event: " + stoppedEvent.getReason() );

			// Now test the stackTrace request
			StackTraceArguments stackArgs = new StackTraceArguments();
			stackArgs.setThreadId( stoppedEvent.getThreadId() );
			
			CompletableFuture<StackTraceResponse>	stackResponse	= debugServer.stackTrace( stackArgs );
			StackTraceResponse						stackResult		= stackResponse.get( 10, TimeUnit.SECONDS );

			// Verify stack trace response
			assertNotNull( stackResult, "Stack trace response should not be null" );
			assertNotNull( stackResult.getStackFrames(), "Stack frames should not be null" );
			assertTrue( stackResult.getStackFrames().length > 0, "Should have at least one stack frame" );

			// Verify stack frame details
			StackFrame topFrame = stackResult.getStackFrames()[ 0 ];
			assertNotNull( topFrame, "Top stack frame should not be null" );
			assertNotNull( topFrame.getName(), "Stack frame should have a name" );
			assertTrue( topFrame.getLine() > 0, "Stack frame should have a valid line number" );
			
			if ( topFrame.getSource() != null ) {
				assertNotNull( topFrame.getSource().getPath(), "Stack frame source should have a path" );
			}

			LOGGER.info( "Successfully received stack trace with " + stackResult.getStackFrames().length + " frames" );
			LOGGER.info( "Top frame: " + topFrame.getName() + " at line " + topFrame.getLine() );

		} catch ( Exception e ) {
			fail( "Stack information test failed: " + e.getMessage() );
		}
	}

	/**
	 * Debug client that captures stopped events and supports stack trace requests
	 */
	private static class StackCapturingClient implements IDebugProtocolClient {

		private static final Logger		CLIENT_LOGGER			= Logger.getLogger( StackCapturingClient.class.getName() );
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
