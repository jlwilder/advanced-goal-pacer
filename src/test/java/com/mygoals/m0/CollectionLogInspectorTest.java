package com.mygoals.m0;

import org.junit.Test;
import static org.junit.Assert.*;

public class CollectionLogInspectorTest
{
    @Test
    public void nativePlaceholderOneIsNotAnObtainedItem()
    {
        assertEquals(0, CollectionLogInspector.decodeQuantity(175, 1));
        assertEquals(1, CollectionLogInspector.decodeQuantity(0, 1));
        assertEquals(6, CollectionLogInspector.decodeQuantity(0, 6));
    }

    @Test(expected = IllegalStateException.class)
    public void unknownOpacityCannotInventAQuantity()
    {
        CollectionLogInspector.decodeQuantity(100, 6);
    }
}
