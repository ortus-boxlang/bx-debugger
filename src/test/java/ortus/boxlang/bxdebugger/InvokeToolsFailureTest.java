package ortus.boxlang.bxdebugger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;

import com.sun.jdi.*;

class InvokeToolsFailureTest {

	@ParameterizedTest
	@ValueSource( booleans = { false, true } )
	void preservesUnexpectedFailureWithNoMessage( boolean isStatic ) throws Exception {
		VMController		controller	= mock( VMController.class );
		RuntimeException	original	= new IllegalStateException();
		when( controller.getDebuggerUtilClass() ).thenThrow( original );
		var future = isStatic
		    ? InvokeTools.submitAndInvokeStatic( controller, "Target", "run", List.of(), List.of() )
		    : InvokeTools.submitAndInvoke( controller, mock( ObjectReference.class ), "run", List.of(), List.of() );
		assertSame( original, assertThrows( ExecutionException.class, () -> future.get( 5, TimeUnit.SECONDS ) ).getCause() );
	}

	@ParameterizedTest
	@CsvSource( { "false,false", "false,true", "true,false", "true,true" } )
	void preservesEnqueueAndPollFailures( boolean isStatic, boolean failDuringPoll ) throws Exception {
		VirtualMachine	vm			= mock( VirtualMachine.class, RETURNS_DEEP_STUBS );
		VMController	controller	= spy( new VMController( vm, null ) );
		ClassType		helper		= mock( ClassType.class );
		ThreadReference	thread		= mock( ThreadReference.class );
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
