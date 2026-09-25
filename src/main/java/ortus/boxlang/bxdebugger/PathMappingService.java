/**
 * [BoxLang]
 *
 * Copyright [2023] [Ortus Solutions, Corp]
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ortus.boxlang.bxdebugger;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Logger;

/**
 * Service for translating file paths between local (editor/client) and remote (debuggee/container) paths.
 *
 * This enables debugging of applications running in Docker containers or remote systems where the file
 * paths differ from the local development environment.
 *
 * Example: Local path "C:\code\myapp\models\User.bx" maps to remote path "/app/models/User.bx"
 * with localRoot="C:\code\myapp" and remoteRoot="/app"
 */
public class PathMappingService {

	private static final Logger	LOGGER	= Logger.getLogger( PathMappingService.class.getName() );

	private String				localRoot;
	private String				remoteRoot;
	private String				workspaceFolder;
	private boolean				hasExplicitMapping;

	/**
	 * Create a new PathMappingService with optional explicit mapping and workspace folder.
	 *
	 * @param localRoot       The local root path (client/editor side), or null for auto-detection
	 * @param remoteRoot      The remote root path (debuggee/container side), or null for auto-detection
	 * @param workspaceFolder The workspace folder path for auto-detection fallback, or null
	 */
	public PathMappingService( String localRoot, String remoteRoot, String workspaceFolder ) {
		this.localRoot			= normalizePath( localRoot );
		this.remoteRoot			= normalizePath( remoteRoot );
		this.workspaceFolder	= normalizePath( workspaceFolder );
		this.hasExplicitMapping	= localRoot != null && remoteRoot != null && !localRoot.isEmpty() && !remoteRoot.isEmpty();

		if ( hasExplicitMapping ) {
			LOGGER.info( "Path mapping configured: localRoot=" + this.localRoot + " -> remoteRoot=" + this.remoteRoot );
		} else if ( this.workspaceFolder != null && !this.workspaceFolder.isEmpty() ) {
			LOGGER.info( "Path mapping using auto-detection with workspaceFolder=" + this.workspaceFolder );
		} else {
			LOGGER.info( "Path mapping disabled - no explicit mapping or workspace folder provided" );
		}
	}

	/**
	 * Translate a local (editor/client) path to a remote (debuggee/container) path.
	 *
	 * @param localPath The local file path from the editor
	 *
	 * @return The translated remote path, or the original path if no mapping applies
	 */
	public String toRemotePath( String localPath ) {
		if ( localPath == null || localPath.isEmpty() ) {
			return localPath;
		}

		String normalizedLocal = normalizePath( localPath );

		// If we have an explicit mapping, use it
		if ( hasExplicitMapping ) {
			if ( isUnderRoot( normalizedLocal, localRoot ) ) {
				String	relativePath	= normalizedLocal.substring( localRoot.length() );
				String	remotePath		= joinRoot( remoteRoot, relativePath );
				LOGGER.fine( "Translated local->remote: " + localPath + " -> " + remotePath );
				return remotePath;
			}
		}

		// No mapping applied, return normalized path
		return normalizedLocal;
	}

	/**
	 * Translate a remote (debuggee/container) path to a local (editor/client) path.
	 *
	 * @param remotePath The remote file path from the debuggee
	 *
	 * @return The translated local path, or the original path if no mapping applies
	 */
	public String toLocalPath( String remotePath ) {
		if ( remotePath == null || remotePath.isEmpty() ) {
			return remotePath;
		}

		String normalizedRemote = normalizePath( remotePath );

		// If we have an explicit mapping, use it
		if ( hasExplicitMapping ) {
			if ( isUnderRoot( normalizedRemote, remoteRoot ) ) {
				String	relativePath	= normalizedRemote.substring( remoteRoot.length() );
				String	localPath		= joinRoot( localRoot, relativePath );
				LOGGER.fine( "Translated remote->local: " + remotePath + " -> " + localPath );
				return localPath;
			}
		}

		// Try auto-detection using workspace folder and filename matching
		if ( workspaceFolder != null && !workspaceFolder.isEmpty() ) {
			String autoDetected = tryAutoDetect( normalizedRemote );
			if ( autoDetected != null ) {
				return autoDetected;
			}
		}

		// No mapping applied, return normalized path
		return normalizedRemote;
	}

	/**
	 * Try to auto-detect the local path by matching the filename and relative path structure
	 * against files in the workspace folder.
	 *
	 * @param remotePath The normalized remote path
	 *
	 * @return The detected local path, or null if no match found
	 */
	private String tryAutoDetect( String remotePath ) {
		// Extract the filename from the remote path
		String fileName = getFileName( remotePath );
		if ( fileName == null || fileName.isEmpty() ) {
			return null;
		}

		// Try to find a matching relative path structure
		// For example, if remote is "/app/models/User.bx" and workspace is "C:/code/myapp"
		// we look for "C:/code/myapp/models/User.bx"

		// Get the relative path from the remote path (try different depths)
		String[] remoteSegments = remotePath.split( "/" );

		// Try progressively longer suffixes of the remote path
		for ( int i = remoteSegments.length - 1; i >= 0; i-- ) {
			StringBuilder relativePath = new StringBuilder();
			for ( int j = i; j < remoteSegments.length; j++ ) {
				if ( !remoteSegments[ j ].isEmpty() ) {
					if ( relativePath.length() > 0 ) {
						relativePath.append( "/" );
					}
					relativePath.append( remoteSegments[ j ] );
				}
			}

			String	candidatePath	= workspaceFolder + "/" + relativePath.toString();
			Path	candidate		= Paths.get( candidatePath.replace( '/', java.io.File.separatorChar ) );

			if ( candidate.toFile().exists() ) {
				String result = normalizePath( candidate.toString() );
				LOGGER.fine( "Auto-detected path mapping: " + remotePath + " -> " + result );
				return result;
			}
		}

		return null;
	}

