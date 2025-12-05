package ortus.boxlang.moduleslug.instrumentation;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * DebuggerHelper
 * <p>
 * Minimal, self-contained helper to run in the target VM. It exposes a tiny enqueue/poll API
 * suitable for being driven via JDI's ClassType.invokeMethod(...). The debugger should only
 * call these methods using a dedicated "invoker" ThreadReference (a short suspended thread) —
 * the static methods below are intentionally short: they only enqueue tasks or read results.
 * The heavy reflective invocation runs on a separate worker thread.
 *
 * Usage summary:
 * - Debugger calls DebuggerHelper.start() once (from a safe suspended event thread) to ensure
 * the invoker and worker threads exist.
 * - Debugger finds the invoker ThreadReference by name and uses it to invoke the static methods
 * (enqueueOnObject / enqueueStatic / pollResult) via JDI. Keep those calls short.
 *
 * Notes:
 * - enqueueOnObject accepts a real Object instance (Object) as the first parameter — when called
 * from JDI the ObjectReference will be marshalled and the helper receives the real target object.
 * - The helper stores results in an internal concurrent map keyed by task id. pollResult removes
 * the result and returns it (so the map does not grow unbounded by default).
 * - The helper itself makes a best-effort to survive any thrown exceptions and will keep processing
 * tasks.
 */
public final class DebuggerHelper {

	public static final String												INVOKER_THREAD_NAME	= "DebuggerInvoker-DEBUGGER_HELPER";

	private static final BlockingQueue<Task>								QUEUE				= new LinkedBlockingQueue<>();
	private static final ConcurrentHashMap<String, Result>					RESULTS				= new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<String, WeakReference<Object>>	REGISTRY			= new ConcurrentHashMap<>();
	private static volatile boolean											started				= false;

	// Worker thread reference (keeps the worker alive in the target VM)
	private static Thread													workerThread;
	private static Thread													invokerThread;

	private DebuggerHelper() {
		// no instances
	}

	/**
	 * Idempotent startup. Call this once (from the debugger) to ensure the invoker and worker exist.
	 */
	public static synchronized void start() {
		if ( started )
			return;
		// start worker
		workerThread = new Thread( () -> {
			while ( !Thread.currentThread().isInterrupted() ) {
				try {
					// System.out.println( "Starting queue" );
					Task	t	= QUEUE.take();
					// System.out.println( "Found t - executing" );
					Result	r	= executeTask( t );
					if ( r != null ) {
						// System.out.println( "Got a result - storing in results: " + t.id );
						RESULTS.put( t.id, r );
					}
				} catch ( InterruptedException ie ) {
					// System.out.println( "Interrupted while waiting for task" );
					Thread.currentThread().interrupt();
					break;
				} catch ( Throwable thr ) {
					// System.out.println( "Error in DebuggerHelper worker: " + thr.getMessage() );
					// Protect worker from dying; record a global failure result if task present
					// (shouldn't happen because executeTask handles task exceptions)
				}
			}
		}, "DebuggerHelper-Worker" );
		workerThread.setDaemon( true );
		workerThread.start();

		// start invoker thread: a minimal thread that sleeps. Debugger will suspend this thread and use it
		// as the ThreadReference argument for ClassType.invokeMethod(...). The invoker must do nothing heavy.
		invokerThread = new Thread( () -> {
			Object lock = new Object();
			synchronized ( lock ) {
				while ( true ) {
					try {
						methodEntryBreakpointHook();
						// System.out.println( "IN THE WORKERLOOP" );
						Thread.sleep( 100 ); // Simulate work or wait
						// lock.wait(); // park until work is available
					} catch ( InterruptedException ignored ) {
					}
				}
			}
		}, INVOKER_THREAD_NAME );
		invokerThread.setDaemon( true );
		invokerThread.start();

		started = true;
	}

	public static void methodEntryBreakpointHook() {
		// System.out.println( "IN THE WORKERLOOP" );
	}

	/**
	 * Enqueue a reflective instance invocation on the provided target object.
	 * This method is intentionally short: it merely enqueues a task and returns an id.
	 *
	 * @param target         the object instance to invoke on (received as a real Object inside the target)
	 * @param methodName     simple name of the method to invoke
	 * @param paramTypeNames fully-qualified class names of parameter types (use primitive names for primitives, e.g. "int")
	 * @param args           argument values (objects) as passed into the helper — those are used by reflection
	 * 
	 * @return task id string
	 */
	public static String enqueueOnObject( Object target, String methodName, String[] paramTypeNames, Object[] args ) {
		Objects.requireNonNull( methodName, "methodName" );
		String	id	= genId();
		Task	t	= new Task( id, target, null, methodName, paramTypeNames, args, TaskType.INSTANCE );
		QUEUE.add( t );
		return id;
	}

	/**
	 * Enqueue a reflective static invocation.
	 */
	public static String enqueueStatic( String className, String methodName, String[] paramTypeNames, Object[] args ) {
		Objects.requireNonNull( className, "className" );
		Objects.requireNonNull( methodName, "methodName" );
		String	id	= genId();
		Task	t	= new Task( id, null, className, methodName, paramTypeNames, args, TaskType.STATIC );
		QUEUE.add( t );
		return id;
	}

