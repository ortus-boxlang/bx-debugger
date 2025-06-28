package ortus.boxlang.moduleslug;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.services.LanguageClient;

public class BoxDebugger {

	private static final Logger			LOGGER					= Logger.getLogger( BoxDebugger.class.getName() );
	private static final int			DEFAULT_PORT			= 5007;
	private static final int			MAX_CONSECUTIVE_ERRORS	= 5;
	private static final long			ERROR_BACKOFF_MS		= 1000;
	private static final AtomicBoolean	shutdown				= new AtomicBoolean( false );

	public static void main( String[] args ) {
		System.out.println( "Box Debugger is starting..." );

		// Add shutdown hook for graceful termination
		Runtime.getRuntime().addShutdownHook( new Thread( () -> {
			LOGGER.info( "Shutdown signal received, stopping server..." );
			shutdown.set( true );
		} ) );

		int port = getPortFromArgs( args );

		try {
			startLSPServer( port );
		} catch ( IOException e ) {
			LOGGER.severe( "Failed to start LSP server: " + e.getMessage() );
			System.exit( 1 );
		}
	}

	private static int getPortFromArgs( String[] args ) {
		if ( args.length > 0 ) {
			try {
				return Integer.parseInt( args[ 0 ] );
			} catch ( NumberFormatException e ) {
				LOGGER.warning( "Invalid port number provided: " + args[ 0 ] + ". Using default port " + DEFAULT_PORT );
			}
		}
		return DEFAULT_PORT;
	}

	private static void startLSPServer( int port ) throws IOException {
		LOGGER.info( "Starting BoxLang Debug Adapter Protocol server on port " + port );

		try ( ServerSocketChannel serverSocket = ServerSocketChannel.open() ) {
			serverSocket.bind( new InetSocketAddress( port ) );
			LOGGER.info( "Debug server listening on port " + port );

			ExecutorService	executor			= Executors.newCachedThreadPool();
			int				consecutiveErrors	= 0;

			while ( !shutdown.get() ) {
				try {
					SocketChannel clientSocket = serverSocket.accept();
					LOGGER.info( "Client connected from " + clientSocket.getRemoteAddress() );

					// Reset error counter on successful connection
					consecutiveErrors = 0;

					// Handle each client connection in a separate thread
					executor.submit( () -> handleClient( clientSocket ) );

				} catch ( IOException e ) {
					consecutiveErrors++;
					LOGGER.severe( "Error accepting client connection (attempt " + consecutiveErrors + "): " + e.getMessage() );

					// If we have too many consecutive errors, implement backoff to prevent hard loop
					if ( consecutiveErrors >= MAX_CONSECUTIVE_ERRORS ) {
						LOGGER.warning( "Too many consecutive errors (" + consecutiveErrors + "). Implementing backoff..." );
						try {
							Thread.sleep( ERROR_BACKOFF_MS );
						} catch ( InterruptedException ie ) {
							Thread.currentThread().interrupt();
							LOGGER.info( "Server interrupted during error backoff" );
							break;
						}

						// Reset counter after backoff to give it another chance
						consecutiveErrors = 0;
					}

					// Check if we should continue or if this is a fatal error
					if ( isFatalServerError( e ) ) {
						LOGGER.severe( "Fatal server error encountered: " + e.getMessage() );
						break;
					}
				}
			}

			LOGGER.info( "Shutting down executor service..." );
			executor.shutdown();
			try {
				if ( !executor.awaitTermination( 30, TimeUnit.SECONDS ) ) {
					LOGGER.warning( "Executor did not terminate gracefully, forcing shutdown..." );
					executor.shutdownNow();
				}
			} catch ( InterruptedException e ) {
				Thread.currentThread().interrupt();
				executor.shutdownNow();
			}
		}

		LOGGER.info( "BoxLang Debug Adapter Protocol server stopped" );
	}

	/**
	 * Determines if a server error is fatal and should stop the server
	 */
	private static boolean isFatalServerError( IOException e ) {
		// Check for specific error conditions that indicate the server should stop
		String message = e.getMessage();
		if ( message != null ) {
			// Port already in use, permission denied, etc.
			if ( message.contains( "Address already in use" ) ||
			    message.contains( "Permission denied" ) ||
			    message.contains( "Cannot assign requested address" ) ) {
				return true;
			}
		}
		return false;
	}

	private static void handleClient( SocketChannel clientSocket ) {
		try {
			// Create the debug server instance
			BoxDebugServer				debugServer	= new BoxDebugServer();

			// Create the LSP4J launcher for the debug adapter protocol
			Launcher<LanguageClient>	launcher	= Launcher.createLauncher(
			    debugServer,
			    LanguageClient.class,
			    clientSocket.socket().getInputStream(),
			    clientSocket.socket().getOutputStream()
			);

			// Connect the debug server to the client
			debugServer.connect( launcher.getRemoteProxy() );

			LOGGER.info( "Debug session started" );

			// Start listening for requests
			launcher.startListening().get();

		} catch ( Exception e ) {
			LOGGER.severe( "Error in debug session: " + e.getMessage() );
		} finally {
			try {
				clientSocket.close();
				LOGGER.info( "Client disconnected" );
			} catch ( IOException e ) {
				LOGGER.warning( "Error closing client socket: " + e.getMessage() );
			}
		}
	}
}