	/**
	 * Check if two paths refer to the same file, accounting for path mapping.
	 * Compare normalized identities using explicit root mappings and filesystem case semantics.
	 * Basename/suffix heuristics cannot establish source identity.
	 *
	 * @param path1 First path (can be local or remote)
	 * @param path2 Second path (can be local or remote)
	 *
	 * @return true if the paths refer to the same logical file
	 */
	public boolean pathsMatch( String path1, String path2 ) {
		if ( path1 == null || path2 == null ) {
			return false;
		}

		return samePath( path1, path2 ) || samePath( toRemotePath( path1 ), toRemotePath( path2 ) );
	}

	/**
	 * Extract the filename from a path.
	 *
	 * @param path The file path
	 *
	 * @return The filename, or null if path is null/empty
	 */
	public static String getFileName( String path ) {
		if ( path == null || path.isEmpty() ) {
			return null;
		}

		String	normalized	= path.replace( '\\', '/' );
		int		lastSlash	= normalized.lastIndexOf( '/' );
		if ( lastSlash >= 0 && lastSlash < normalized.length() - 1 ) {
			return normalized.substring( lastSlash + 1 );
		}
		return normalized;
	}

	/**
	 * Normalize a path for consistent comparison.
	 * Converts backslashes to forward slashes, removes trailing slashes.
	 *
	 * @param path The path to normalize
	 *
	 * @return The normalized path, or empty string if null
	 */
	public static String normalizePath( String path ) {
		if ( path == null || path.isEmpty() ) {
			return "";
		}

		String	normalized						= path.replace( '\\', '/' );
		boolean	unc								= normalized.startsWith( "//" );

		// Check if this looks like a Windows absolute path (e.g., "C:/code" or "C:\code")
		boolean	looksLikeWindowsAbsolutePath	= path.length() >= 2 &&
		    Character.isLetter( path.charAt( 0 ) ) &&
		    ( path.charAt( 1 ) == ':' );

		// Only call toAbsolutePath() for paths that are actually absolute on this OS
		// Don't call it for Windows-style paths on Unix/macOS
		boolean	isWindows						= System.getProperty( "os.name" ).toLowerCase().contains( "win" );

		try {
			Path p = Paths.get( normalized );
			// Only convert to absolute if it's truly absolute on this OS
			// (avoid prepending current directory to Windows paths on non-Windows systems)
			if ( p.isAbsolute() && ( isWindows || !looksLikeWindowsAbsolutePath ) ) {
				normalized = p.toAbsolutePath().normalize().toString();
			} else {
				// Just normalize the path without making it absolute
				normalized = p.normalize().toString();
			}
		} catch ( Exception e ) {
			// Path may be a remote path format, just normalize separators
		}

		// Replace backslashes with forward slashes
		normalized = normalized.replace( '\\', '/' );
		if ( unc && !normalized.startsWith( "//" ) ) {
			normalized = "/" + normalized;
		}

		// Remove trailing slash
		while ( normalized.length() > 1 && normalized.endsWith( "/" ) ) {
			normalized = normalized.substring( 0, normalized.length() - 1 );
		}

		return normalized;
	}

	/**
	 * Join a mapped root to a relative suffix without dropping or duplicating separators.
	 */
	private String joinRoot( String root, String relative ) {
		if ( relative.isEmpty() )
			return root;
		return root.replaceAll( "/+$", "" ) + "/" + relative.replaceFirst( "^/", "" );
	}

	private boolean isUnderRoot( String path, String root ) {
		if ( root.isEmpty() || path.length() < root.length() ) {
			return false;
		}
		return samePath( path.substring( 0, root.length() ), root )
		    && ( path.length() == root.length() || root.endsWith( "/" ) || path.charAt( root.length() ) == '/' );
	}

	public static boolean samePath( String first, String second ) {
		String	a		= normalizePath( first );
		String	b		= normalizePath( second );
		boolean	windows	= a.matches( "^[A-Za-z]:/.*" ) || a.startsWith( "//" );
		return windows ? a.equalsIgnoreCase( b ) : a.equals( b );
	}

	/**
	 * Check if an explicit path mapping is configured.
	 *
	 * @return true if localRoot and remoteRoot are both set
	 */
	public boolean hasExplicitMapping() {
		return hasExplicitMapping;
	}

	/**
	 * Get the configured local root path.
	 *
	 * @return The local root path, or empty string if not set
	 */
	public String getLocalRoot() {
		return localRoot != null ? localRoot : "";
	}

	/**
	 * Get the configured remote root path.
	 *
	 * @return The remote root path, or empty string if not set
	 */
	public String getRemoteRoot() {
		return remoteRoot != null ? remoteRoot : "";
	}

	/**
	 * Get the configured workspace folder path.
	 *
	 * @return The workspace folder path, or empty string if not set
	 */
	public String getWorkspaceFolder() {
		return workspaceFolder != null ? workspaceFolder : "";
	}
}
