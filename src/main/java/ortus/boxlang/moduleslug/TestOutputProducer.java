package ortus.boxlang.moduleslug;

/**
 * Simple test class that produces output for testing the debugger's output capture functionality
 */
public class TestOutputProducer {

	public static void main( String[] args ) {
		// Check if we should produce specific output based on the program being "run"
		if ( args.length > 0 && args[ 0 ].endsWith( "output.bxs" ) ) {
			// Simulate output from the output.bxs script
			System.out.println( "This is output from boxlang" );
			return;
		}

		System.out.println( "TestOutputProducer starting..." );

		// Give the debugger time to set breakpoints
		try {
			Thread.sleep( 1000 );
		} catch ( InterruptedException e ) {
			Thread.currentThread().interrupt();
		}

		// This line should be hit by a breakpoint
		int result = add( 3, 5 );

		// Output what we would expect from running the breakpoint.bxs script
		System.out.println( result );

		// Also output to stderr for testing
		System.err.println( "Test stderr output" );

		// Give the debugger time to capture the output
		try {
			Thread.sleep( 2000 );
		} catch ( InterruptedException e ) {
			Thread.currentThread().interrupt();
		}

		System.out.println( "TestOutputProducer ending..." );
	}

	/**
	 * Simple function that adds two numbers - this is where we'll set a breakpoint
	 */
	public static int add( int a, int b ) {
		return a + b; // Line 41 - breakpoint target
	}
}
