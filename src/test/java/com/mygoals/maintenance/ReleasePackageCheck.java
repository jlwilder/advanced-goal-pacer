package com.mygoals.maintenance;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;

/** Release preflight for the actual JAR; never distributed with the plugin. */
public final class ReleasePackageCheck
{
	public static void main(String[] args) throws Exception
	{
		Path root = Path.of(args[1]);
		Properties metadata = new Properties();
		try (InputStream input = Files.newInputStream(root.resolve("runelite-plugin.properties")))
		{
			metadata.load(input);
		}
		require("Advanced Goal Pacer".equals(metadata.getProperty("displayName")), "Unexpected display name");
		require("com.mygoals.MyGoalsPlugin".equals(metadata.getProperty("plugins")), "Only the production plugin may be listed");
		require("standard".equals(metadata.getProperty("build")), "The Hub standard build is required");
		require(args[2].equals(metadata.getProperty("version")) && args[2].matches("\\d+\\.\\d+\\.\\d+"), "Use matching release versions");
		for (String key : Set.of("author", "description", "tags"))
		{
			require(!metadata.getProperty(key, "").isBlank(), "Missing metadata: " + key);
		}

		byte[] icon = Files.readAllBytes(root.resolve("icon.png"));
		var image = ImageIO.read(root.resolve("icon.png").toFile());
		require(image != null && image.getWidth() <= 48 && image.getHeight() <= 72, "Hub icon exceeds 48x72");
		int classes = 0;
		try (ZipFile jar = new ZipFile(args[0]))
		{
			require(jar.getEntry("com/mygoals/MyGoalsPlugin.class") != null, "Production plugin is missing");
			require(jar.getEntry("META-INF/LICENSE") != null, "License must survive the Hub's standard build");
			require(Arrays.equals(icon, jar.getInputStream(jar.getEntry("com/mygoals/icon.png")).readAllBytes()),
					"Hub and sidebar icons differ");
			var entries = jar.entries();
			while (entries.hasMoreElements())
			{
				var entry = entries.nextElement();
				String name = entry.getName();
				require(!name.matches("(?i).*(?:/m0/|Probe|Preview|Fixture|Test\\.class|credentials|session\\.json).*"),
						"Development/private file in JAR: " + name);
				if (!name.endsWith(".class"))
				{
					continue;
				}
				classes++;
				require(name.startsWith("com/mygoals/"), "Unexpected bundled dependency: " + name);
				byte[] bytes = jar.getInputStream(entry).readAllBytes();
				require(((bytes[6] & 255) << 8 | bytes[7] & 255) == 55, "Class is not Java 11: " + name);
				String pool = new String(bytes, StandardCharsets.ISO_8859_1);
				require(!restrictedReference(pool), "Restricted runtime API in " + name);
			}
		}
		Pattern forbiddenSource = Pattern.compile(
				"new\\s+(?:Gson|GsonBuilder|OkHttpClient|ProcessBuilder)\\s*\\(|Thread\\.(?:sleep|interrupt)\\s*\\(|Filepath\\.Unchecked|\\.menuAction\\s*\\(|\\.runScript\\s*\\(");
		try (var sources = Files.walk(root.resolve("src/main/java")))
		{
			for (Path source : sources.filter(p -> p.toString().endsWith(".java")).collect(java.util.stream.Collectors.toList()))
			{
				require(!forbiddenSource.matcher(Files.readString(source)).find(),
						"Restricted runtime source pattern in " + source.getFileName());
			}
		}
		System.out.println("Release package verified: " + classes
				+ " Java 11 classes, standard-build metadata, matching icons, license, and no development/private payloads.");
	}

	static boolean restrictedReference(String pool)
	{
		for (String forbidden : Set.of("java/lang/reflect/", "java/lang/Runtime", "java/lang/ProcessBuilder", "java/awt/Robot",
				"java/awt/KeyboardFocusManager", "java/nio/file/Files", "java/io/FileInputStream", "java/io/FileOutputStream",
				"Filepath$Unchecked"))
		{
			// Match complete class names (and nested classes), not RuntimeException.
			String suffix = forbidden.endsWith("/") ? "" : "(?![A-Za-z0-9_])";
			if (Pattern.compile(Pattern.quote(forbidden) + suffix).matcher(pool).find())
			{
				return true;
			}
		}
		return false;
	}

	private static void require(boolean condition, String message)
	{
		if (!condition)
		{
			throw new IllegalStateException(message);
		}
	}
}
