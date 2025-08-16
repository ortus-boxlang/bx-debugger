package ortus.boxlang.moduleslug.instrumentation;

import java.lang.instrument.Instrumentation;

public class DebugAgent {

	private static final Object lock = new Object();

	public static void agentmain( String agentArgs, Instrumentation inst ) {
		System.out.println( "[DebugAgent] Agent attached!" );

		Thread debugThread = new Thread( () -> {
			workerLoop();
		} );

		debugThread.setDaemon( true );
		debugThread.setName( "bxDebugAgent" );

		debugThread.start();

		// OPTIONAL: Load or register DebugUtils here
		try {
			Class<?> utilsClass = Class.forName( "com.example.debug.DebugUtils" );
			System.out.println( "[DebugAgent] DebugUtils already loaded: " + utilsClass );
		} catch ( ClassNotFoundException e ) {
			System.out.println( "[DebugAgent] DebugUtils not found. You could define it here." );
		}
	}

	public static void workerLoop() {
		synchronized ( lock ) {
			while ( true ) {
				try {
					methodEntryBreakpoinHook();
					System.out.println( "IN THE WORKERLOOP" );
					Thread.sleep( 100 ); // Simulate work or wait
					// lock.wait(); // park until work is available
				} catch ( InterruptedException ignored ) {
				}
			}
		}
	}

	public static void methodEntryBreakpoinHook() {

	}
}