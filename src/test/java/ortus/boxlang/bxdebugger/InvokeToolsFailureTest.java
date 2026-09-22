package ortus.boxlang.bxdebugger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;

import com.sun.jdi.*;

class InvokeToolsFailureTest {

	private VMController controller;

	@AfterEach
	void cleanup() {
		if ( controller != null )
			controller.stopEventProcessing();
	}

	@Test
	void missingHelperFailsOnlyTheInvocation() throws Exception {
		VirtualMachine vm = mock( VirtualMachine.class, RETURNS_DEEP_STUBS );
		controller = new VMController( vm, null );
		var	future	= InvokeTools.submitAndInvokeStatic( controller, "Target", "run", List.of(), List.of() );
		var	error	= assertThrows( ExecutionException.class, () -> future.get( 2, TimeUnit.SECONDS ) );
		assertTrue( error.getCause().getMessage().contains( "DebuggerUtil class not loaded" ) );
		verify( vm, never() ).dispose();
	}

	@Test
	void invocationQueuesAreSessionLocalAndCancelledOnCleanup() throws Exception {
		controller = spy( new VMController( null, null ) );
		var	entered	= new CountDownLatch( 1 );
		var	release	= new CountDownLatch( 1 );
		doAnswer( call -> {
			entered.countDown();
			release.await();
			return null;
		} ).when( controller ).getDebuggerUtilClass();
		var first = InvokeTools.submitAndInvokeStatic( controller, "Target", "run", List.of(), List.of() );
		assertTrue( entered.await( 2, TimeUnit.SECONDS ) );
		var				queued	= InvokeTools.submitAndInvokeStatic( controller, "Target", "run", List.of(), List.of() );
		VMController	other	= spy( new VMController( null, null ) );
		try {
			var failure = new IllegalStateException( "other session remains responsive" );
			doThrow( failure ).when( other ).getDebuggerUtilClass();
			var independent = InvokeTools.submitAndInvokeStatic( other, "Target", "run", List.of(), List.of() );
			assertSame( failure, assertThrows( ExecutionException.class, () -> independent.get( 2, TimeUnit.SECONDS ) ).getCause() );
			controller.stopEventProcessing();
			assertThrows( ExecutionException.class, () -> first.get( 2, TimeUnit.SECONDS ) );
			assertThrows( ExecutionException.class, () -> queued.get( 2, TimeUnit.SECONDS ) );
			verify( controller, times( 1 ) ).getDebuggerUtilClass();
		} finally {
			release.countDown();
			other.stopEventProcessing();
		}
	}

	@ParameterizedTest
	@ValueSource( booleans = { false, true } )
	void preservesUnexpectedFailureWithNoMessage( boolean isStatic ) throws Exception {
		controller = spy( new VMController( null, null ) );
		RuntimeException original = new IllegalStateException();
		doThrow( original ).when( controller ).getDebuggerUtilClass();
		var future = isStatic
		    ? InvokeTools.submitAndInvokeStatic( controller, "Target", "run", List.of(), List.of() )
		    : InvokeTools.submitAndInvoke( controller, mock( ObjectReference.class ), "run", List.of(), List.of() );
		assertSame( original, assertThrows( ExecutionException.class, () -> future.get( 5, TimeUnit.SECONDS ) ).getCause() );
	}

	@ParameterizedTest
	@CsvSource( { "false,false", "false,true", "true,false", "true,true" } )
	void preservesEnqueueAndPollFailures( boolean isStatic, boolean failDuringPoll ) throws Exception {
		VirtualMachine vm = mock( VirtualMachine.class, RETURNS_DEEP_STUBS );
		controller = spy( new VMController( vm, null ) );
		ClassType		helper	= mock( ClassType.class );
		ThreadReference	thread	= mock( ThreadReference.class );
		doReturn( helper ).when( controller ).getDebuggerUtilClass();
		doReturn( true ).when( controller ).isDebuggerUtilStarted();
		doReturn( thread ).when( controller ).getPreparedDebugInvokeThread();
		ArrayType arrayType = mock( ArrayType.class );
		when( vm.classesByName( "java.lang.String[]" ) ).thenReturn( List.of( arrayType ) );
		when( vm.classesByName( "java.lang.Object[]" ) ).thenReturn( List.of( arrayType ) );
		when( arrayType.newInstance( 0 ) ).thenReturn( mock( ArrayReference.class ) );
		Method	enqueue	= mock( Method.class );
		Method	poll	= mock( Method.class );
		when( helper.methodsByName( isStatic ? "enqueueStatic" : "enqueueOnObject" ) ).thenReturn( List.of( enqueue ) );
		when( helper.methodsByName( "pollResult" ) ).thenReturn( List.of( poll ) );
		StringReference taskId = mock( StringReference.class );
		when( taskId.value() ).thenReturn( "task" );
		when( helper.invokeMethod( eq( thread ), eq( enqueue ), anyList(), anyInt() ) ).thenReturn( taskId );
		IncompatibleThreadStateException original = new IncompatibleThreadStateException( "test failure" );
		when( helper.invokeMethod( eq( thread ), eq( failDuringPoll ? poll : enqueue ), anyList(), anyInt() ) ).thenThrow( original );
		var future = isStatic
		    ? InvokeTools.submitAndInvokeStatic( controller, "Target", "run", List.of(), List.of() )
		    : InvokeTools.submitAndInvoke( controller, mock( ObjectReference.class ), "run", List.of(), List.of() );
		assertSame( original, assertThrows( ExecutionException.class, () -> future.get( 5, TimeUnit.SECONDS ) ).getCause() );
	}
}
