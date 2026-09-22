package ortus.boxlang.bxdebugger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.lang.ref.WeakReference;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.lsp4j.debug.StoppedEventArguments;
import org.eclipse.lsp4j.debug.ContinuedEventArguments;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.sun.jdi.*;
import com.sun.jdi.event.*;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.StepRequest;

@Timeout( 10 )
class StoppedThreadStateTest {

	private final VirtualMachine								vm			= mock( VirtualMachine.class, RETURNS_DEEP_STUBS );
	private final LinkedBlockingQueue<EventSet>					events		= new LinkedBlockingQueue<>();
	private final LinkedBlockingQueue<StoppedEventArguments>	stops		= new LinkedBlockingQueue<>();
	private VMController										controller;
	private final LinkedBlockingQueue<ContinuedEventArguments>	continued	= new LinkedBlockingQueue<>();

	@AfterEach
	void cleanup() {
		if ( controller != null )
			controller.stopEventProcessing();
	}

	private void start( IBoxLangDebugClient client ) throws Exception {
		when( vm.eventQueue().remove( anyLong() ) ).thenAnswer( call -> events.poll( 100, TimeUnit.MILLISECONDS ) );
		when( vm.allThreads() ).thenReturn( List.of() );
		when( vm.process() ).thenReturn( null );
		controller = new VMController( vm, client );
		controller.startEventProcessing();
	}

	private EventSet breakpoint( long id ) throws Exception {
		return breakpoint( id, null );
	}

	private EventSet breakpoint( long id, String hitCondition ) throws Exception {
		ThreadReference thread = mock( ThreadReference.class );
		when( thread.uniqueID() ).thenReturn( id );
		when( thread.isSuspended() ).thenReturn( true );
		StackFrame frame = mock( StackFrame.class, RETURNS_DEEP_STUBS );
		when( frame.location().method().name() ).thenReturn( "run" );
		when( frame.location().lineNumber() ).thenReturn( 10 );
		when( frame.location().sourceName() ).thenReturn( "test.bxs" );
		when( frame.location().sourcePath() ).thenReturn( "/app/test.bxs" );
		when( thread.frames() ).thenReturn( List.of( frame ) );
		BreakpointEvent event = mock( BreakpointEvent.class );
		when( event.thread() ).thenReturn( thread );
		Location location = frame.location();
		when( event.location() ).thenReturn( location );
		BreakpointRequest request = mock( BreakpointRequest.class );
		when( event.request() ).thenReturn( request );
		when( request.getProperty( "breakPointId" ) ).thenReturn( ( int ) id );
		when( request.getProperty( "hitCondition" ) ).thenReturn( hitCondition );
		return eventSet( event );
	}

	private EventSet eventSet( Event event ) {
		EventSet set = mock( EventSet.class );
		when( set.eventIterator() ).thenAnswer( call -> {
			EventIterator iterator = mock( EventIterator.class );
			when( iterator.hasNext() ).thenReturn( true, false );
			when( iterator.nextEvent() ).thenReturn( event );
			return iterator;
		} );
		return set;
	}

	private void start() throws Exception {
		start( new IBoxLangDebugClient() {

			@Override
			public void stopped( StoppedEventArguments event ) {
				stops.add( event );
			}

			@Override
			public void continued( ContinuedEventArguments event ) {
				continued.add( event );
			}
		} );
	}

	@Test
	void reverifyingBindingsDoesNotResetTheLogicalHitCount() throws Exception {
		start();
		ReferenceType type = mock( ReferenceType.class );
		when( type.name() ).thenReturn( "boxgenerated.Test" );
		Location location = mock( Location.class );
		when( location.sourcePath() ).thenReturn( "/app/test.bxs" );
		when( location.sourceName() ).thenReturn( "test.bxs" );
		when( type.locationsOfLine( 10 ) ).thenReturn( List.of( location ) );
		when( vm.allClasses() ).thenReturn( List.of( type ) );
		var source = new org.eclipse.lsp4j.debug.Source();
		source.setPath( "/app/test.bxs" );
		var line = new org.eclipse.lsp4j.debug.SourceBreakpoint();
		line.setLine( 10 );
		line.setHitCondition( "2" );
		var breakpoint = controller.trackSourceBreakpoint( source, line );
		controller.verifyAndSetPendingBreakpoints();
		EventSet first = breakpoint( breakpoint.getId(), "2" );
		events.add( first );
		verify( first, timeout( 3000 ) ).resume();
		assertTrue( stops.isEmpty() );
		controller.verifyAndSetPendingBreakpoints();
		events.add( breakpoint( breakpoint.getId(), "2" ) );
		assertNotNull( stops.poll( 3, TimeUnit.SECONDS ), "Second hit must stop even after re-verification" );
	}

