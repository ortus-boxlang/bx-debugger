package ortus.boxlang.bxdebugger;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import org.eclipse.lsp4j.debug.launch.DSPLauncher;
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient;
import org.eclipse.lsp4j.jsonrpc.Launcher;

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
			System.out.println( String.format( "Listening on port: %s", port ) );
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
			LOGGER.info( "Creating debug server instance..." );
			// Create the debug server instance
			BoxDebugServer debugServer = new BoxDebugServer();
			LOGGER.info( "Debug server instance created successfully" );
			LOGGER.info( "Client socket open: " + clientSocket.isOpen() + ", connected: " + clientSocket.isConnected() );

			Launcher<IDebugProtocolClient> launcher = null;

			if ( System.getProperty( "teeDAPInput" ) != null ) {
				String				timestamp		= new SimpleDateFormat( "yyyyMMdd-HHmmss" ).format( new Date() );
				FileOutputStream	debugFile		= new FileOutputStream( "debug-messages-" + timestamp + ".log" );

				// Create TeeInputStream to capture incoming messages
				TeeInputStream		teeInputStream	= new TeeInputStream(
				    clientSocket.socket().getInputStream(),
				    debugFile,
				    true // Enable message logging
				);

				// Create the LSP4J launcher for the debug adapter protocol
				launcher = DSPLauncher.createServerLauncher(
				    debugServer,
				    // clientSocket.socket().getInputStream(),
				    teeInputStream,
				    clientSocket.socket().getOutputStream()
				);
			} else {
				LOGGER.info( "Creating DSP launcher..." );
				launcher = DSPLauncher.createServerLauncher(
				    debugServer,
				    clientSocket.socket().getInputStream(),
				    clientSocket.socket().getOutputStream()
				);
			}

			LOGGER.info( "Connecting debug server to client..." );
			// Connect the debug server to the client
			debugServer.connect( launcher.getRemoteProxy() );

			LOGGER.info( "Debug session started, waiting for requests..." );

			// Start listening for requests
			launcher.startListening().get();

			LOGGER.info( "Launcher listening completed normally" );

		} catch ( Exception e ) {
			LOGGER.severe( "Error in debug session: " + e.getClass().getName() + ": " + e.getMessage() );
			if ( e.getCause() != null ) {
				LOGGER.severe( "Caused by: " + e.getCause().getClass().getName() + ": " + e.getCause().getMessage() );
			}
			e.printStackTrace();
		} finally {
			try {
				clientSocket.close();
				LOGGER.info( "Client disconnected" );
			} catch ( IOException e ) {
				LOGGER.warning( "Error closing client socket: " + e.getMessage() );
			}
		}
	}

	private static class TeeInputStream extends InputStream {

		private static final Logger	LOGGER	= Logger.getLogger( TeeInputStream.class.getName() );

		private final InputStream	source;
		private final OutputStream	tee;
		private final StringBuilder	buffer;
		private final boolean		logMessages;

		public TeeInputStream( InputStream source, OutputStream tee, boolean logMessages ) {
			this.source			= source;
			this.tee			= tee;
			this.buffer			= new StringBuilder();
			this.logMessages	= logMessages;
		}

		@Override
		public int read() throws IOException {
			int data = source.read();
			if ( data != -1 ) {
				tee.write( data );
				tee.flush();

				if ( logMessages ) {
					char c = ( char ) data;
					buffer.append( c );

					// Log complete JSON-RPC messages (end with newline or closing brace)
					if ( c == '\n' || ( c == '}' && isCompleteMessage() ) ) {
						String message = buffer.toString().trim();
						if ( !message.isEmpty() ) {
							LOGGER.info( "Incoming JSON-RPC: " + message );
						}
						buffer.setLength( 0 );
					}
				}
			}
			return data;
		}

		@Override
		public int read( byte[] b, int off, int len ) throws IOException {
			int bytesRead = source.read( b, off, len );
			if ( bytesRead > 0 ) {
				tee.write( b, off, bytesRead );
				tee.flush();

				if ( logMessages ) {
					String chunk = new String( b, off, bytesRead );
					buffer.append( chunk );

					// Check if we have complete messages to log
					String		content	= buffer.toString();
					String[]	lines	= content.split( "\n" );

					for ( int i = 0; i < lines.length - 1; i++ ) {
						String line = lines[ i ].trim();
						if ( !line.isEmpty() ) {
							LOGGER.info( "Incoming JSON-RPC: " + line );
						}
					}

					// Keep the last incomplete line in the buffer
					buffer.setLength( 0 );
					if ( lines.length > 0 ) {
						buffer.append( lines[ lines.length - 1 ] );
					}
				}
			}
			return bytesRead;
		}

		private boolean isCompleteMessage() {
			String content = buffer.toString().trim();
			if ( content.startsWith( "{" ) ) {
				int braceCount = 0;
				for ( char c : content.toCharArray() ) {
					if ( c == '{' )
						braceCount++;
					else if ( c == '}' )
						braceCount--;
				}
				return braceCount == 0;
			}
			return false;
		}

		@Override
		public void close() throws IOException {
			try {
				source.close();
			} finally {
				tee.close();
			}
		}

		@Override
		public int available() throws IOException {
			return source.available();
		}

		@Override
		public boolean markSupported() {
			return source.markSupported();
		}

		@Override
		public void mark( int readlimit ) {
			source.mark( readlimit );
		}

		@Override
		public void reset() throws IOException {
			source.reset();
		}

		@Override
		public long skip( long n ) throws IOException {
			return source.skip( n );
		}
	}

}
