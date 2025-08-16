package ortus.boxlang.moduleslug;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.ClassNotLoadedException;
import com.sun.jdi.ClassType;
import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.InvalidTypeException;
import com.sun.jdi.InvocationException;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.Method;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;

public class Util {

	private static final Logger LOGGER = Logger.getLogger( BoxDebugServer.class.getName() );

	public static CompletableFuture<Value> invokeAsync( ThreadReference invokeThread, ObjectReference obj, String methodName, String signature,
	    List<Value> args ) {
		return CompletableFuture.supplyAsync( () -> {
			try {
				return invokeByNameAndArgs( invokeThread, obj, methodName, signature, args );
			} catch ( InvalidTypeException | ClassNotLoadedException | IncompatibleThreadStateException | InvocationException e ) {

				LOGGER.severe( "Error invoking method: " + e.getMessage() );

				throw new RuntimeException( "Error invoking method: " + methodName + ": " + e.getMessage(), e );
			}
		} );
	}

	public static Method findMethod( ReferenceType type, String methodName, String signature ) {
		var methods = type.methodsByName( methodName, signature );

		if ( methods.size() == 0 ) {
			throw new RuntimeException( "No methods found for: " + methodName + " " + signature );
		}

		return methods.getFirst();
	}

	public static Value invokeByNameAndArgs( ThreadReference invokeThread, ObjectReference obj, String methodName, String signature,
	    List<Value> args )
	    throws InvalidTypeException, ClassNotLoadedException, IncompatibleThreadStateException, InvocationException {
		Value val = ( ( ObjectReference ) obj )
		    .invokeMethod(
		        invokeThread,
		        findMethod( ( ClassType ) ( ( ObjectReference ) obj ).referenceType(), methodName, signature ),
		        args,
		        ObjectReference.INVOKE_SINGLE_THREADED
		    );

		return val;
	}

	private static Object executeInContextForBreakpoint( VirtualMachine vm, ThreadReference threadRef, ObjectReference context ) {
		ClassType	debugUtils		= ( ClassType ) vm.classesByName( "ortus.boxlang.moduleslug.instrumentation.DebugUtils" ).get( 0 );
		Method		snapshotMethod	= debugUtils.methodsByName( "snapshotContext" ).get( 0 );

		List<Value>	args			= new ArrayList<>();

		args.add( context );

		try {
			return debugUtils.invokeMethod( threadRef, snapshotMethod, args, ObjectReference.INVOKE_SINGLE_THREADED );
		} catch ( InvalidTypeException | ClassNotLoadedException | IncompatibleThreadStateException | InvocationException e ) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		return null;
	}

	public static Optional<ObjectReference> findNearestContext( ThreadReference threadRef ) {
		try {
			for ( var frame : threadRef.frames() ) {
				for ( var visibleVariable : frame.visibleVariables() ) {

					if ( isBoxContext( visibleVariable ) ) {
						return Optional.of( ( ObjectReference ) frame.getValue( visibleVariable ) );
					}
				}
			}
		} catch ( IncompatibleThreadStateException | AbsentInformationException e ) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		return Optional.empty();
	}

	public static ObjectReference findContextForFrame( StackFrame frame ) {
		try {
			for ( var visibleVariable : frame.visibleVariables() ) {

				if ( isBoxContext( visibleVariable ) ) {
					return ( ObjectReference ) frame.getValue( visibleVariable );
				}
			}
		} catch ( AbsentInformationException e ) {
			LOGGER.severe( "Unable to get context for frame: " + e.getMessage() );
		}
		return null;
	}

	public static Optional<ObjectReference> findNearestContext( List<StackFrame> frames ) {
		for ( var frame : frames ) {
			var context = findContextForFrame( frame );
			if ( context != null ) {
				return Optional.of( context );
			}
		}

		return Optional.empty();
	}

	public static boolean isBoxContext( LocalVariable variable ) {
		try {

			var type = variable.type();

			if ( type.name().equalsIgnoreCase( "ortus.boxlang.runtime.context.IBoxContext" ) ) {
				return true;
			}

			if ( ! ( type instanceof ClassType ) ) {
				return false;
			}

			var isBoxContext = ( ( ClassType ) type ).allInterfaces()
			    .stream()
			    .anyMatch( iname -> iname.name().equalsIgnoreCase( "ortus.boxlang.runtime.context.IBoxContext" ) );

			return isBoxContext;
		} catch ( ClassNotLoadedException exception ) {
			LOGGER.severe( "Class not loaded: " + exception.getMessage() );
		}

		return false;
	}

	public static Method findMethodByNameAndArgs( ClassType classType, String name, List<String> args ) {
		for ( Method method : classType.allMethods() ) {
			if ( !method.name().equalsIgnoreCase( name ) ) {
				continue;
			}

			List<String> argumentNames = method.argumentTypeNames();

			if ( argumentNames.size() != args.size() ) {
				continue;
			}

			boolean matches = true;

			for ( int i = 0; i < argumentNames.size(); i++ ) {
				if ( !argumentNames.get( i ).equalsIgnoreCase( args.get( i ) ) ) {
					matches = false;
					break;
				}
			}

			if ( matches ) {
				return method;
			}
		}

		return null;
	}
}
