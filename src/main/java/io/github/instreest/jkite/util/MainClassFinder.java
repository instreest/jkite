package io.github.instreest.jkite.util;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Finds the class to run in what a script compiled to, for a script that does
 * not name one with <code>//MAIN</code>.
 *
 * The class files are read here - the constant pool and the method table, no
 * further - rather than with a bytecode library, which is what JBang uses
 * jandex for. That keeps a megabyte out of jkite.jar, and puts the reading of
 * the class file format in this project's care: a JDK that writes a constant
 * this does not know is a thing to notice, so it stops there rather than read
 * on into rubbish.
 */
public final class MainClassFinder {
	public static final String DESC_MAIN = "([Ljava/lang/String;)V";
	public static final String DESC_NO_ARGS = "()V";

	/** A method as found in a class file, by the two things that name it. */
	public static final class Method {
		public final String name;
		public final String descriptor;

		Method(String name, String descriptor) {
			this.name = name;
			this.descriptor = descriptor;
		}
	}

	private MainClassFinder() {
	}

	/**
	 * Fully qualified names of all classes under dir with a main method, both
	 * the classic <code>public static void main(String[])</code> and the
	 * instance <code>void main()</code> of JEP 512.
	 */
	public static List<String> findMainClasses(Path dir) throws IOException {
		return scan(dir, m -> ("main".equals(m.name)
				&& (DESC_MAIN.equals(m.descriptor) || DESC_NO_ARGS.equals(m.descriptor))));
	}

	private static List<String> scan(Path dir, java.util.function.Predicate<Method> wanted) throws IOException {
		try (Stream<Path> paths = Files.walk(dir)) {
			List<Path> files = paths
				.filter(Files::isRegularFile)
				.filter(f -> f.getFileName().toString().endsWith(".class"))
				.filter(f -> !f.getFileName().toString().contains("$"))
				.sorted()
				.collect(Collectors.toList());
			List<String> found = new ArrayList<>();
			for (Path f : files) {
				try (InputStream is = Files.newInputStream(f)) {
					String name = classNameIfMatches(is, wanted);
					if (name != null) {
						found.add(name);
					}
				}
			}
			return found;
		}
	}

	/**
	 * Returns the class name if the class file declares a method the predicate
	 * accepts, otherwise null.
	 */
	static String classNameIfMatches(InputStream is, java.util.function.Predicate<Method> wanted)
			throws IOException {
		DataInputStream in = new DataInputStream(is);
		if (in.readInt() != 0xCAFEBABE) {
			return null;
		}
		in.readUnsignedShort(); // minor
		in.readUnsignedShort(); // major
		int cpCount = in.readUnsignedShort();
		String[] utf8 = new String[cpCount];
		int[] classNameIdx = new int[cpCount];
		for (int i = 1; i < cpCount; i++) {
			int tag = in.readUnsignedByte();
			switch (tag) {
			case 1: // Utf8
				utf8[i] = in.readUTF();
				break;
			case 7: // Class
				classNameIdx[i] = in.readUnsignedShort();
				break;
			case 8: // String
			case 16: // MethodType
			case 19: // Module
			case 20: // Package
				in.readUnsignedShort();
				break;
			case 15: // MethodHandle
				in.readUnsignedByte();
				in.readUnsignedShort();
				break;
			case 3: // Integer
			case 4: // Float
			case 9: // Fieldref
			case 10: // Methodref
			case 11: // InterfaceMethodref
			case 12: // NameAndType
			case 17: // Dynamic
			case 18: // InvokeDynamic
				in.readInt();
				break;
			case 5: // Long
			case 6: // Double
				in.readLong();
				i++;
				break;
			default:
				throw new IOException("Unknown constant pool tag " + tag);
			}
		}
		in.readUnsignedShort(); // access flags
		int thisClass = in.readUnsignedShort();
		in.readUnsignedShort(); // super
		int ifCount = in.readUnsignedShort();
		for (int i = 0; i < ifCount; i++) {
			in.readUnsignedShort();
		}
		int fieldCount = in.readUnsignedShort();
		for (int i = 0; i < fieldCount; i++) {
			skipMember(in);
		}
		int methodCount = in.readUnsignedShort();
		boolean matches = false;
		for (int i = 0; i < methodCount; i++) {
			in.readUnsignedShort(); // access flags
			String name = utf8[in.readUnsignedShort()];
			String desc = utf8[in.readUnsignedShort()];
			skipAttributes(in);
			if (!matches && wanted.test(new Method(name, desc))) {
				matches = true;
			}
		}
		return matches ? utf8[classNameIdx[thisClass]].replace('/', '.') : null;
	}

	private static void skipMember(DataInputStream in) throws IOException {
		in.readUnsignedShort(); // access
		in.readUnsignedShort(); // name
		in.readUnsignedShort(); // descriptor
		skipAttributes(in);
	}

	private static void skipAttributes(DataInputStream in) throws IOException {
		int count = in.readUnsignedShort();
		for (int i = 0; i < count; i++) {
			in.readUnsignedShort(); // name
			int len = in.readInt();
			in.skipBytes(len);
		}
	}
}
