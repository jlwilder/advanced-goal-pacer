package com.mygoals.persistence;

import com.google.gson.Gson;
import com.mygoals.domain.ProfileState;
import com.mygoals.domain.GoalPurge;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import net.runelite.client.util.Filepath;

/**
 * Explicit, account-bound ZIP transfers. Encoding and file access stay off the
 * game and Swing threads.
 */
public final class BackupArchive
{
	// Input validation limits are transfer guards, not a history retention policy.
	static final long MAX_ENTRY_BYTES = 64L * 1024 * 1024;
	static final long MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024;
	private static final int MAX_ENTRIES = 100_000;
	private final Gson gson;
	private final StateCodec codec;

	public BackupArchive(Gson clientGson)
	{
		gson = clientGson.newBuilder().serializeNulls().setPrettyPrinting().create();
		codec = new StateCodec(clientGson);
	}

	public interface History
	{
		Filepath file(String name);
	}

	/**
	 * Rewrites only plugin-owned automatic copies; exported user files are never
	 * touched.
	 */
	public void purgeManaged(Filepath file, Set<String> ids) throws IOException
	{
		Filepath temp = file.getParent().createTempFile("purge-", ".zip");
		boolean changed = false;
		try
		{
			Manifest original = null, updated = new Manifest();
			Map<String, String> originalDigests = new LinkedHashMap<>();
			try (ZipInputStream input = new ZipInputStream(file.openInputStream());
					ZipOutputStream output = new ZipOutputStream(temp.openOutputStream(StandardOpenOption.TRUNCATE_EXISTING)))
			{
				ZipEntry entry;
				Set<String> seen = new HashSet<>();
				long bytesRead = 0;
				while ((entry = input.getNextEntry()) != null)
				{
					String name = entry.getName();
					if (!validEntry(name) || !seen.add(name) || seen.size() > MAX_ENTRIES)
					{
						throw new IOException("Invalid automatic backup");
					}
					byte[] data = readEntry(input, Math.min(MAX_ENTRY_BYTES, MAX_TOTAL_BYTES - bytesRead));
					bytesRead += data.length;
					if (name.equals("manifest.json"))
					{
						original = gson.fromJson(new String(data, StandardCharsets.UTF_8), Manifest.class);
						continue;
					}
					originalDigests.put(name, digest(data));
					var tree = new com.google.gson.JsonParser().parse(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
					ProfileState state = codec.decode(tree.toString(), tree.get("profileKey").getAsString());
					if (GoalPurge.strip(state, ids))
					{
						changed = true;
						data = codec.encode(state).getBytes(StandardCharsets.UTF_8);
					}
					put(output, name, data, updated, () -> false);
				}
				if (original == null || !"my-goals-backup".equals(original.format) || original.version != 1
						|| !originalDigests.equals(original.files))
				{
					throw new IOException("Cannot purge a damaged automatic backup; files preserved");
				}
				original.files = updated.files;
				output.putNextEntry(new ZipEntry("manifest.json"));
				output.write(gson.toJson(original).getBytes(StandardCharsets.UTF_8));
				output.closeEntry();
			}
			if (changed)
			{
				try (var channel = temp.openFileChannel(StandardOpenOption.WRITE))
				{
					channel.force(true);
				}
				try
				{
					temp.moveTo(file, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				}
				catch (java.nio.file.AtomicMoveNotSupportedException ex)
				{
					temp.moveTo(file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				}
			}
		}
		finally
		{
			temp.deleteIfExists();
		}
	}

	private static final class Manifest
	{
		String format = "my-goals-backup";
		int version = 1;
		String account, profile, scope = "standard";
		long createdAt;
		transient long totalBytes;
		Map<String, String> files = new LinkedHashMap<>();
	}

	public static final class Prepared implements AutoCloseable
	{
		public final String id = UUID.randomUUID().toString();
		public final ProfileState state;
		public final long createdAt, periods;
		public final Filepath staging;
		private final String sourceProfile;
		private final Map<String, String> digests;
		private boolean closed;

		Prepared(ProfileState state, long createdAt, long periods, Filepath staging, String sourceProfile, Map<String, String> digests)
		{
			this.state = state;
			this.createdAt = createdAt;
			this.periods = periods;
			this.staging = staging;
			this.sourceProfile = sourceProfile;
			this.digests = Map.copyOf(digests);
		}

		public Filepath history(String name)
		{
			return staging.join("history").joinSegment(name);
		}

		@Override
		public void close() throws IOException
		{
			if (!closed)
			{
				staging.deleteRecursively();
				closed = true;
			}
		}
	}

	/** Write privately first. Publishing never replaces an existing user backup. */
	public void export(Filepath destination, Filepath scratch, ProfileState state, Map<String, ProfileState> pending,
			History history, String account, long now, BooleanSupplier cancelled, boolean validateHistory) throws IOException
	{
		identity(account);
		scratch.createDirectories();
		if (state.historyFiles.size() > MAX_ENTRIES - 2)
		{
			throw new IOException("The backup has too many files to transfer safely.");
		}
		if (destination.exists())
		{
			throw new IOException("A file already exists there. Choose a new backup filename.");
		}
		Filepath temp = scratch.createTempFile("export-", ".zip");
		try
		{
			Manifest manifest = new Manifest();
			manifest.account = account;
			manifest.profile = state.profileKey;
			manifest.createdAt = now;
			try (ZipOutputStream zip = new ZipOutputStream(temp.openOutputStream(StandardOpenOption.TRUNCATE_EXISTING)))
			{
				put(zip, "state.json", codec.encode(state).getBytes(StandardCharsets.UTF_8), manifest, cancelled);
				for (String name : state.historyFiles)
				{
					checkCancelled(cancelled);
					byte[] data = pending.containsKey(name) ? codec.encode(pending.get(name)).getBytes(StandardCharsets.UTF_8)
							: read(history.file(name).openInputStream(), MAX_ENTRY_BYTES);
					if (validateHistory)
					{
						codec.decode(new String(data, StandardCharsets.UTF_8), state.profileKey);
					}
					put(zip, "history/" + name, data, manifest, cancelled);
				}
				byte[] data = gson.toJson(manifest).getBytes(StandardCharsets.UTF_8);
				if (manifest.totalBytes + data.length > MAX_TOTAL_BYTES || data.length > MAX_ENTRY_BYTES)
				{
					throw new IOException("The backup exceeds safe transfer limits.");
				}
				zip.putNextEntry(new ZipEntry("manifest.json"));
				zip.write(data);
				zip.closeEntry();
			}
			try (var channel = temp.openFileChannel(StandardOpenOption.WRITE))
			{
				channel.force(true);
			}
			checkCancelled(cancelled);
			temp.copyTo(destination); // CREATE_NEW semantics keep an existing backup intact on collision.
			try (var channel = destination.openFileChannel(StandardOpenOption.WRITE))
			{
				channel.force(true);
			}
		}
		finally
		{
			temp.deleteIfExists();
		}
	}

	/**
	 * Untrusted ZIP paths never become extraction paths; only this fixed format is
	 * accepted.
	 */
	public Prepared prepare(Filepath source, Filepath scratch, String account, String targetProfile) throws IOException
	{
		return prepare(source, scratch, account, targetProfile, () -> false);
	}

	public Prepared prepare(Filepath source, Filepath scratch, String account, String targetProfile, BooleanSupplier cancelled)
			throws IOException
	{
		identity(account);
		identity(targetProfile);
		scratch.createDirectories();
		Filepath staging = scratch.createTempDir("restore-").rooted();
		boolean complete = false;
		try
		{
			Map<String, String> digests = new LinkedHashMap<>();
			Manifest manifest = null;
			Set<String> seen = new HashSet<>();
			long total = 0;
			try (ZipInputStream zip = new ZipInputStream(source.openInputStream()))
			{
				ZipEntry entry;
				while ((entry = zip.getNextEntry()) != null)
				{
					checkCancelled(cancelled);
					String name = entry.getName();
					if (entry.isDirectory() || !validEntry(name) || !seen.add(name) || seen.size() > MAX_ENTRIES)
					{
						throw new IOException("This is not a valid Advanced Goal Pacer backup.");
					}
					byte[] bytes = readEntry(zip, Math.min(MAX_ENTRY_BYTES, MAX_TOTAL_BYTES - total), cancelled);
					total += bytes.length;
					if (name.equals("manifest.json"))
					{
						var document = new com.google.gson.JsonParser().parse(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
						for (String required : new String[] { "format", "version", "account", "profile", "scope", "createdAt", "files" })
						{
							if (!document.has(required) || document.get(required).isJsonNull())
							{
								throw new IOException("The backup manifest is incomplete.");
							}
						}
						manifest = gson.fromJson(document, Manifest.class);
					}
					else
					{
						Filepath file = name.equals("state.json") ? staging.join("state.json")
								: staging.join("history").joinSegment(name.substring(8));
						if (name.startsWith("history/"))
						{
							staging.join("history").createDirectories();
						}
						file.write(bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
						digests.put(name, digest(bytes));
					}
					zip.closeEntry();
				}
			}
			if (manifest == null || !"my-goals-backup".equals(manifest.format) || manifest.version != 1 ||
					!"standard".equals(manifest.scope) || manifest.createdAt < 0 || manifest.files == null
					|| !manifest.files.equals(digests))
			{
				throw new IOException("The backup is incomplete, damaged, or uses a newer backup format.");
			}
			if (!account.equals(manifest.account))
			{
				throw new IOException("This backup belongs to a different RuneScape character.");
			}
			identity(manifest.profile);
			ProfileState state = codec.decode(
					new String(read(staging.join("state.json").openInputStream(), MAX_ENTRY_BYTES), StandardCharsets.UTF_8),
					manifest.profile);
			Set<String> expected = new HashSet<>();
			expected.add("state.json");
			state.historyFiles.forEach(n -> expected.add("history/" + n));
			if (!expected.equals(digests.keySet()))
			{
				throw new IOException("The backup does not contain its complete history.");
			}
			long periods = state.goals.values().stream().mapToLong(g -> g.history.size() + (g.current == null ? 0 : 1)).sum();
			for (String name : state.historyFiles)
			{
				ProfileState fragment = codec
						.decode(new String(read(staging.join("history").joinSegment(name).openInputStream(), MAX_ENTRY_BYTES),
								StandardCharsets.UTF_8), manifest.profile);
				if (!fragment.historyFiles.isEmpty())
				{
					throw new IOException("Invalid nested history references.");
				}
				periods = Math.addExact(periods,
						fragment.goals.values().stream().mapToLong(g -> g.history.size() + (g.current == null ? 0 : 1)).sum());
			}
			// Account proof is stable across computers; local storage keys can safely be
			// rebound.
			state.profileKey = targetProfile;
			new com.mygoals.engine.GoalEngine(StateSnapshot.copy(state), java.time.ZoneId.systemDefault()).tick(java.time.Instant.now());
			complete = true;
			return new Prepared(state, manifest.createdAt, periods, staging, manifest.profile, digests);
		}
		catch (RuntimeException ex)
		{
			throw new IOException(ex.getMessage() == null ? "The backup could not be validated." : ex.getMessage(), ex);
		}
		finally
		{
			if (!complete)
			{
				staging.deleteRecursively();
			}
		}
	}

	public ProfileState readHistory(Prepared prepared, String name, String targetProfile) throws IOException
	{
		// Each fragment was already validated against its source profile during
		// preparation.
		byte[] data = read(prepared.history(name).openInputStream(), MAX_ENTRY_BYTES);
		if (!digest(data).equals(prepared.digests.get("history/" + name)))
		{
			throw new IOException("The staged backup changed. Select the backup again.");
		}
		ProfileState fragment = codec.decode(new String(data, StandardCharsets.UTF_8), prepared.sourceProfile);
		fragment.profileKey = targetProfile;
		return fragment;
	}

	private static boolean validEntry(String name)
	{
		return name.equals("manifest.json") || name.equals("state.json")
				|| (name.startsWith("history/") && HistoryArchive.validName(name.substring(8)));
	}

	private static void put(ZipOutputStream zip, String name, byte[] bytes, Manifest manifest, BooleanSupplier cancelled) throws IOException
	{
		checkCancelled(cancelled);
		if (bytes.length > MAX_ENTRY_BYTES)
		{
			throw new IOException("A backup entry is too large to transfer safely.");
		}
		manifest.totalBytes = Math.addExact(manifest.totalBytes, bytes.length);
		if (manifest.totalBytes > MAX_TOTAL_BYTES)
		{
			throw new IOException("The backup exceeds safe transfer limits.");
		}
		zip.putNextEntry(new ZipEntry(name));
		zip.write(bytes);
		zip.closeEntry();
		manifest.files.put(name, digest(bytes));
	}

	private static byte[] read(InputStream input, long limit) throws IOException
	{
		try (InputStream close = input)
		{
			return readEntry(close, limit);
		}
	}

	private static byte[] readEntry(InputStream input, long limit) throws IOException
	{
		return readEntry(input, limit, () -> false);
	}

	private static byte[] readEntry(InputStream input, long limit, BooleanSupplier cancelled) throws IOException
	{
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		byte[] buffer = new byte[8192];
		long total = 0;
		int length;
		while ((length = input.read(buffer)) != -1)
		{
			checkCancelled(cancelled);
			total += length;
			if (total > limit)
			{
				throw new IOException("The backup exceeds safe transfer limits.");
			}
			bytes.write(buffer, 0, length);
		}
		return bytes.toByteArray();
	}

	private static String digest(byte[] data)
	{
		try
		{
			byte[] hash = MessageDigest.getInstance("SHA-256").digest(data);
			StringBuilder text = new StringBuilder(64);
			for (byte value : hash)
			{
				text.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
			}
			return text.toString();
		}
		catch (NoSuchAlgorithmException impossible)
		{
			throw new IllegalStateException(impossible);
		}
	}

	private static void identity(String key)
	{
		if (key == null || !key.matches("[a-f0-9]{64}"))
		{
			throw new IllegalArgumentException("The active character is not ready.");
		}
	}

	private static void checkCancelled(BooleanSupplier cancelled) throws IOException
	{
		if (cancelled.getAsBoolean())
		{
			throw new IOException("The character changed or the restore was cancelled. Try again after login.");
		}
	}
}
