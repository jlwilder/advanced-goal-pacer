package com.mygoals;

import com.mygoals.domain.GoalSpec;
import com.mygoals.domain.ProfileState;
import com.mygoals.engine.GoalEngine;
import com.mygoals.persistence.ProfileStateRepository;
import com.mygoals.persistence.StateCodec;
import com.mygoals.ui.GoalsView;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import net.runelite.client.util.Filepath;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class HistoryOperationsTest
{
	@Rule
	public TemporaryFolder temporary = new TemporaryFolder();
	private final String key = "a".repeat(64);
	private final ZoneId zone = ZoneId.of("America/Chicago");
	private final LocalDate first = LocalDate.parse("2016-01-01"), today = LocalDate.parse("2026-10-17");
	private final ArrayDeque<Runnable> io = new ArrayDeque<>(), callbacks = new ArrayDeque<>();
	private final List<String> notices = new ArrayList<>();
	private final HistoryOperations history = new HistoryOperations(io::add, callbacks::add, notices::add);

	private ProfileState seed(ProfileStateRepository repository) throws Exception
	{
		var start = first.atTime(12, 0).atZone(zone).toInstant();
		GoalEngine engine = new GoalEngine(new ProfileState(key), zone);
		engine.observe(Map.of("MINING", 1000L), GoalEngine.LIVE, start);
		engine.add(new GoalSpec("Archived goal", null, "MINING", GoalSpec.Mode.GAIN, 100, 0, GoalSpec.Cadence.ONCE, first,
				first.plusDays(2), zone), start);
		engine.observe(Map.of("MINING", 1200L), GoalEngine.LIVE, start.plusSeconds(1));
		engine.state().saveSequence = 1;
		repository.load();
		repository.save(new StateCodec(net.runelite.http.api.RuneLiteAPI.GSON).encode(engine.state()));
		return repository.loadAndArchive(today).state;
	}

	@Test
	public void profileResetDropsOldCompletedReadsAndAllowsTheNewSessionToLoad() throws Exception
	{
		try (var repository = new ProfileStateRepository(net.runelite.http.api.RuneLiteAPI.GSON,
				Filepath.Unchecked.getRooted(temporary.getRoot().toPath()), key))
		{
			ProfileState state = seed(repository);
			history.attach("old", state, repository);
			history.finished("old", 0);
			history.finished("old", 0);
			assertEquals(1, io.size());
			history.attach("new", state, repository);
			io.remove().run();
			callbacks.remove().run();
			assertTrue(history.finishedGoals().isEmpty());
			assertTrue(notices.isEmpty());
			history.finished("new", 0);
			io.remove().run();
			callbacks.remove().run();
			assertEquals(1, history.finishedGoals().size());
			assertEquals(List.of(""), notices);
		}
	}

	@Test
	public void deletedGoalCannotReceiveAnOlderQueuedChartAndFailedReadsCanBeRetried() throws Exception
	{
		try (var repository = new ProfileStateRepository(net.runelite.http.api.RuneLiteAPI.GSON,
				Filepath.Unchecked.getRooted(temporary.getRoot().toPath()), key))
		{
			ProfileState state = seed(repository);
			String id = state.finished.keySet().iterator().next();
			history.attach("session", state, repository);
			AtomicReference<GoalsView.HistoryPage> reply = new AtomicReference<>();
			history.daily("session", id, "invalid", first.plusDays(1).toString(), reply::set);
			io.remove().run();
			callbacks.remove().run();
			assertFalse(reply.get().error.isEmpty());
			history.daily("session", id, first.toString(), first.plusDays(1).toString(), reply::set);
			io.remove().run();
			callbacks.remove().run();
			assertEquals(1, reply.get().days.size());
			reply.set(null);
			history.daily("session", id, first.toString(), first.plusDays(1).toString(), reply::set);
			io.remove().run();
			new GoalEngine(state, zone).remove(id);
			callbacks.remove().run();
			assertNull(reply.get());
		}
	}
}
