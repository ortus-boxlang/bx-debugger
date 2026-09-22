package ortus.boxlang.bxdebugger;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.sun.jdi.ArrayReference;
import com.sun.jdi.ArrayType;
import com.sun.jdi.ClassNotLoadedException;
import com.sun.jdi.ClassType;
import com.sun.jdi.Field;
import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.InvalidTypeException;
import com.sun.jdi.InvocationException;
import com.sun.jdi.Method;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.StringReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;

public class InvokeTools {

	private static final Logger							LOGGER		= Logger.getLogger( InvokeTools.class.getName() );
	public static CompletableFuture<ThreadReference>	debugThread	= new CompletableFuture<>();

	/**
	 * Fail the invocation when DebuggerUtil is unavailable without terminating the adapter.
	 *
	 * @param message The error message to log
	 */
	private static void fatalError( String message ) {
		LOGGER.severe( "FATAL: " + message );
		LOGGER.severe( "The debugger cannot function without the DebuggerUtil. Ensure BoxLang is started with debugMode=true" );
		throw new IllegalStateException( message );
	}

	private static final class Pins {

		private final java.util.List<ObjectReference>	values	= new java.util.ArrayList<>();
		private boolean									closed;

		synchronized <T extends ObjectReference> T keep( T value ) {
			if ( closed )
				throw new IllegalStateException( "Invocation has ended" );
			value.disableCollection();
			values.add( value );
			return value;
		}

		synchronized void close() {
			if ( closed )
				return;
			closed = true;
			for ( ObjectReference value : values ) {
				try {
					value.enableCollection();
				} catch ( RuntimeException error ) {
					LOGGER.fine( "Unable to release invocation value: " + error );
				}
			}
			values.clear();
		}
	}

	public static ObjectReference createIntegerRef( VMController vmController, int value ) {
		synchronized ( vmController.invocationLock ) {
			ClassType	integerClass	= ( ClassType ) vmController.vm.classesByName( "java.lang.Integer" ).get( 0 );
			Method		valueOfMethod	= null;
			for ( Method m : integerClass.methodsByName( "valueOf" ) ) {
				if ( m.signature().equals( "(I)Ljava/lang/Integer;" ) ) {
					valueOfMethod = m;
					break;
				}
			}
			if ( valueOfMethod == null )
				throw new IllegalStateException( "Integer.valueOf(int) not found" );

			Value boxedInt;
			try {
				var invokeThread = vmController.getPreparedDebugInvokeThread();

				boxedInt = integerClass.invokeMethod(
				    invokeThread,
				    valueOfMethod,
				    Collections.singletonList( vmController.vm.mirrorOf( value ) ),
				    ObjectReference.INVOKE_SINGLE_THREADED
				);
			} catch ( Exception e ) {
				throw new CompletionException( e );
			}
			vmController.retainInvocationResult( boxedInt );
			return ( ObjectReference ) boxedInt;
		}
	}

	public static CompletableFuture<Value> submitAndInvokeStatic( VMController controller, String target, String method, List<String> types,
	    List<Value> args ) {
		return submit( controller, null, target, method, types, args );
	}

	public static CompletableFuture<Value> submitAndInvoke( VMController controller, ObjectReference target, String method, List<String> types,
	    List<Value> args ) {
		return submit( controller, target, null, method, types, args );
	}

	private static CompletableFuture<Value> submit( VMController controller, ObjectReference target, String targetClass, String method, List<String> types,
	    List<Value> args ) {
		Pins pins = new Pins();
		try {
			// Retain inputs before queuing: a JDI mirror alone is not a target-side GC root.
			if ( target != null )
				pins.keep( target );
			for ( Value argument : args )
				if ( argument instanceof ObjectReference object )
					pins.keep( object );
			return controller.submitInvocation( () -> {
				synchronized ( controller.invocationLock ) {
					try {
						StringReference task = targetClass == null
						    ? enqueueOnObject( controller, target, method, types, args, pins )
						    : enqueueStatic( controller, targetClass, method, types, args, pins );
						return pollForResult( controller, task, pins );
					} catch ( Exception error ) {
						LOGGER.log( Level.SEVERE, "Helper invocation failed: " + ( targetClass == null ? method : targetClass + "." + method ), error );
						throw error instanceof CompletionException completion ? completion : new CompletionException( error );
					}
				}
			} ).whenComplete( ( value, error ) -> pins.close() );
		} catch ( Exception error ) {
			LOGGER.log( Level.SEVERE, "Unable to retain invocation arguments: " + method, error );
			pins.close();
			return CompletableFuture.failedFuture( error );
		}
	}