	/**
	 * Register an object in the helper registry and return an id you can later use to enqueue tasks by id.
	 * The registry uses WeakReference to avoid strong leaks by default.
	 */
	public static String register( Object target ) {
		Objects.requireNonNull( target, "target" );
		String id = genId();
		REGISTRY.put( id, new WeakReference<>( target ) );
		return id;
	}

	/**
	 * Enqueue by previously registered id.
	 */
	public static String enqueueById( String registeredId, String methodName, String[] paramTypeNames, Object[] args ) {
		Objects.requireNonNull( registeredId, "registeredId" );
		WeakReference<Object>	wr		= REGISTRY.get( registeredId );
		Object					target	= ( wr == null ) ? null : wr.get();
		if ( target == null )
			throw new IllegalStateException( "Registered object not found or has been GC'd: " + registeredId );
		return enqueueOnObject( target, methodName, paramTypeNames, args );
	}

	/**
	 * Poll result and remove it from the result map. Returns null if not ready.
	 */
	public static Result pollResult( String taskId ) {
		// System.out.println( "Polling result for taskId: " + taskId );
		// System.out.println( "RESULTS len: " + RESULTS.size() );
		// RESULTS.keySet().forEach( k -> System.out.println( " key: " + k ) );

		var res = RESULTS.remove( taskId );

		// System.out.println( "Poll result found: " + ( res != null ? "yes" : "no" ) );
		return res;
	}

	/**
	 * Peek result without removing (may be null).
	 */
	public static Result peekResult( String taskId ) {
		return RESULTS.get( taskId );
	}

	/**
	 * Blocking get with timeout (ms). Returns null if timeout elapses without a result.
	 */
	public static Result getResultBlocking( String taskId, long timeoutMs ) throws InterruptedException {
		long deadline = System.currentTimeMillis() + Math.max( 0, timeoutMs );
		while ( System.currentTimeMillis() < deadline ) {
			Result r = RESULTS.remove( taskId );
			if ( r != null )
				return r;
			Thread.sleep( 20 );
		}
		return null;
	}

	/**
	 * Shutdown helper threads (best-effort). Not strictly required but helpful for tests.
	 */
	public static synchronized void shutdown() {
		started = false;
		Thread	w	= workerThread;
		Thread	i	= invokerThread;
		if ( w != null )
			w.interrupt();
		if ( i != null )
			i.interrupt();
		QUEUE.clear();
		RESULTS.clear();
		REGISTRY.clear();
	}

	// ------------------ internal helpers ------------------

	private static Result executeTask( Task t ) {
		try {
			switch ( t.type ) {
				case INSTANCE :
					return execInstance( t );
				case STATIC :
					return execStatic( t );
				default :
					return new Result( null, new IllegalArgumentException( "Unknown task type" ) );
			}
		} catch ( Throwable thr ) {
			return new Result( null, thr );
		}
	}

	private static Result execInstance( Task t ) {
		Object target = t.target;
		if ( target == null )
			return new Result( null, new IllegalArgumentException( "target is null" ) );
		try {
			Class<?>	cls			= target.getClass();
			Class<?>[]	paramTypes	= resolveParamTypes( t.paramTypeNames );
			Method		m			= findMethod( cls, t.methodName, paramTypes );
			if ( m == null )
				return new Result( null, new NoSuchMethodException( "Method not found: " + t.methodName + " on " + cls.getName() ) );
			m.setAccessible( true );
			Object rv = m.invoke( target, safeArgsForInvocation( m.getParameterTypes(), t.args ) );
			return new Result( rv, null );
		} catch ( Throwable thr ) {
			return new Result( null, thr );
		}
	}

	private static Result execStatic( Task t ) {
		try {
			Class<?>	cls			= Class.forName( t.className );
			Class<?>[]	paramTypes	= resolveParamTypes( t.paramTypeNames );
			Method		m			= findMethod( cls, t.methodName, paramTypes );
			if ( m == null )
				return new Result( null, new NoSuchMethodException( "Static method not found: " + t.methodName + " on " + t.className ) );
			m.setAccessible( true );
			Object rv = m.invoke( null, safeArgsForInvocation( m.getParameterTypes(), t.args ) );
			return new Result( rv, null );
		} catch ( Throwable thr ) {
			return new Result( null, thr );
		}
	}

	private static Object[] safeArgsForInvocation( Class<?>[] paramTypes, Object[] providedArgs ) {
		if ( ( providedArgs == null || providedArgs.length == 0 ) && ( paramTypes == null || paramTypes.length == 0 ) )
			return new Object[ 0 ];
		Object[] out = new Object[ paramTypes.length ];
		for ( int i = 0; i < paramTypes.length; ++i ) {
			Object provided = ( providedArgs != null && i < providedArgs.length ) ? providedArgs[ i ] : null;
			out[ i ] = coerceArgIfNeeded( paramTypes[ i ], provided );
		}
		return out;
	}

