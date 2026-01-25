package ortus.boxlang.bxdebugger;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
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
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StringReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;

public class InvokeTools {

	private static final Logger							LOGGER		= Logger.getLogger( VariableManager.class.getName() );
	public static CompletableFuture<ThreadReference>	debugThread	= new CompletableFuture<>();

	private static final Object							invokeLock	= new Object();

	/**
	 * Fatal error that terminates the debugger.
	 * Called when the DebuggerService is not available, which is a non-recoverable state.
	 *
	 * @param message The error message to log
	 */
	private static void fatalError( String message ) {
		LOGGER.severe( "FATAL: " + message );
		LOGGER.severe( "The debugger cannot function without the DebuggerService. Ensure BoxLang is started with debugMode=true" );
		System.exit( 1 );
	}

	public static ObjectReference createIntegerRef( VMController vmController, int value ) {
		synchronized ( invokeLock ) {
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
				// TODO Auto-generated catch block
				e.printStackTrace();

				return null;
			}
			// boxedInt is an ObjectReference (java.lang.Integer) on success
			return ( ObjectReference ) boxedInt;
		}
	}

	// TODO probably want to change this to just directly use the signature to find the method
	public static CompletableFuture<Value> submitAndInvokeStatic( VMController vmController, String target, String methodName, List<String> paramTypeNames,
	    List<Value> args ) {
		return CompletableFuture.supplyAsync( () -> {
			synchronized ( invokeLock ) {
				try {
					String taskId = enqueueStatic( vmController, target, methodName, paramTypeNames, args );
					if ( taskId == null ) {
						LOGGER.severe( "Failed to enqueue static invocation: " + target + "." + methodName );
						return null;
					}
					return pollForResult( vmController, taskId );
				} catch ( Exception e ) {
					LOGGER.severe( "Error during submitAndInvokeStatic: " + e.getMessage() );
				}

				return null;
			}
		} );
	}

	// TODO probably want to change this to just directly use the signature to find the method
	public static CompletableFuture<Value> submitAndInvoke( VMController vmController, ObjectReference target, String methodName, List<String> paramTypeNames,
	    List<Value> args ) {
		return CompletableFuture.supplyAsync( () -> {
			synchronized ( invokeLock ) {
				try {
					String taskId = enqueueOnObject( vmController, target, methodName, paramTypeNames, args );
					if ( taskId == null ) {
						LOGGER.severe( "Failed to enqueue invocation: " + methodName );
						return null;
					}
					return pollForResult( vmController, taskId );
				} catch ( Exception e ) {
					LOGGER.severe( "Error during submitAndInvoke: " + e.getMessage() );
				}

				return null;
			}
		} );
	}

	private static Value pollForResult( VMController vmController, String taskId ) {
		ClassType helperClass = getHelperClass( vmController );
		if ( helperClass == null ) {
			fatalError( "DebuggerService class not found during pollForResult" );
			return null; // Unreachable, but satisfies compiler
		}

		ThreadReference debugThread = vmController.getPreparedDebugInvokeThread();
		if ( debugThread == null ) {
			fatalError( "Debug thread not available for pollForResult" );
			return null; // Unreachable, but satisfies compiler
		}

		try {
			int		timeoutLoop	= 100;

			Method	pollMethod	= helperClass.methodsByName( "pollResult" ).get( 0 );
			Value	res			= null;
			for ( int i = 0; i < timeoutLoop; ++i ) {
				res = helperClass.invokeMethod( debugThread, pollMethod,
				    Collections.singletonList( vmController.vm.mirrorOf( taskId ) ),
				    ObjectReference.INVOKE_SINGLE_THREADED );
				if ( res != null ) {
					return findValueOfPropertyByName( ( ObjectReference ) res, "value" );
				}
				Thread.sleep( 50 );
			}
			LOGGER.warning( "pollForResult timed out after " + ( timeoutLoop * 50 ) + "ms for taskId: " + taskId );
		} catch ( InvalidTypeException e ) {
			LOGGER.severe( "Invalid type during pollForResult: " + e.getMessage() );
		} catch ( ClassNotLoadedException e ) {
			LOGGER.severe( "Class not loaded during pollForResult: " + e.getMessage() );
		} catch ( IncompatibleThreadStateException e ) {
			LOGGER.severe( "Incompatible thread state during pollForResult: " + e.getMessage() );
		} catch ( InvocationException e ) {
			LOGGER.severe( "Invocation exception during pollForResult: " + e.getMessage() );
		} catch ( InterruptedException e ) {
			LOGGER.severe( "Interrupted during pollForResult: " + e.getMessage() );
		}
		return null;
	}

	private static String enqueueStatic( VMController vmController, String target, String methodName, List<String> paramTypeNames, List<Value> args ) {
		ClassType helperClass = getHelperClass( vmController );
		if ( helperClass == null ) {
			fatalError( "DebuggerService class not found for enqueueStatic" );
			return null; // Unreachable, but satisfies compiler
		}

		ThreadReference debugThread = vmController.getPreparedDebugInvokeThread();
		if ( debugThread == null ) {
			fatalError( "Debug thread not available for enqueueStatic" );
			return null; // Unreachable, but satisfies compiler
		}

		List<Value> taskArgs = List.of( vmController.vm.mirrorOf( target ), vmController.vm.mirrorOf( methodName ),
		    convertToMirrorStringArray( vmController, paramTypeNames ),
		    convertToMirrorObjectArray( vmController, args ) );

		try {
			Value taskIdVal = helperClass.invokeMethod(
			    debugThread,
			    helperClass.methodsByName( "enqueueStatic" ).get( 0 ),
			    taskArgs,
			    ObjectReference.INVOKE_SINGLE_THREADED
			);

			return ( ( StringReference ) taskIdVal ).value();
		} catch ( InvalidTypeException e ) {
			LOGGER.severe( "Invalid type during enqueueStatic: " + e.getMessage() );
		} catch ( ClassNotLoadedException e ) {
			LOGGER.severe( "Class not loaded during enqueueStatic: " + e.getMessage() );
		} catch ( IncompatibleThreadStateException e ) {
			LOGGER.severe( "Incompatible thread state during enqueueStatic: " + e.getMessage() );
		} catch ( InvocationException e ) {
			LOGGER.severe( "Invocation exception during enqueueStatic: " + e.getMessage() );
		}

		return null;
	}

	private static String enqueueOnObject( VMController vmController, ObjectReference target, String methodName, List<String> paramTypeNames,
	    List<Value> args ) {
		ClassType helperClass = getHelperClass( vmController );
		if ( helperClass == null ) {
			fatalError( "DebuggerService class not found for enqueueOnObject" );
			return null; // Unreachable, but satisfies compiler
		}

		ThreadReference debugThread = vmController.getPreparedDebugInvokeThread();
		if ( debugThread == null ) {
			fatalError( "Debug thread not available for enqueueOnObject" );
			return null; // Unreachable, but satisfies compiler
		}

		List<Value> taskArgs = List.of( target, vmController.vm.mirrorOf( methodName ), convertToMirrorStringArray( vmController, paramTypeNames ),
		    convertToMirrorObjectArray( vmController, args ) );

		try {
			Value taskIdVal = helperClass.invokeMethod(
			    debugThread,
			    helperClass.methodsByName( "enqueueOnObject" ).get( 0 ),
			    taskArgs,
			    ObjectReference.INVOKE_SINGLE_THREADED
			);

			return ( ( StringReference ) taskIdVal ).value();
		} catch ( InvalidTypeException e ) {
			LOGGER.severe( "Invalid type during enqueueOnObject: " + e.getMessage() );
		} catch ( ClassNotLoadedException e ) {
			LOGGER.severe( "Class not loaded during enqueueOnObject: " + e.getMessage() );
		} catch ( IncompatibleThreadStateException e ) {
			LOGGER.severe( "Incompatible thread state during enqueueOnObject: " + e.getMessage() );
		} catch ( InvocationException e ) {
			LOGGER.severe( "Invocation exception during enqueueOnObject: " + e.getMessage() );
		}

		return null;
	}

	private static ArrayReference convertToMirrorStringArray( VMController vmController, List<String> strings ) {
		var	strArrayType	= ( ArrayType ) vmController.vm.classesByName( "java.lang.String[]" ).get( 0 );
		var	typeArray		= strArrayType.newInstance( strings.size() );
		for ( int i = 0; i < strings.size(); ++i ) {
			try {
				typeArray.setValue( i, vmController.vm.mirrorOf( strings.get( i ) ) );
			} catch ( InvalidTypeException e ) {
				LOGGER.severe( "Invalid type when converting to Value[]: " + e.getMessage() );
			} catch ( ClassNotLoadedException e ) {
				LOGGER.severe( "Class not loaded when converting to Value[]: " + e.getMessage() );
			}
		}

		return typeArray;
	}

	private static ArrayReference convertToMirrorObjectArray( VMController vmController, List<Value> things ) {
		var	strArrayType	= ( ArrayType ) vmController.vm.classesByName( "java.lang.Object[]" ).get( 0 );
		var	typeArray		= strArrayType.newInstance( things.size() );
		for ( int i = 0; i < things.size(); ++i ) {
			try {
				typeArray.setValue( i, things.get( i ) );
			} catch ( InvalidTypeException e ) {
				LOGGER.severe( "Invalid type when converting to Object[]: " + e.getMessage() );
			} catch ( ClassNotLoadedException e ) {
				LOGGER.severe( "Class not loaded when converting to Object[]: " + e.getMessage() );
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
		ClassType debuggerServiceClass = vmController.getDebuggerServiceClass();

		if ( debuggerServiceClass == null ) {
			fatalError( "DebuggerService class not loaded" );
			return null; // Unreachable, but satisfies compiler
		}

		// Verify the DebuggerService is running (started by BoxLang when debugMode=true)
		if ( !vmController.isDebuggerServiceStarted() ) {
			// Try to detect if it's running by looking for the invoker thread
			if ( !vmController.ensureDebuggerServiceStarted( null ) ) {
				fatalError( "DebuggerService not running" );
				return null; // Unreachable, but satisfies compiler
			}
		}

		return debuggerServiceClass;
	}
}