	@Test
	void independentStopsRetainHandlesAndContinueAllReleasesTheRemainder() throws Exception {
		start();
		EventSet	first	= breakpoint( 201 );
		EventSet	second	= breakpoint( 202 );
		events.add( first );
		events.add( second );
		assertNotNull( stops.poll( 3, TimeUnit.SECONDS ) );
		assertNotNull( stops.poll( 3, TimeUnit.SECONDS ) );
		BreakpointContext	a		= controller.getBreakpointContextByThread( 201 ).orElseThrow();
		BreakpointContext	b		= controller.getBreakpointContextByThread( 202 ).orElseThrow();
		int					aFrame	= a.getStackFrames().getFirst().getId();
		int					bFrame	= b.getStackFrames().getFirst().getId();
		assertNotEquals( aFrame, bFrame );
		ObjectReference	value	= mock( ObjectReference.class );
		int				aRef	= a.getVariables().put( value );
		int				bRef	= b.getVariables().put( value );
		assertNotEquals( aRef, bRef );
		a.resume();
		assertThrows( IllegalArgumentException.class, () -> controller.getVariables( aRef ) );
		assertTrue( controller.getBreakpointContextbyStackFrame( aFrame ).isEmpty() );
		assertSame( value, controller.getVariables( bRef ).getValue( bRef ) );
		assertSame( b, controller.getBreakpointContextbyStackFrame( bFrame ).orElseThrow() );
		verify( first ).resume();
		verify( second, never() ).resume();
		ContinuedEventArguments one = continued.poll( 1, TimeUnit.SECONDS );
		assertNotNull( one );
		assertEquals( false, one.getAllThreadsContinued() );
		controller.continueAllExecution();
		verify( second ).resume();
		assertTrue( controller.getBreakpointContextByThread( 202 ).isEmpty() );
		assertThrows( IllegalArgumentException.class, () -> controller.getVariables( bRef ) );
		assertEquals( true, continued.poll( 1, TimeUnit.SECONDS ).getAllThreadsContinued() );
		verify( vm, never() ).resume();
	}

	@ParameterizedTest
	@ValueSource( ints = { StepRequest.STEP_OVER, StepRequest.STEP_INTO, StepRequest.STEP_OUT } )
	void steppingInvalidatesOldHandlesAndCannotResumeTheNextStopViaAnOldContext( int depth ) throws Exception {
		EventSet first = breakpoint( 201 );
		start();
		events.add( first );
		assertNotNull( stops.poll( 3, TimeUnit.SECONDS ) );
		BreakpointContext	old		= controller.getBreakpointContextByThread( 201 ).orElseThrow();
		int					frameId	= old.getStackFrames().getFirst().getId();
		int					ref		= old.getVariables().put( mock( ObjectReference.class ) );
		ThreadReference		thread	= old.getThreadReference();
		StepRequest			request	= mock( StepRequest.class );
		when( vm.eventRequestManager().createStepRequest( thread, StepRequest.STEP_LINE, depth ) ).thenReturn( request );
		controller.stepThread( 201, depth, true );
		verify( first ).resume();
		assertThrows( IllegalArgumentException.class, () -> controller.getVariables( ref ) );
		assertTrue( controller.getBreakpointContextbyStackFrame( frameId ).isEmpty() );
		StepEvent	event		= mock( StepEvent.class );
		Location	location	= thread.frames().getFirst().location();
		when( event.thread() ).thenReturn( thread );
		when( event.location() ).thenReturn( location );
		EventSet next = eventSet( event );
		events.add( next );
		assertEquals( "step", stops.poll( 3, TimeUnit.SECONDS ).getReason() );
		BreakpointContext current = controller.getBreakpointContextByThread( 201 ).orElseThrow();
		assertNotEquals( frameId, current.getStackFrames().getFirst().getId() );
		assertThrows( IllegalArgumentException.class, old::resume );
		verify( next, never() ).resume();
		current.resume();
		verify( next ).resume();
		verify( first, times( 1 ) ).resume();
		verify( vm.eventRequestManager() ).deleteEventRequest( request );
	}

