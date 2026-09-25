package ortus.boxlang.bxdebugger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import org.eclipse.lsp4j.debug.Source;
import org.eclipse.lsp4j.debug.SourceBreakpoint;
import org.junit.jupiter.api.Test;
import com.sun.jdi.VirtualMachine;

class BreakpointBindingTest {

	@Test
	void bindsEveryLocationAndLoaderWithoutDuplicatingVerification() throws Exception {
		VirtualMachine	vm		= mock( VirtualMachine.class, RETURNS_DEEP_STUBS );
		var				first	= mock( com.sun.jdi.ReferenceType.class );
		var				second	= mock( com.sun.jdi.ReferenceType.class );
		when( first.name() ).thenReturn( "boxgenerated.Example" );
		when( second.name() ).thenReturn( "boxgenerated.Example" );
		var	a	= location( "/app/example.bxs" );
		var	b	= location( "/app/example.bxs" );
		var	c	= location( "/app/example.bxs" );
		when( first.locationsOfLine( 3 ) ).thenReturn( List.of( a, b ) );
		when( second.locationsOfLine( 3 ) ).thenReturn( List.of( c ) );
		when( vm.allClasses() ).thenReturn( List.of( first, second ) );
		stubRequests( vm );
		VMController	controller	= new VMController( vm, null );
		Source			source		= new Source();
		source.setPath( "/app/example.bxs" );
		SourceBreakpoint line = new SourceBreakpoint();
		line.setLine( 3 );
		var breakpoint = controller.trackSourceBreakpoint( source, line );
		controller.verifyAndSetPendingBreakpoints();
		controller.verifyAndSetPendingBreakpoints();
		assertTrue( breakpoint.isVerified() );
		assertEquals( 3, controller.getActiveBreakpointCount() );
		verify( vm, times( 1 ) ).allClasses();
		for ( var location : List.of( a, b, c ) ) {
			verify( vm.eventRequestManager(), times( 1 ) ).createBreakpointRequest( location );
		}
		controller.clearPendingBreakpointsForFile( "/other/example.bxs" );
		assertEquals( 3, controller.getActiveBreakpointCount(), "Clearing another same-named file must preserve these bindings" );
		controller.clearPendingBreakpointsForFile( source.getPath() );
		assertEquals( 0, controller.getActiveBreakpointCount() );
	}

	@Test
	void lateBindingPublishesVerificationAndKeepsSiblingLocations() throws Exception {
		VirtualMachine vm = mock( VirtualMachine.class, RETURNS_DEEP_STUBS );
		when( vm.allClasses() ).thenReturn( List.of() );
		when( vm.process() ).thenReturn( null );
		stubRequests( vm );
		var events = new java.util.concurrent.LinkedBlockingQueue<com.sun.jdi.event.EventSet>();
		when( vm.eventQueue().remove( anyLong() ) ).thenAnswer( call -> events.poll( 50, java.util.concurrent.TimeUnit.MILLISECONDS ) );
		var				changes		= new java.util.concurrent.LinkedBlockingQueue<org.eclipse.lsp4j.debug.BreakpointEventArguments>();
		VMController	controller	= new VMController( vm, new IBoxLangDebugClient() {

										@Override
										public void breakpoint( org.eclipse.lsp4j.debug.BreakpointEventArguments event ) {
											changes.add( event );
										}
									} );
		Source			source		= new Source();
		source.setPath( "/app/late.bxs" );
		SourceBreakpoint line = new SourceBreakpoint();
		line.setLine( 3 );
		var breakpoint = controller.trackSourceBreakpoint( source, line );
		controller.verifyAndSetPendingBreakpoints();
		controller.startEventProcessing();
		try {
			for ( int i = 0; i < 3; i++ ) {
				if ( i == 2 )
					controller.clearPendingBreakpointsForFile( source.getPath() );
				var type = mock( com.sun.jdi.ReferenceType.class );
				when( type.name() ).thenReturn( "boxgenerated.Late" );
				var locations = List.of( location( "/app/late.bxs" ), location( "/app/late.bxs" ) );
				when( type.locationsOfLine( 3 ) ).thenReturn( locations );
				var event = mock( com.sun.jdi.event.ClassPrepareEvent.class );
				when( event.referenceType() ).thenReturn( type );
				var set = mock( com.sun.jdi.event.EventSet.class );
				when( set.eventIterator() ).thenAnswer( call -> {
					var iterator = mock( com.sun.jdi.event.EventIterator.class );
					when( iterator.hasNext() ).thenReturn( true, false );
					when( iterator.nextEvent() ).thenReturn( event );
					return iterator;
				} );
				events.add( set );
				verify( set, timeout( 3000 ) ).resume();
				assertEquals( i == 2 ? 0 : ( i + 1 ) * 2, controller.getActiveBreakpointCount(),
				    "A new class must preserve siblings but must not resurrect a removed breakpoint" );
			}
			var change = changes.poll( 3, java.util.concurrent.TimeUnit.SECONDS );
			assertNotNull( change, "Late binding must notify the client" );
			assertEquals( "changed", change.getReason() );
			assertEquals( breakpoint.getId(), change.getBreakpoint().getId() );
			assertTrue( change.getBreakpoint().isVerified() );
		} finally {
			controller.stopEventProcessing();
		}
	}

