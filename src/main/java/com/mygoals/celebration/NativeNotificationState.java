package com.mygoals.celebration;

public final class NativeNotificationState
{
	private NativeNotificationState()
	{
	}

	public static boolean blocks(int stage, boolean expandedWidget, boolean achievementModal, boolean recentStart)
	{
		// Client scripts use 1..5 for opening, holding and closing; 0 and 6 are
		// idle/end.
		boolean activeStage = stage >= 1 && stage <= 5;
		boolean unknownStage = stage < 0 || stage > 6;
		return activeStage || unknownStage || expandedWidget || achievementModal || recentStart;
	}
}