	@Test
	void skippedHitResumesWithoutPublishingStopOrContinueEvents() throws Exception {
		EventSet		set		= breakpoint( 201, "2" );
		CountDownLatch	resumed	= new CountDownLatch( 1 );
		doAnswer( call -> {
			resumed.countDown();
			return null;
		} ).when( set ).resume();
		start();
		events.add( set );
		assertTrue( resumed.await( 3, TimeUnit.SECONDS ) );
		assertTrue( stops.isEmpty() );
		assertTrue( continued.isEmpty() );
		assertTrue( controller.getBreakpointContextByThread( 201 ).isEmpty() );
		verify( set ).resume();
	}

	@Test
	void repeatedStopsSurviveGcAndCleanupExpiresEveryHandle() throws Exception {
		EventSet set = breakpoint( 201 );
		start();
		int	oldFrame	= -1;
		int	oldRef		= -1;
		for ( int i = 0; i < 160; i++ ) {
			events.add( set );
			assertNotNull( stops.poll( 3, TimeUnit.SECONDS ) );
			BreakpointContext context = controller.getBreakpointContextByThread( 201 ).orElseThrow();
			assertTrue( controller.getBreakpointContextbyStackFrame( oldFrame ).isEmpty() );
			int expired = oldRef;
			assertThrows( IllegalArgumentException.class, () -> controller.getVariables( expired ) );
			oldFrame	= context.getStackFrames().getFirst().getId();
			oldRef		= context.getVariables().put( mock( ObjectReference.class ) );
			if ( i < 159 )
				context.resume();
		}
		WeakReference<Object> sentinel = new WeakReference<>( new Object() );
		for ( int i = 0; i < 50 && sentinel.get() != null; i++ ) {
			System.gc();
			Thread.sleep( 10 );
		}
		assertNull( sentinel.get() );
		assertTrue( controller.getBreakpointContextbyStackFrame( oldFrame ).isPresent() );
		VariableManager variables = controller.getVariables( oldRef );
		assertNotNull( variables.getValue( oldRef ) );
		controller.stopEventProcessing();
		assertTrue( controller.getBreakpointContextByThread( 201 ).isEmpty() );
		assertTrue( controller.getBreakpointContextbyStackFrame( oldFrame ).isEmpty() );
		int expired = oldRef;
		assertThrows( IllegalArgumentException.class, () -> variables.getVariablesFor( expired ) );
		assertNull( variables.getValue( expired ) );
		assertThrows( IllegalArgumentException.class, () -> variables.put( mock( ObjectReference.class ) ) );
		verify( set, times( 159 ) ).resume();
	}

	@Test
	void exceptionDetailsExpireOnResumeAndOnCleanup() throws Exception {
		EventSet		seed		= breakpoint( 201 );
		BreakpointEvent	breakpoint	= ( BreakpointEvent ) seed.eventIterator().nextEvent();
		ThreadReference	thread		= breakpoint.thread();
		Location		location	= breakpoint.location();
		ExceptionEvent	exception	= mock( ExceptionEvent.class );
		when( exception.thread() ).thenReturn( thread );
		when( exception.location() ).thenReturn( location );
		ObjectReference thrown = mock( ObjectReference.class, RETURNS_DEEP_STUBS );
		when( thrown.referenceType().name() ).thenReturn( "example.Failure" );
		when( thrown.referenceType().methodsByName( "getMessage" ) ).thenReturn( List.of() );
		when( exception.exception() ).thenReturn( thrown );
		EventSet set = eventSet( exception );
		start();
		events.add( set );
		assertEquals( "exception", stops.poll( 3, TimeUnit.SECONDS ).getReason() );
		assertNotNull( controller.getExceptionInfo( 201 ) );
		controller.continueExecution( 201, true );
		assertNull( controller.getExceptionInfo( 201 ) );
		events.add( set );
		assertNotNull( stops.poll( 3, TimeUnit.SECONDS ) );
		assertNotNull( controller.getExceptionInfo( 201 ) );
		controller.stopEventProcessing();
		assertNull( controller.getExceptionInfo( 201 ) );
	}