	private void stubRequests( VirtualMachine vm ) {
		when( vm.eventRequestManager().createBreakpointRequest( any() ) ).thenAnswer( call -> {
			var request = mock( com.sun.jdi.request.BreakpointRequest.class );
			when( request.location() ).thenReturn( call.getArgument( 0 ) );
			java.util.Map<Object, Object> properties = new java.util.HashMap<>();
			doAnswer( put -> {
				properties.put( put.getArgument( 0 ), put.getArgument( 1 ) );
				return null;
			} )
			    .when( request ).putProperty( any(), any() );
			when( request.getProperty( any() ) ).thenAnswer( get -> properties.get( get.getArgument( 0 ) ) );
			return request;
		} );
	}

	private com.sun.jdi.Location location( String path ) throws Exception {
		var location = mock( com.sun.jdi.Location.class );
		when( location.sourcePath() ).thenReturn( path );
		when( location.sourceName() ).thenReturn( path.substring( path.lastIndexOf( '/' ) + 1 ) );
		return location;
	}

	@Test
	void sameBasenameInAnotherDirectoryDoesNotBind() throws Exception {
		VirtualMachine	vm		= mock( VirtualMachine.class, RETURNS_DEEP_STUBS );
		var				type	= mock( com.sun.jdi.ReferenceType.class );
		when( type.name() ).thenReturn( "boxgenerated.Example" );
		var location = location( "/other/example.bxs" );
		when( type.locationsOfLine( 3 ) ).thenReturn( List.of( location ) );
		when( vm.allClasses() ).thenReturn( List.of( type ) );
		VMController controller = new VMController( vm, null );
		controller.setPathMappingService( new PathMappingService( null, null, null ) );
		Source source = new Source();
		source.setPath( "/app/example.bxs" );
		SourceBreakpoint line = new SourceBreakpoint();
		line.setLine( 3 );
		var breakpoint = controller.trackSourceBreakpoint( source, line );
		controller.verifyAndSetPendingBreakpoints();
		assertFalse( breakpoint.isVerified() );
		assertEquals( 0, controller.getActiveBreakpointCount() );
	}

	@Test
	void removingUnloadedSourceDeletesItsTargetedListener() {
		VirtualMachine	vm			= mock( VirtualMachine.class, RETURNS_DEEP_STUBS );
		VMController	controller	= new VMController( vm, null );
		Source			source		= new Source();
		source.setPath( "/app/removed.bxs" );
		SourceBreakpoint line = new SourceBreakpoint();
		line.setLine( 3 );
		controller.trackSourceBreakpoint( source, line );
		controller.verifyAndSetPendingBreakpoints();
		controller.clearPendingBreakpointsForFile( source.getPath() );
		assertTrue( controller.getPendingBreakpointsForFile( source.getPath() ).isEmpty() );
		verify( vm.eventRequestManager() ).deleteEventRequest( any( com.sun.jdi.request.ClassPrepareRequest.class ) );
	}

	@Test
	void identicalDapReplacementRetainsLogicalBreakpointId() throws Exception {
		BoxDebugServer	server	= new BoxDebugServer();
		Source			source	= new Source();
		source.setPath( "/app/same.bxs" );
		SourceBreakpoint line = new SourceBreakpoint();
		line.setLine( 3 );
		line.setHitCondition( "3" );
		var args = new org.eclipse.lsp4j.debug.SetBreakpointsArguments();
		args.setSource( source );
		args.setBreakpoints( new SourceBreakpoint[] { line } );
		var	first	= server.setBreakpoints( args ).get( 5, java.util.concurrent.TimeUnit.SECONDS ).getBreakpoints()[ 0 ];
		var	second	= server.setBreakpoints( args ).get( 5, java.util.concurrent.TimeUnit.SECONDS ).getBreakpoints()[ 0 ];
		assertEquals( first.getId(), second.getId() );
	}

	@Test
	void clearingBeforeLaunchRemovesLogicalBreakpointsToo() {
		VMController	controller	= new VMController( null, null );
		Source			source		= new Source();
		source.setPath( "/app/cleared.bxs" );
		SourceBreakpoint line = new SourceBreakpoint();
		line.setLine( 3 );
		controller.trackSourceBreakpoint( source, line );
		controller.verifyAndSetPendingBreakpoints();
		controller.clearAllBreakpoints();
		assertTrue( controller.getAllPendingBreakpoints().isEmpty() );
	}

