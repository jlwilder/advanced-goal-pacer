package com.mygoals.collection;

import java.util.Arrays;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class CollectionLogReaderTest
{
    @Test public void nativePlaceholderIsZeroAndACompleteOwnPageProvidesExactCounts()
    {
        FakeCollectionClient fake = new FakeCollectionClient(); fake.page(605, Map.of(23956, 4L));
        var snapshot = CollectionLogReader.read(fake.client);
        assertNotNull(snapshot); assertEquals(605, snapshot.pageId);
        assertEquals(Long.valueOf(4), snapshot.quantities.get(23956));
        assertEquals(Long.valueOf(0), snapshot.quantities.get(25859));
        assertThrows(IllegalArgumentException.class, () -> CollectionLogReader.decodeQuantity(50, 1));
    }

    @Test public void foreignHiddenPartialAndMisorderedPagesAreRejected()
    {
        FakeCollectionClient fake = new FakeCollectionClient(); fake.page(605, Map.of());
        fake.foreign = 1; assertNull(CollectionLogReader.read(fake.client));
        fake.foreign = 0; fake.hidden = true; assertNull(CollectionLogReader.read(fake.client));
        fake.hidden = false; fake.children = Arrays.copyOf(fake.children, 4); assertNull(CollectionLogReader.read(fake.client));
        fake.page(605, Map.of()); fake.children[0] = fake.item(23956, 4); assertNull(CollectionLogReader.read(fake.client));
    }

    @Test public void nativeReplacementAndBodyVariantsKeepCanonicalCollectionKeys()
    {
        FakeCollectionClient fake = new FakeCollectionClient(); fake.page(605, Map.of());
        fake.body = 1;
        fake.replacements.put(3721 * 100000 + 23757, 90001);
        fake.replacements.put(2108 * 100000 + 90001, 90002);
        fake.children[0] = fake.item(90002, 2);
        var snapshot = CollectionLogReader.read(fake.client);
        assertNotNull(snapshot); assertEquals(Long.valueOf(2), snapshot.quantities.get(23757));
        assertFalse(snapshot.quantities.containsKey(90002));
    }

    @Test public void missingCacheDefinitionsDisableEveryAffectedBoss()
    {
        assertEquals(UniqueCatalogue.INSTANCE.bosses.keySet(), CollectionLogReader.incompatibleMetrics(new FakeCollectionClient().client));
    }
}