	private static Value pollForResult( VMController vmController, StringReference taskId, Pins pins ) {
		ClassType helperClass = getHelperClass( vmController );
		if ( helperClass == null ) {
			fatalError( "DebuggerUtil class not found during pollForResult" );
			return null; // Unreachable, but satisfies compiler
		}

		ThreadReference debugThread = vmController.getPreparedDebugInvokeThread();
		if ( debugThread == null ) {
			fatalError( "Debug thread not available for pollForResult" );
			return null; // Unreachable, but satisfies compiler
		}

		try {
			int		timeoutLoop	= 100;

			Method	pollMethod	= helperClass.methodsByName( "peekResult" ).get( 0 );
			Value	res			= null;
			for ( int i = 0; i < timeoutLoop; ++i ) {
				res = helperClass.invokeMethod( debugThread, pollMethod,
				    Collections.singletonList( taskId ),
				    ObjectReference.INVOKE_SINGLE_THREADED );
				if ( res != null ) {
					// Peek keeps the result rooted in the runtime until we can pin and transfer ownership.
					ObjectReference result = pins.keep( ( ObjectReference ) res );
					helperClass.invokeMethod( debugThread, helperClass.methodsByName( "pollResult" ).get( 0 ), List.of( taskId ),
					    ObjectReference.INVOKE_SINGLE_THREADED );
					vmController.retainInvocationResult( result );
					Value failure = findValueOfPropertyByName( result, "exception" );
					if ( failure instanceof ObjectReference exception ) {
						var				messages	= new java.util.StringJoiner( ": " );
						var				seen		= new java.util.HashSet<Long>();
						ObjectReference	current		= exception;
						while ( seen.add( current.uniqueID() ) ) {
							String	type	= current.referenceType().name();
							Value	detail	= findValueOfPropertyByName( current, "detailMessage" );
							messages.add( type + ( detail instanceof StringReference text ? ": " + text.value() : "" ) );
							Value cause = findValueOfPropertyByName( current,
							    type.equals( "java.lang.reflect.InvocationTargetException" ) ? "target" : "cause" );
							if ( ! ( cause instanceof ObjectReference next ) )
								break;
							current = next;
						}
						throw new IllegalStateException( "Target evaluation failed: " + messages, new InvocationException( exception ) );
					}
					return findValueOfPropertyByName( result, "value" );
				}
				Thread.sleep( 50 );
			}
			throw new CompletionException(
			    new TimeoutException( "pollForResult timed out after " + ( timeoutLoop * 50 ) + "ms for taskId: " + taskId.value() ) );
		} catch ( InvalidTypeException | ClassNotLoadedException | IncompatibleThreadStateException | InvocationException e ) {
			throw new CompletionException( e );
		} catch ( InterruptedException e ) {
			Thread.currentThread().interrupt();
			throw new CompletionException( e );
		}
	}

	private static StringReference enqueueStatic( VMController vmController, String target, String methodName, List<String> paramTypeNames, List<Value> args,
	    Pins pins ) {
		ClassType helperClass = getHelperClass( vmController );
		if ( helperClass == null ) {
			fatalError( "DebuggerUtil class not found for enqueueStatic" );
			return null; // Unreachable, but satisfies compiler
		}

		ThreadReference debugThread = vmController.getPreparedDebugInvokeThread();
		if ( debugThread == null ) {
			fatalError( "Debug thread not available for enqueueStatic" );
			return null; // Unreachable, but satisfies compiler
		}

		List<Value> taskArgs = List.of( pins.keep( vmController.vm.mirrorOf( target ) ), pins.keep( vmController.vm.mirrorOf( methodName ) ),
		    convertToMirrorStringArray( vmController, paramTypeNames, pins ),
		    convertToMirrorObjectArray( vmController, args, pins ) );

		try {
			Value taskIdVal = helperClass.invokeMethod(
			    debugThread,
			    helperClass.methodsByName( "enqueueStatic" ).get( 0 ),
			    taskArgs,
			    ObjectReference.INVOKE_SINGLE_THREADED
			);

			return pins.keep( ( StringReference ) taskIdVal );
		} catch ( InvalidTypeException | ClassNotLoadedException | IncompatibleThreadStateException | InvocationException e ) {
			throw new CompletionException( e );
		}
	}