	@Test
	void steppingAllThreadsReleasesOtherStopsAndCleanupDeletesPendingStepRequests() throws Exception {
		EventSet	first	= breakpoint( 201 );
		EventSet	second	= breakpoint( 202 );
		start();
		events.add( first );
		events.add( second );
		assertNotNull( stops.poll( 3, TimeUnit.SECONDS ) );
		assertNotNull( stops.poll( 3, TimeUnit.SECONDS ) );
		BreakpointContext	context	= controller.getBreakpointContextByThread( 201 ).orElseThrow();
		StepRequest			request	= mock( StepRequest.class );
		ThreadReference		thread	= context.getThreadReference();
		when( vm.eventRequestManager().createStepRequest( thread, StepRequest.STEP_LINE, StepRequest.STEP_OVER ) ).thenReturn( request );
		controller.stepThread( 201, StepRequest.STEP_OVER, false );
		assertEquals( true, continued.poll( 1, TimeUnit.SECONDS ).getAllThreadsContinued() );
		assertTrue( controller.getBreakpointContextByThread( 202 ).isEmpty() );
		verify( first ).resume();
		verify( second ).resume();
		controller.stopEventProcessing();
		verify( vm.eventRequestManager() ).deleteEventRequest( request );
	}

	@Test
	void repeatedConfigurationDoneDoesNotResumeAnUnrelatedStop() throws Exception {
		EventSet		start	= eventSet( mock( VMStartEvent.class ) );
		CountDownLatch	resumed	= new CountDownLatch( 1 );
		doAnswer( call -> {
			resumed.countDown();
			return null;
		} ).when( start ).resume();
		start();
		events.add( start );
		controller.signalConfigurationDone();
		assertTrue( resumed.await( 3, TimeUnit.SECONDS ) );
		EventSet stop = breakpoint( 201 );
		events.add( stop );
		assertNotNull( stops.poll( 3, TimeUnit.SECONDS ) );
		controller.signalConfigurationDone();
		verify( start ).resume();
		verify( stop, never() ).resume();
		verify( vm, never() ).resume();
	}

	@Test
	void notificationFailureCannotStrandAResumedStop() throws Exception {
		EventSet set = breakpoint( 201 );
		start( new IBoxLangDebugClient() {

			@Override
			public void stopped( StoppedEventArguments event ) {
				stops.add( event );
			}

			@Override
			public void continued( ContinuedEventArguments event ) {
				throw new IllegalStateException( "Client disconnected" );
			}
		} );
		events.add( set );
		assertNotNull( stops.poll( 3, TimeUnit.SECONDS ) );
		BreakpointContext context = controller.getBreakpointContextByThread( 201 ).orElseThrow();
		assertThrows( IllegalStateException.class, context::resume );
		verify( set ).resume();
		assertTrue( controller.getBreakpointContextByThread( 201 ).isEmpty() );
	}

	@Test
	void immediateContinueConsumesTheStopEventSetExactlyOnce() throws Exception {
		EventSet					set		= breakpoint( 201 );
		AtomicReference<Throwable>	failure	= new AtomicReference<>();
		start( new IBoxLangDebugClient() {

			@Override
			public void stopped( StoppedEventArguments event ) {
				try {
					controller.getBreakpointContextByThread( event.getThreadId() ).orElseThrow().resume();
				} catch ( Throwable error ) {
					failure.set( error );
				} finally {
					stops.add( event );
				}
			}
		} );
		events.add( set );
		assertNotNull( stops.poll( 3, TimeUnit.SECONDS ) );
		assertNull( failure.get() );
		verify( set ).resume();
		assertTrue( controller.getBreakpointContextByThread( 201 ).isEmpty() );
	}
}
