package com.mygoals.persistence;

import com.mygoals.domain.ProfileState;
import java.nio.file.Files;
import java.nio.file.Path;
import net.runelite.client.util.Filepath;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class ProfileStateRepositoryTest
{
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final String key = "a".repeat(64);
    private final StateCodec codec = new StateCodec(net.runelite.http.api.RuneLiteAPI.GSON);
    private Filepath root() { return Filepath.Unchecked.getRooted(temporary.getRoot().toPath()); }
    private Path saved(String file) { return temporary.getRoot().toPath().resolve(key).resolve("standard").resolve(file); }
    private String snapshot(long sequence, long xp)
    {
        ProfileState state = new ProfileState(key);
        state.saveSequence = sequence;
        state.xp.put("MINING", xp);
        return codec.encode(state);
    }
    @Test
    public void restartRestoresCheckpointAndOlderWritesCannotReplaceNewerSnapshot() throws Exception
    {
        try (ProfileStateRepository repository = new ProfileStateRepository(net.runelite.http.api.RuneLiteAPI.GSON, root(), key))
        {
            repository.load();
            repository.save(snapshot(2, 700));
            repository.save(snapshot(1, 400));
        }
        try (ProfileStateRepository restarted = new ProfileStateRepository(net.runelite.http.api.RuneLiteAPI.GSON, root(), key))
        {
            assertEquals(Long.valueOf(700), restarted.load().state.xp.get("MINING"));
        }
    }
    @Test
    public void corruptPrimaryRecoversBackupWithoutPoisoningItOnNextSave() throws Exception
    {
        try (ProfileStateRepository repository = new ProfileStateRepository(net.runelite.http.api.RuneLiteAPI.GSON, root(), key))
        {
            repository.load();
            repository.save(snapshot(1, 400));
            repository.save(snapshot(2, 700));
        }
        Files.writeString(saved("state.json"), "{partial write");
        try (ProfileStateRepository recovered = new ProfileStateRepository(net.runelite.http.api.RuneLiteAPI.GSON, root(), key))
        {
            ProfileStateRepository.Loaded load = recovered.load();
            assertTrue(load.writable);
            assertEquals(Long.valueOf(400), load.state.xp.get("MINING"));
            recovered.save(snapshot(3, 900));
            assertEquals(Long.valueOf(400), codec.decode(Files.readString(saved("state.backup.json")), key).xp.get("MINING"));
        }
        try (var paths = Files.list(saved("state.json").getParent()))
        {
            assertTrue(paths.anyMatch(p -> p.getFileName().toString().startsWith("state.invalid-")));
        }
    }
    @Test
    public void futureSchemaIsPreservedEvenWhenAnOlderBackupExists() throws Exception
    {
        try (ProfileStateRepository repository = new ProfileStateRepository(net.runelite.http.api.RuneLiteAPI.GSON, root(), key))
        {
            repository.load(); repository.save(snapshot(1, 400)); repository.save(snapshot(2, 700));
        }
        Files.writeString(saved("state.json"), "{\"schemaVersion\":99}");
        try (ProfileStateRepository future = new ProfileStateRepository(net.runelite.http.api.RuneLiteAPI.GSON, root(), key))
        {
            assertFalse(future.load().writable);
            try { future.save(snapshot(3, 900)); fail(); } catch (java.io.IOException expected) { }
        }
        assertEquals("{\"schemaVersion\":99}", Files.readString(saved("state.json")));
    }
    @Test
    public void simultaneousClientsCannotOwnTheSameProfileSave() throws Exception
    {
        try (ProfileStateRepository first = new ProfileStateRepository(net.runelite.http.api.RuneLiteAPI.GSON, root(), key);
             ProfileStateRepository second = new ProfileStateRepository(net.runelite.http.api.RuneLiteAPI.GSON, root(), key))
        {
            assertTrue(first.load().writable);
            assertFalse(second.load().writable);
        }
    }
    @Test(expected = IllegalArgumentException.class)
    public void profileKeysCannotEscapeThePluginDirectory()
    {
        new ProfileStateRepository(net.runelite.http.api.RuneLiteAPI.GSON, root(), "../someone-else");
    }

    @Test public void savesAndBackupsPreserveTheInjectedClientJsonAdapters() throws Exception
    {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        com.google.gson.JsonSerializer<ProfileState> adapter = (state, type, context) -> {
            calls.incrementAndGet();
            var json = net.runelite.http.api.RuneLiteAPI.GSON.toJsonTree(state).getAsJsonObject();
            json.addProperty("clientAdapterApplied", true); return json;
        };
        var clientGson = net.runelite.http.api.RuneLiteAPI.GSON.newBuilder().registerTypeAdapter(ProfileState.class, adapter).create();
        StateCodec codec = new StateCodec(clientGson); ProfileState state = new ProfileState(key); state.saveSequence = 1;
        assertTrue(codec.encode(state).contains("clientAdapterApplied"));
        assertEquals(1, calls.get());
        try (ProfileStateRepository repository = new ProfileStateRepository(clientGson, root(), key))
        {
            repository.load(); repository.save(state, java.util.Map.of());
            assertTrue(Files.readString(saved("state.json")).contains("clientAdapterApplied"));
            assertEquals(2, calls.get());
            Filepath backup = root().join("injected.zip");
            BackupArchive archive = new BackupArchive(clientGson);
            archive.export(backup, root().join("scratch"), state, java.util.Map.of(), repository::historyFile, key, 1, () -> false, true);
            assertEquals(3, calls.get());
            try (var prepared = archive.prepare(backup, root().join("restore"), key, key))
            { assertEquals(1, prepared.state.saveSequence); }
        }
    }
}
