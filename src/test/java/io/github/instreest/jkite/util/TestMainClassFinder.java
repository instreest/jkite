package io.github.instreest.jkite.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Finding the class to run when a script does not name one with //MAIN.
 *
 * It reads the compiled class files rather than the source, and it reads them
 * by hand rather than with a bytecode library, which keeps a megabyte out of
 * the jar and puts the whole of the class file format in this project's care.
 * So the classes here are compiled by the JDK the tests run on: what has to
 * keep working is reading what a real javac writes, and a JDK that starts
 * writing something new should fail here rather than in front of a user.
 */
class TestMainClassFinder {

	@TempDir
	Path dir;

	/** Compiles the sources (file name to source) and returns the class output. */
	private Path compile(Map<String, String> sources) throws IOException {
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		assumeTrue(compiler != null, "no compiler here to make the classes to read");
		Path src = Files.createDirectories(dir.resolve("src"));
		Path out = Files.createDirectories(dir.resolve("classes"));
		List<String> args = new ArrayList<>(Arrays.asList("-d", out.toString()));
		for (Map.Entry<String, String> source : sources.entrySet()) {
			Path file = src.resolve(source.getKey());
			Files.createDirectories(file.getParent());
			Files.write(file, source.getValue().getBytes(StandardCharsets.UTF_8));
			args.add(file.toString());
		}
		ByteArrayOutputStream errors = new ByteArrayOutputStream();
		int status = compiler.run(null, null, errors, args.toArray(new String[0]));
		assertEquals(0, status, "the test's own sources did not compile: " + errors);
		return out;
	}

	private Path compile(String name, String source) throws IOException {
		Map<String, String> sources = new LinkedHashMap<>();
		sources.put(name, source);
		return compile(sources);
	}

	@Test
	void aClassWithTheUsualMainIsFoundUnderItsFullName() throws IOException {
		Path classes = compile("tools/Report.java",
				"package tools;\n"
						+ "public class Report { public static void main(String[] args) {} }\n");

		assertEquals(Arrays.asList("tools.Report"), MainClassFinder.findMainClasses(classes));
	}

	@Test
	void aClassWithoutAMainIsNotOne() throws IOException {
		Path classes = compile("tools/Helper.java",
				"package tools;\n"
						+ "public class Helper { public static void help() {} }\n");

		assertEquals(0, MainClassFinder.findMainClasses(classes).size());
	}

	/**
	 * JEP 512 lets a main be an instance method with no arguments. A script
	 * written that way is the shortest one there is, so it has to be found.
	 */
	@Test
	void theShortMainOfJep512IsFoundToo() throws IOException {
		Path classes = compile("Short.java", "public class Short { void main() {} }\n");

		assertEquals(Arrays.asList("Short"), MainClassFinder.findMainClasses(classes));
	}

	/** A main that takes the wrong arguments is not a main. */
	@Test
	void aMethodNamedMainThatCannotBeStartedIsIgnored() throws IOException {
		Path classes = compile("Odd.java", "public class Odd { public static void main(int n) {} }\n");

		assertEquals(0, MainClassFinder.findMainClasses(classes).size());
	}

	/** The JVM cannot start a nested class of a script, so it is not offered. */
	@Test
	void aMainInsideANestedClassIsNotOffered() throws IOException {
		Path classes = compile("Outer.java",
				"public class Outer {\n"
						+ "  public static class Inner { public static void main(String[] args) {} }\n"
						+ "}\n");

		assertEquals(0, MainClassFinder.findMainClasses(classes).size());
	}

	@Test
	void everyClassWithAMainIsReturned() throws IOException {
		Map<String, String> sources = new LinkedHashMap<>();
		sources.put("a/First.java", "package a;\npublic class First { public static void main(String[] a) {} }\n");
		sources.put("b/Second.java", "package b;\npublic class Second { public static void main(String[] a) {} }\n");

		List<String> mains = MainClassFinder.findMainClasses(compile(sources));

		assertEquals(Arrays.asList("a.First", "b.Second"), mains);
	}

	/**
	 * The reader walks the constant pool by hand, and every entry has to be
	 * stepped over by exactly its own length: miss by a byte and what follows
	 * is read as something else. The pool of a script that does ordinary things
	 * holds most of the kinds there are, so this compiles one and expects its
	 * main to come back.
	 *
	 * A JDK that writes a kind this does not know makes this fail, which is the
	 * point: the alternative is a user finding out.
	 */
	@Test
	void aClassUsingWhatScriptsUseIsReadWithoutLosingThePlace() throws IOException {
		Path classes = compile("Busy.java",
				"import java.util.*;\n"
						+ "import java.util.function.*;\n"
						+ "public class Busy {\n"
						+ "  static final long BIG = 1234567890123L;\n"
						+ "  static final double RATE = 3.14159d;\n"
						+ "  static final String NAME = \"busy\";\n"
						+ "  enum Colour { RED, GREEN }\n"
						+ "  record Point(int x, int y) {}\n"
						+ "  public static void main(String[] args) {\n"
						+ "    Supplier<String> lambda = () -> NAME + BIG + RATE;\n"
						+ "    Runnable methodRef = Busy::helper;\n"
						+ "    List<String> list = new ArrayList<>();\n"
						+ "    list.add(lambda.get());\n"
						+ "    methodRef.run();\n"
						+ "    switch (args.length == 0 ? \"one\" : args[0]) {\n"
						+ "      case \"one\": break;\n"
						+ "      default: break;\n"
						+ "    }\n"
						+ "    switch (Colour.RED) {\n"
						+ "      case RED: break;\n"
						+ "      default: break;\n"
						+ "    }\n"
						+ "    System.out.println(new Point(1, 2) + list.toString());\n"
						+ "  }\n"
						+ "  static void helper() {}\n"
						+ "}\n");

		assertTrue(MainClassFinder.findMainClasses(classes).contains("Busy"),
				"the main of a class doing ordinary things was lost");
	}

	/** Something that is not a class file is not a class without a main: it is not read. */
	@Test
	void aFileThatIsNotAClassIsPassedOver() throws IOException {
		InputStream notAClass = new ByteArrayInputStream("this is not a class file".getBytes(StandardCharsets.UTF_8));

		assertNull(MainClassFinder.classNameIfMatches(notAClass, m -> true));
	}

	/**
	 * The one thing the reader cannot do is guess. A constant pool entry it does
	 * not know has a length it does not know, so it stops rather than read the
	 * rest as rubbish - and says which kind it was, which is what tells whoever
	 * reads the report what to add.
	 */
	@Test
	void aConstantItDoesNotKnowStopsItRatherThanMisleadIt() throws IOException {
		byte[] classFile = classFileWithConstantPoolTag(99);

		IOException e = assertThrows(IOException.class,
				() -> MainClassFinder.classNameIfMatches(new ByteArrayInputStream(classFile), m -> true));

		assertTrue(e.getMessage().contains("99"), e.getMessage());
	}

	/** The first bytes of a class file, with one constant pool entry of the given kind. */
	private static byte[] classFileWithConstantPoolTag(int tag) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		DataOutputStream out = new DataOutputStream(bytes);
		out.writeInt(0xCAFEBABE);
		out.writeShort(0); // minor
		out.writeShort(65); // major
		out.writeShort(2); // one entry, counted from 1
		out.writeByte(tag);
		out.flush();
		return bytes.toByteArray();
	}
}
