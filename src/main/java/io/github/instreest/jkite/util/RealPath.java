package io.github.instreest.jkite.util;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

import dev.jbang.ExitException;

/**
 * The file a shell would open, rather than the one string folding arrives at.
 *
 * toAbsolutePath().normalize() folds ".." textually: it looks at "/a/b/../c",
 * sees that ".." cancels the component before it, and answers "/a/c" without
 * asking the file system anything. That is right only while no component is a
 * link.
 *
 * If "b" is a symbolic link to "/elsewhere/d", then opening "/a/b/../c" goes
 * through the link first and lands in "/elsewhere/c". So a shell, and every
 * other tool, reads "/elsewhere/c" - and jkite, having folded the string,
 * would compile and run "/a/c". A different file, from the same path, with
 * nothing said about it. That was measured, not supposed.
 *
 * toRealPath() asks the file system instead: it resolves each link as it
 * walks, and applies ".." to what the link actually reached. Same procedure
 * as the shell, so the same answer.
 *
 * Unlike folding, it needs the file to exist. Everything here is a file jkite
 * is about to read - the script, a //SOURCES sibling, a //FILES resource - so
 * one that is not there is a mistake to report and not a path to carry on
 * with. It says so, naming which file and why, rather than folding the string
 * and letting the failure surface somewhere further on as something else.
 */
public final class RealPath {

	private RealPath() {
	}

	/**
	 * @param what what this path is, for the message: "The script", "The
	 *             source named by //SOURCES"
	 */
	public static Path of(Path path, String what) {
		try {
			return path.toRealPath();
		} catch (NoSuchFileException e) {
			throw new ExitException(ExitException.EXIT_INVALID_INPUT,
					what + " could not be found: " + path);
		} catch (IOException e) {
			throw new ExitException(ExitException.EXIT_INVALID_INPUT,
					what + " could not be read: " + path + " (" + e + ")");
		}
	}
}
