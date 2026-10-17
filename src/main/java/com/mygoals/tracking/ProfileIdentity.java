package com.mygoals.tracking;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class ProfileIdentity
{
	private ProfileIdentity()
	{
	}

	/**
	 * Stable across RuneLite installations; contains neither a name nor raw account
	 * hash.
	 */
	public static String backupKey(long accountHash)
	{
		if (accountHash == -1)
		{
			throw new IllegalArgumentException("Wait for the active character");
		}
		return digest("mygoals-backup-v1\n" + accountHash + "\nstandard");
	}

	public static String key(String runeScapeProfileKey, long accountHash)
	{
		if (runeScapeProfileKey == null || runeScapeProfileKey.isBlank() || accountHash == -1)
		{
			throw new IllegalArgumentException("Wait for RuneLite's RuneScape profile");
		}
		return digest("mygoals-v1\n" + runeScapeProfileKey + "\n" + accountHash + "\nstandard");
	}

	private static String digest(String value)
	{
		try
		{
			byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder(64);
			for (byte octet : bytes)
			{
				hex.append(String.format(java.util.Locale.ROOT, "%02x", octet & 255));
			}
			return hex.toString();
		}
		catch (NoSuchAlgorithmException impossible)
		{
			throw new IllegalStateException(impossible);
		}
	}
}
