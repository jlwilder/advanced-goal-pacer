package com.mygoals.maintenance;

import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ReleasePackageCheckTest
{
	@Test
	public void ordinaryExceptionsAreNotRestrictedRuntimeAccess()
	{
		assertFalse(ReleasePackageCheck.restrictedReference("java/lang/RuntimeException"));
		assertFalse(ReleasePackageCheck.restrictedReference("Ljava/lang/RuntimeException;"));
	}

	@Test
	public void restrictedClassesAndDescriptorsAreDetected()
	{
		assertTrue(ReleasePackageCheck.restrictedReference("java/lang/Runtime\u0001"));
		assertTrue(ReleasePackageCheck.restrictedReference("Ljava/lang/Runtime;"));
		assertTrue(ReleasePackageCheck.restrictedReference("java/lang/Runtime$Version"));
		assertTrue(ReleasePackageCheck.restrictedReference("java/lang/reflect/Method"));
		assertTrue(ReleasePackageCheck.restrictedReference("net/runelite/client/util/Filepath$Unchecked"));
	}
}
