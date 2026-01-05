package ortus.boxlang.moduleslug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for PathMappingService.
 * Tests path translation between local (editor/client) and remote (debuggee/container) paths.
 */
@DisplayName( "PathMappingService Tests" )
public class PathMappingServiceTest {

	@Nested
	@DisplayName( "Explicit Mapping Tests" )
	class ExplicitMappingTests {

		@Test
		@DisplayName( "Should translate local path to remote path with explicit mapping" )
		void testToRemotePathWithExplicitMapping() {
			PathMappingService	service		= new PathMappingService(
			    "C:/code/myapp",
			    "/app",
			    null
			);

			String				remotePath	= service.toRemotePath( "C:/code/myapp/models/User.bx" );
			assertEquals( "/app/models/User.bx", remotePath );
		}

		@Test
		@DisplayName( "Should translate remote path to local path with explicit mapping" )
		void testToLocalPathWithExplicitMapping() {
			PathMappingService	service		= new PathMappingService(
			    "C:/code/myapp",
			    "/app",
			    null
			);

			String				localPath	= service.toLocalPath( "/app/models/User.bx" );
			assertEquals( "C:/code/myapp/models/User.bx", localPath );
		}

		@Test
		@DisplayName( "Should handle Windows-style paths in explicit mapping" )
		void testWindowsPathsInExplicitMapping() {
			PathMappingService	service		= new PathMappingService(
			    "C:\\Users\\dev\\project",
			    "/home/app",
			    null
			);

			String				remotePath	= service.toRemotePath( "C:\\Users\\dev\\project\\src\\Main.bx" );
			assertEquals( "/home/app/src/Main.bx", remotePath );

			String localPath = service.toLocalPath( "/home/app/src/Main.bx" );
			assertTrue( localPath.contains( "Users/dev/project/src/Main.bx" ) );
		}

		@Test
		@DisplayName( "Should handle case-insensitive path matching" )
		void testCaseInsensitiveMatching() {
			PathMappingService	service		= new PathMappingService(
			    "C:/Code/MyApp",
			    "/app",
			    null
			);

			// Different case in input should still match
			String				remotePath	= service.toRemotePath( "c:/code/myapp/models/User.bx" );
			assertEquals( "/app/models/User.bx", remotePath );
		}

		@Test
		@DisplayName( "Should return original path if not under localRoot" )
		void testPathNotUnderLocalRoot() {
			PathMappingService	service		= new PathMappingService(
			    "C:/code/myapp",
			    "/app",
			    null
			);

			String				remotePath	= service.toRemotePath( "D:/other/project/File.bx" );
			// Path should be normalized but not translated
			assertTrue( remotePath.contains( "other/project/File.bx" ) );
		}

		@Test
		@DisplayName( "Should report hasExplicitMapping when both roots are set" )
		void testHasExplicitMapping() {
			PathMappingService withMapping = new PathMappingService( "C:/local", "/remote", null );
			assertTrue( withMapping.hasExplicitMapping() );

			PathMappingService withoutMapping = new PathMappingService( null, null, null );
			assertFalse( withoutMapping.hasExplicitMapping() );

			PathMappingService partialMapping = new PathMappingService( "C:/local", null, null );
			assertFalse( partialMapping.hasExplicitMapping() );
		}
	}

	@Nested
	@DisplayName( "Auto-Detection Tests" )
	class AutoDetectionTests {

		@Test
		@DisplayName( "Should auto-detect local path from workspace folder" )
		void testAutoDetectFromWorkspaceFolder( @TempDir Path tempDir ) throws Exception {
			// Create a file structure in the temp directory
			Path modelsDir = tempDir.resolve( "models" );
			Files.createDirectories( modelsDir );
			Path userFile = modelsDir.resolve( "User.bx" );
			Files.writeString( userFile, "// User model" );

			PathMappingService	service		= new PathMappingService(
			    null,
			    null,
			    tempDir.toString()
			);

			// Remote path with different root
			String				localPath	= service.toLocalPath( "/app/models/User.bx" );

			// Should find the file by matching the relative path
			assertTrue( localPath.contains( "models" ) && localPath.contains( "User.bx" ),
			    "Should auto-detect local path from workspace folder: " + localPath );
		}

		@Test
		@DisplayName( "Should return original path if auto-detection fails" )
		void testAutoDetectFails( @TempDir Path tempDir ) {
			PathMappingService	service		= new PathMappingService(
			    null,
			    null,
			    tempDir.toString()
			);

			// Remote path to a file that doesn't exist locally
			String				localPath	= service.toLocalPath( "/app/nonexistent/Missing.bx" );

			// Should return normalized original path
			assertEquals( "/app/nonexistent/Missing.bx", localPath );
		}
	}

	@Nested
	@DisplayName( "Path Matching Tests" )
	class PathMatchingTests {

		@Test
		@DisplayName( "Should match identical paths" )
		void testIdenticalPaths() {
			PathMappingService service = new PathMappingService( null, null, null );

			assertTrue( service.pathsMatch( "/app/User.bx", "/app/User.bx" ) );
			assertTrue( service.pathsMatch( "C:/code/User.bx", "C:/code/User.bx" ) );
		}

