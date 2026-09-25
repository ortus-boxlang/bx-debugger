package ortus.boxlang.bxdebugger;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.eclipse.lsp4j.debug.ConfigurationDoneArguments;
import org.junit.jupiter.api.Test;

class SessionLifecycleTest {

	@Test
	void disconnectCancelsAnAttachBlockedInTheJdwpHandshake() throws Exception {
		try ( var listener = new java.net.ServerSocket( 0 ) ) {
			listener.setSoTimeout( 3000 );
			BoxDebugServer server = new BoxDebugServer();
			server.setFalseExit( true );
			server.connect( new IBoxLangDebugClient() {
			} );
			var setup = server.attach( Map.of( "serverPort", listener.getLocalPort() ) );
			try ( var peer = listener.accept() ) {
				server.disconnect( new org.eclipse.lsp4j.debug.DisconnectArguments() ).get( 2, TimeUnit.SECONDS );
				assertTrue( setup.isCompletedExceptionally(), "Disconnect must cancel the pending setup response" );
				peer.setSoTimeout( 2000 );
				assertEquals( 14, peer.getInputStream().readNBytes( 14 ).length );
				assertEquals( -1, peer.getInputStream().read(), "Cancellation must close the stalled JDWP handshake" );
				assertTrue( server.isSessionCleaned() );
				assertThrows( ExecutionException.class, () -> server.configurationDone( new ConfigurationDoneArguments() ).get( 2, TimeUnit.SECONDS ) );
			}
		}
	}

	@Test
	void aLateConnectionIsDisposedInsteadOfReactivatingTheSession() throws Exception {
		var	entered		= new java.util.concurrent.CountDownLatch( 1 );
		var	release		= new java.util.concurrent.CountDownLatch( 1 );
		var	vm			= org.mockito.Mockito.mock( com.sun.jdi.VirtualMachine.class );
		var	connection	= org.mockito.Mockito.mock( ortus.boxlang.bxdebugger.vm.IVMConnection.class );
		org.mockito.Mockito.when( connection.getVirtualMachine() ).thenReturn( vm );
		BoxDebugServer server = new BoxDebugServer() {

			@Override
			ortus.boxlang.bxdebugger.vm.IVMConnection openAttachConnection( String name, int port ) throws Exception {
				entered.countDown();
				// Simulate a provider that ignores cancellation and returns a late connection.
				while ( release.getCount() > 0 ) {
					try {
						release.await( 5, TimeUnit.SECONDS );
					} catch ( InterruptedException ignored ) {
					}
				}
				return connection;
			}
		};
		server.setFalseExit( true );
		var setup = server.attach( Map.of( "serverPort", 1234 ) );
		try {
			assertTrue( entered.await( 2, TimeUnit.SECONDS ) );
			server.disconnect( new org.eclipse.lsp4j.debug.DisconnectArguments() ).get( 2, TimeUnit.SECONDS );
			assertTrue( setup.isCompletedExceptionally() );
		} finally {
			release.countDown();
		}
		org.mockito.Mockito.verify( vm, org.mockito.Mockito.timeout( 2000 ) ).dispose();
		org.mockito.Mockito.verify( vm, org.mockito.Mockito.never() ).eventRequestManager();
		assertTrue( server.isSessionCleaned() );
	}

	@org.junit.jupiter.params.ParameterizedTest
	@org.junit.jupiter.params.provider.ValueSource( booleans = { false, true } )
	void failedSetupAlsoFailsConfiguration( boolean launch ) throws Exception {
		BoxDebugServer server = new BoxDebugServer();
		server.setFalseExit( true );
		server.connect( new IBoxLangDebugClient() {
		} );
		var setup = launch ? server.launch( Map.of( "program", "missingTicket04Program.bxs" ) ) : server.attach( Map.of() );
		assertThrows( ExecutionException.class, () -> setup.get( 2, TimeUnit.SECONDS ) );
		assertTrue( server.isSessionCleaned(), "Setup failure must clean up the session" );
		assertThrows( ExecutionException.class, () -> server.configurationDone( new ConfigurationDoneArguments() ).get( 2, TimeUnit.SECONDS ) );
	}
}
