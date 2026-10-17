package com.mygoals.ui;

/** Values only; file contents and mutable engine state never cross to Swing. */
public final class BackupResult
{
	public final String error, message, restoreId;
	public final int goals;
	public final long periods, createdAt;

	private BackupResult(String error, String message, String restoreId, int goals, long periods, long createdAt)
	{
		this.error = error;
		this.message = message;
		this.restoreId = restoreId;
		this.goals = goals;
		this.periods = periods;
		this.createdAt = createdAt;
	}

	public static BackupResult success(String message)
	{
		return new BackupResult("", message, null, 0, 0, 0);
	}

	public static BackupResult failure(String message)
	{
		return new BackupResult(message, "", null, 0, 0, 0);
	}

	public static BackupResult preview(String id, int goals, long periods, long createdAt)
	{
		return new BackupResult("", "", id, goals, periods, createdAt);
	}
}