	private static Object coerceArgIfNeeded( Class<?> paramType, Object provided ) {
		// In the target VM, typical uses will pass boxed primitives or the correct object references.
		// This helper does minimal coercion: unbox wrappers to primitives where required.
		if ( provided == null )
			return null;
		if ( paramType.isPrimitive() ) {
			// handle common wrappers
			if ( paramType == int.class && provided instanceof Number )
				return ( ( Number ) provided ).intValue();
			if ( paramType == long.class && provided instanceof Number )
				return ( ( Number ) provided ).longValue();
			if ( paramType == short.class && provided instanceof Number )
				return ( ( Number ) provided ).shortValue();
			if ( paramType == byte.class && provided instanceof Number )
				return ( ( Number ) provided ).byteValue();
			if ( paramType == float.class && provided instanceof Number )
				return ( ( Number ) provided ).floatValue();
			if ( paramType == double.class && provided instanceof Number )
				return ( ( Number ) provided ).doubleValue();
			if ( paramType == boolean.class && provided instanceof Boolean )
				return ( ( Boolean ) provided ).booleanValue();
			if ( paramType == char.class && provided instanceof Character )
				return ( ( Character ) provided ).charValue();
			// fallback: hope reflection will coerce or throw
		}
		return provided;
	}

	private static Class<?>[] resolveParamTypes( String[] names ) throws ClassNotFoundException {
		if ( names == null || names.length == 0 )
			return new Class<?>[ 0 ];
		Class<?>[] out = new Class<?>[ names.length ];
		for ( int i = 0; i < names.length; ++i ) {
			out[ i ] = resolveClassByName( names[ i ] );
		}
		return out;
	}

	private static Class<?> resolveClassByName( String name ) throws ClassNotFoundException {
		switch ( name ) {
			case "int" :
				return int.class;
			case "long" :
				return long.class;
			case "short" :
				return short.class;
			case "byte" :
				return byte.class;
			case "char" :
				return char.class;
			case "boolean" :
				return boolean.class;
			case "float" :
				return float.class;
			case "double" :
				return double.class;
			case "void" :
				return void.class;
			default :
				return Class.forName( name );
		}
	}

	// TODO probably want to change this to just directly use the signature
	private static Method findMethod( Class<?> cls, String methodName, Class<?>[] paramTypes ) {
		try {
			return cls.getDeclaredMethod( methodName, paramTypes );
		} catch ( NoSuchMethodException ignored ) {
			// fallback: try to find a compatible method by name and parameter count / assignability
			for ( Method m : cls.getMethods() ) {
				if ( !m.getName().equals( methodName ) )
					continue;
				Class<?>[] sig = m.getParameterTypes();
				if ( sig.length != ( paramTypes == null ? 0 : paramTypes.length ) )
					continue;
				boolean ok = true;
				for ( int i = 0; i < sig.length; ++i ) {
					if ( paramTypes[ i ] == null )
						continue; // unknown param type, accept
					if ( !isAssignable( sig[ i ], paramTypes[ i ] ) ) {
						ok = false;
						break;
					}
				}
				if ( ok )
					return m;
			}
			return null;
		}
	}

	private static boolean isAssignable( Class<?> target, Class<?> source ) {
		if ( target.isPrimitive() ) {
			// treat primitive boxing as assignable for common cases
			if ( target == int.class && Integer.class.equals( source ) )
				return true;
			if ( target == long.class && Long.class.equals( source ) )
				return true;
			if ( target == boolean.class && Boolean.class.equals( source ) )
				return true;
			if ( target == byte.class && Byte.class.equals( source ) )
				return true;
			if ( target == short.class && Short.class.equals( source ) )
				return true;
			if ( target == char.class && Character.class.equals( source ) )
				return true;
			if ( target == float.class && Float.class.equals( source ) )
				return true;
			if ( target == double.class && Double.class.equals( source ) )
				return true;
			return false;
		}
		return target.isAssignableFrom( source );
	}

	private static String genId() {
		return UUID.randomUUID().toString();
	}

	// ----------------- inner data classes -----------------
	private enum TaskType {
		INSTANCE, STATIC
	}

	private static final class Task {

		final String	id;
		final Object	target;       // may be null for static
		final String	className;    // for static
		final String	methodName;
		final String[]	paramTypeNames;
		final Object[]	args;
		final TaskType	type;

		Task( String id, Object target, String className, String methodName, String[] paramTypeNames, Object[] args, TaskType type ) {
			this.id				= id;
			this.target			= target;
			this.className		= className;
			this.methodName		= methodName;
			this.paramTypeNames	= paramTypeNames;
			this.args			= args;
			this.type			= type;
		}
	}

	public static final class Result {

		public final Object		value; // may be null
		public final Throwable	exception; // may be null
		public final long		timestampMs;

		Result( Object v, Throwable ex ) {
			this.value			= v;
			this.exception		= ex;
			this.timestampMs	= System.currentTimeMillis();
		}

		public boolean isException() {
			return exception != null;
		}
	}
}