		@Test
		@DisplayName( "Should match paths with different separators" )
		void testDifferentSeparators() {
			PathMappingService service = new PathMappingService( null, null, null );

			assertTrue( service.pathsMatch( "C:\\code\\User.bx", "C:/code/User.bx" ) );
		}

		@Test
		@DisplayName( "Should match paths with explicit mapping" )
		void testPathsMatchWithExplicitMapping() {
			PathMappingService service = new PathMappingService(
			    "C:/code/myapp",
			    "/app",
			    null
			);

			assertTrue( service.pathsMatch( "C:/code/myapp/models/User.bx", "/app/models/User.bx" ) );
		}

		@Test
		@DisplayName( "Should match when one path is suffix of another" )
		void testSuffixMatching() {
			PathMappingService service = new PathMappingService( null, null, null );

			// Filename-only match
			assertTrue( service.pathsMatch( "User.bx", "/app/models/User.bx" ) );
			assertTrue( service.pathsMatch( "/app/models/User.bx", "User.bx" ) );

			// Relative path suffix match
			assertTrue( service.pathsMatch( "models/User.bx", "/app/models/User.bx" ) );
		}

		@Test
		@DisplayName( "Should not match completely different files" )
		void testNoMatchDifferentFiles() {
			PathMappingService service = new PathMappingService( null, null, null );

			assertFalse( service.pathsMatch( "/app/User.bx", "/app/Admin.bx" ) );
			assertFalse( service.pathsMatch( "User.bx", "Admin.bx" ) );
		}

		@Test
		@DisplayName( "Should handle null paths" )
		void testNullPaths() {
			PathMappingService service = new PathMappingService( null, null, null );

			assertFalse( service.pathsMatch( null, "/app/User.bx" ) );
			assertFalse( service.pathsMatch( "/app/User.bx", null ) );
			assertFalse( service.pathsMatch( null, null ) );
		}
	}

	@Nested
	@DisplayName( "Static Utility Tests" )
	class StaticUtilityTests {

		@Test
		@DisplayName( "Should normalize paths correctly" )
		void testNormalizePath() {
			// Forward slashes
			assertEquals( "path/to/file.bx", PathMappingService.normalizePath( "path\\to\\file.bx" )
			    .replace( File.separatorChar, '/' ).replaceAll( "^[A-Za-z]:/", "" ) );

			// Trailing slash removal
			String normalized = PathMappingService.normalizePath( "/app/models/" );
			assertFalse( normalized.endsWith( "/" ) || normalized.length() == 1 );

			// Null handling
			assertEquals( "", PathMappingService.normalizePath( null ) );
			assertEquals( "", PathMappingService.normalizePath( "" ) );
		}

		@Test
		@DisplayName( "Should extract filename correctly" )
		void testGetFileName() {
			assertEquals( "User.bx", PathMappingService.getFileName( "/app/models/User.bx" ) );
			assertEquals( "User.bx", PathMappingService.getFileName( "C:\\code\\models\\User.bx" ) );
			assertEquals( "User.bx", PathMappingService.getFileName( "User.bx" ) );

			assertNull( PathMappingService.getFileName( null ) );
			assertNull( PathMappingService.getFileName( "" ) );
		}
	}

	@Nested
	@DisplayName( "Edge Cases" )
	class EdgeCaseTests {

		@Test
		@DisplayName( "Should handle empty strings" )
		void testEmptyStrings() {
			PathMappingService service = new PathMappingService( "", "", "" );

			assertFalse( service.hasExplicitMapping() );
			assertEquals( "", service.toRemotePath( "" ) );
			assertEquals( "", service.toLocalPath( "" ) );
		}

		@Test
		@DisplayName( "Should handle paths with spaces" )
		void testPathsWithSpaces() {
			PathMappingService	service		= new PathMappingService(
			    "C:/My Projects/my app",
			    "/home/user/my app",
			    null
			);

			String				remotePath	= service.toRemotePath( "C:/My Projects/my app/src/Main.bx" );
			assertEquals( "/home/user/my app/src/Main.bx", remotePath );
		}

		@Test
		@DisplayName( "Should handle deeply nested paths" )
		void testDeeplyNestedPaths() {
			PathMappingService	service		= new PathMappingService(
			    "C:/code",
			    "/app",
			    null
			);

			String				remotePath	= service.toRemotePath( "C:/code/src/main/java/com/example/models/User.bx" );
			assertEquals( "/app/src/main/java/com/example/models/User.bx", remotePath );

			String localPath = service.toLocalPath( "/app/src/main/java/com/example/models/User.bx" );
			assertEquals( "C:/code/src/main/java/com/example/models/User.bx", localPath );
		}

		@Test
		@DisplayName( "Should preserve getters" )
		void testGetters() {
			PathMappingService service = new PathMappingService(
			    "C:/local",
			    "/remote",
			    "C:/workspace"
			);

			assertTrue( service.getLocalRoot().contains( "local" ) );
			assertEquals( "/remote", service.getRemoteRoot() );
			assertTrue( service.getWorkspaceFolder().contains( "workspace" ) );
		}
	}
}