	@org.junit.jupiter.params.ParameterizedTest
	@org.junit.jupiter.params.provider.ValueSource( booleans = { false, true } )
	void cfcLoadListenerUsesSourceMetadataRatherThanAGuessedTemplateClassName( boolean windows ) {
		VirtualMachine vm = mock( VirtualMachine.class, RETURNS_DEEP_STUBS );
		when( vm.canUseSourceNameFilters() ).thenReturn( true );
		var requests = new java.util.ArrayList<com.sun.jdi.request.ClassPrepareRequest>();
		when( vm.eventRequestManager().createClassPrepareRequest() ).thenAnswer( call -> {
			var request = mock( com.sun.jdi.request.ClassPrepareRequest.class );
			requests.add( request );
			return request;
		} );
		VMController	controller	= new VMController( vm, null );
		Source			source		= new Source();
		source.setPath( windows ? "C:/app/Handler.cfc" : "/app/Handler.cfc" );
		SourceBreakpoint line = new SourceBreakpoint();
		line.setLine( 3 );
		controller.trackSourceBreakpoint( source, line );
		var targeted = requests.getLast();
		verify( targeted ).setSuspendPolicy( com.sun.jdi.request.EventRequest.SUSPEND_EVENT_THREAD );
		if ( windows ) {
			verify( targeted ).addClassFilter( "boxgenerated.boxclass.*" );
			verify( targeted, never() ).addSourceNameFilter( anyString() );
		} else {
			verify( targeted ).addClassFilter( "boxgenerated.*" );
			verify( targeted ).addSourceNameFilter( "*Handler.cfc" );
		}
	}

	@Test
	void learningRemoteRootsPreservesTheSameLogicalBreakpointAndAllowsRemoval() {
		VMController	controller	= new VMController( null, null );
		Source			local		= new Source();
		local.setPath( "/workspace/page.bxs" );
		SourceBreakpoint line = new SourceBreakpoint();
		line.setLine( 3 );
		var original = controller.replaceSourceBreakpoints( local, new SourceBreakpoint[] { line } ).getFirst();
		controller.setPathMappingService( new PathMappingService( "/workspace", "/app", null ) );
		Source remote = new Source();
		remote.setPath( "/app/page.bxs" );
		var repeated = controller.replaceSourceBreakpoints( remote, new SourceBreakpoint[] { line } ).getFirst();
		assertEquals( original.getId(), repeated.getId() );
		controller.replaceSourceBreakpoints( remote, new SourceBreakpoint[ 0 ] );
		assertTrue( controller.getAllPendingBreakpoints().isEmpty() );
		assertNull( controller.getPendingBreakpointById( original.getId() ) );
	}

	@Test
	void attachingPreservesIdsWithoutReusingThemForNewSources() {
		VMController	beforeLaunch	= new VMController( null, null );
		Source			firstSource		= new Source();
		firstSource.setPath( "/app/first.bxs" );
		SourceBreakpoint line = new SourceBreakpoint();
		line.setLine( 3 );
		var				first			= beforeLaunch.trackSourceBreakpoint( firstSource, line );
		VMController	attached		= new VMController( beforeLaunch, null, null );
		Source			secondSource	= new Source();
		secondSource.setPath( "/app/second.bxs" );
		var second = attached.trackSourceBreakpoint( secondSource, line );
		assertNotEquals( first.getId(), second.getId() );
		assertEquals( firstSource.getPath(), attached.getPendingBreakpointById( first.getId() ).getFilePath() );
	}

	@org.junit.jupiter.params.ParameterizedTest
	@org.junit.jupiter.params.provider.ValueSource( booleans = { false, true } )
	void unresolvedSourceOrNonExecutableLineStaysUnverified( boolean loaded ) {
		VirtualMachine	vm		= mock( VirtualMachine.class, RETURNS_DEEP_STUBS );
		var				type	= mock( com.sun.jdi.ReferenceType.class );
		when( type.name() ).thenReturn( "boxgenerated.Unloaded" );
		when( vm.allClasses() ).thenReturn( loaded ? List.of( type ) : List.of() );
		VMController	controller	= new VMController( vm, null );
		Source			source		= new Source();
		source.setPath( "/app/unloaded.bxs" );
		SourceBreakpoint line = new SourceBreakpoint();
		line.setLine( 3 );
		var breakpoint = controller.trackSourceBreakpoint( source, line );
		controller.verifyAndSetPendingBreakpoints();
		assertFalse( breakpoint.isVerified() );
		assertNotNull( breakpoint.getMessage() );
		assertEquals( 0, controller.getActiveBreakpointCount() );
	}
}
