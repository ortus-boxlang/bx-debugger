package ortus.boxlang.moduleslug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.eclipse.lsp4j.debug.Capabilities;
import org.eclipse.lsp4j.debug.EvaluateArguments;
import org.eclipse.lsp4j.debug.EvaluateResponse;
import org.eclipse.lsp4j.debug.InitializeRequestArguments;
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient;
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

public class EvaluateRequestHandlingTest {

	private static final Logger	LOGGER		= Logger.getLogger( EvaluateRequestHandlingTest.class.getName() );
	private static final int	TEST_PORT	= 5021;
	private ExecutorService		serverExecutor;
	private ServerSocketChannel	serverSocket;
	private CountDownLatch		serverStartupLatch;

	@BeforeEach
	void setUp() throws IOException {
		serverExecutor		= Executors.newSingleThreadExecutor();
		serverStartupLatch	= new CountDownLatch( 1 );

		serverExecutor.submit( () -> {
			try {
				serverSocket = ServerSocketChannel.open();
				serverSocket.bind( new InetSocketAddress( TEST_PORT ) );
				serverStartupLatch.countDown();

				SocketChannel					clientSocket	= serverSocket.accept();
				BoxDebugServer					debugServer		= new BoxDebugServer();

				Launcher<IDebugProtocolClient>	launcher		= org.eclipse.lsp4j.debug.launch.DSPLauncher.createServerLauncher(
				    debugServer,
				    clientSocket.socket().getInputStream(),
				    clientSocket.socket().getOutputStream()
				);

				debugServer.connect( launcher.getRemoteProxy() );
				launcher.startListening().get();

			} catch ( Exception e ) {
				LOGGER.severe( "Error in test debug server: " + e.getMessage() );
			}
		} );
	}

	@AfterEach
	void tearDown() {
		try {
			if ( serverSocket != null && serverSocket.isOpen() ) {
				serverSocket.close();
			}
		} catch ( IOException e ) {
			// ignore
		}
		if ( serverExecutor != null ) {
			serverExecutor.shutdown();
			try {
				if ( !serverExecutor.awaitTermination( 5, TimeUnit.SECONDS ) ) {
					serverExecutor.shutdownNow();
				}
			} catch ( InterruptedException e ) {
				Thread.currentThread().interrupt();
				serverExecutor.shutdownNow();
			}
		}
	}

	public static class TestDebugClient implements IDebugProtocolClient {

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

	@Test
	@Timeout( value = 30, unit = TimeUnit.SECONDS )
	@DisplayName( "Evaluate in repl context returns string literal" )
	public void testEvaluateReplStringLiteral() throws Exception {
		assertTrue( serverStartupLatch.await( 5, TimeUnit.SECONDS ) );

		try ( SocketChannel clientSocket = SocketChannel.open() ) {
			clientSocket.connect( new InetSocketAddress( "localhost", TEST_PORT ) );
			TestDebugClient					testClient	= new TestDebugClient();
			Launcher<IDebugProtocolServer>	launcher	= org.eclipse.lsp4j.debug.launch.DSPLauncher.createClientLauncher(
			    testClient,
			    clientSocket.socket().getInputStream(),
			    clientSocket.socket().getOutputStream()
			);
			launcher.startListening();
			IDebugProtocolServer			server		= launcher.getRemoteProxy();

			InitializeRequestArguments		initArgs	= new InitializeRequestArguments();
			CompletableFuture<Capabilities>	init		= server.initialize( initArgs );
			assertNotNull( init.get( 5, TimeUnit.SECONDS ) );

			// Launch a simple script, we don't need paused state for literal eval
			Map<String, Object> launchArgs = Map.of(
			    "program", "src/test/resources/output.bxs",
			    "debugMode", "BoxLang"
			);
			server.launch( launchArgs ).get( 10, TimeUnit.SECONDS );

			EvaluateArguments evalArgs = new EvaluateArguments();
			evalArgs.setContext( "repl" );
			evalArgs.setExpression( "\"hello world\"" );

			EvaluateResponse resp = server.evaluate( evalArgs ).get( 5, TimeUnit.SECONDS );
			assertNotNull( resp );
			assertEquals( "hello world", resp.getResult() );
		}
	}

	@Test
	@Timeout( value = 30, unit = TimeUnit.SECONDS )
	@DisplayName( "Evaluate in hover without pause returns error" )
	public void testEvaluateHoverWithoutPauseReturnsError() throws Exception {
		assertTrue( serverStartupLatch.await( 5, TimeUnit.SECONDS ) );

		try ( SocketChannel clientSocket = SocketChannel.open() ) {
			clientSocket.connect( new InetSocketAddress( "localhost", TEST_PORT ) );
			TestDebugClient					testClient	= new TestDebugClient();
			Launcher<IDebugProtocolServer>	launcher	= org.eclipse.lsp4j.debug.launch.DSPLauncher.createClientLauncher(
			    testClient,
			    clientSocket.socket().getInputStream(),
			    clientSocket.socket().getOutputStream()
			);
			launcher.startListening();
			IDebugProtocolServer			server		= launcher.getRemoteProxy();

			InitializeRequestArguments		initArgs	= new InitializeRequestArguments();
			CompletableFuture<Capabilities>	init		= server.initialize( initArgs );
			assertNotNull( init.get( 5, TimeUnit.SECONDS ) );

			Map<String, Object> launchArgs = Map.of(
			    "program", "src/test/resources/output.bxs"
			);
			server.launch( launchArgs ).get( 10, TimeUnit.SECONDS );

			EvaluateArguments evalArgs = new EvaluateArguments();
			evalArgs.setContext( "hover" );
			evalArgs.setExpression( "foo" );

			EvaluateResponse resp = server.evaluate( evalArgs ).get( 5, TimeUnit.SECONDS );
			assertNotNull( resp );
			assertTrue( resp.getResult() != null && resp.getResult().toLowerCase().contains( "error" ) );
		}
	}
}