	private static StringReference enqueueOnObject( VMController vmController, ObjectReference target, String methodName, List<String> paramTypeNames,
	    List<Value> args, Pins pins ) {
		ClassType helperClass = getHelperClass( vmController );
		if ( helperClass == null ) {
			fatalError( "DebuggerUtil class not found for enqueueOnObject" );
			return null; // Unreachable, but satisfies compiler
		}

		ThreadReference debugThread = vmController.getPreparedDebugInvokeThread();
		if ( debugThread == null ) {
			fatalError( "Debug thread not available for enqueueOnObject" );
			return null; // Unreachable, but satisfies compiler
		}

		List<Value> taskArgs = List.of( target, pins.keep( vmController.vm.mirrorOf( methodName ) ),
		    convertToMirrorStringArray( vmController, paramTypeNames, pins ),
		    convertToMirrorObjectArray( vmController, args, pins ) );

		try {
			Value taskIdVal = helperClass.invokeMethod(
			    debugThread,
			    helperClass.methodsByName( "enqueueOnObject" ).get( 0 ),
			    taskArgs,
			    ObjectReference.INVOKE_SINGLE_THREADED
			);

			return pins.keep( ( StringReference ) taskIdVal );
		} catch ( InvalidTypeException | ClassNotLoadedException | IncompatibleThreadStateException | InvocationException e ) {
			throw new CompletionException( e );
		}
	}

	private static ArrayReference convertToMirrorStringArray( VMController vmController, List<String> strings, Pins pins ) {
		var	strArrayType	= ( ArrayType ) vmController.vm.classesByName( "java.lang.String[]" ).get( 0 );
		var	typeArray		= pins.keep( strArrayType.newInstance( strings.size() ) );
		for ( int i = 0; i < strings.size(); ++i ) {
			try {
				typeArray.setValue( i, pins.keep( vmController.vm.mirrorOf( strings.get( i ) ) ) );
			} catch ( InvalidTypeException | ClassNotLoadedException e ) {
				throw new CompletionException( e );
			}
		}

		return typeArray;
	}

	private static ArrayReference convertToMirrorObjectArray( VMController vmController, List<Value> things, Pins pins ) {
		var	strArrayType	= ( ArrayType ) vmController.vm.classesByName( "java.lang.Object[]" ).get( 0 );
		var	typeArray		= pins.keep( strArrayType.newInstance( things.size() ) );
		for ( int i = 0; i < things.size(); ++i ) {
			try {
				typeArray.setValue( i, things.get( i ) );
			} catch ( InvalidTypeException | ClassNotLoadedException e ) {
				throw new CompletionException( e );
			}
		}

		return typeArray;
	}

	public static Value findValueOfPropertyByName( ObjectReference object, String name ) {
		for ( Field field : object.referenceType().allFields() ) {
			if ( field.name().equalsIgnoreCase( name ) ) {
				return object.getValue( field );
			}
		}

		return null;
	}

	private static ClassType getHelperClass( VMController vmController ) {
		// First, get the class type (either from cache or by looking it up)
		ClassType debuggerUtilClass = vmController.getDebuggerUtilClass();

		if ( debuggerUtilClass == null ) {
			fatalError( "DebuggerUtil class not loaded" );
			return null; // Unreachable, but satisfies compiler
		}

		// Verify the DebuggerUtil is running (started by BoxLang when debugMode=true)
		if ( !vmController.isDebuggerUtilStarted() ) {
			// Try to detect if it's running by looking for the invoker thread
			if ( !vmController.ensureDebuggerUtilStarted( null ) ) {
				fatalError( "DebuggerUtil not running" );
				return null; // Unreachable, but satisfies compiler
			}
		}

		return debuggerUtilClass;
	}
}
