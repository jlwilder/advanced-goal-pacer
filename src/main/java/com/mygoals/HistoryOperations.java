package com.mygoals;

import com.mygoals.domain.ProfileState;
import com.mygoals.persistence.ProfileStateRepository;
import com.mygoals.ui.GoalsView;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Bounded archive reads. Attach/reset and callbacks belong to the client
 * thread.
 */
final class HistoryOperations
{
	private final Executor io;
	private final MyGoalsController.Dispatcher clientThread;
	private final Consumer<String> changed;
	private final Map<String, ProfileState.Goal> finished = new LinkedHashMap<>();
	private ProfileState state;
	private ProfileStateRepository repository;
	private String token;
	private long generation, revision;
	private int page;
	private boolean loading;

	HistoryOperations(Executor io, MyGoalsController.Dispatcher clientThread, Consumer<String> changed)
	{
		this.io = io;
		this.clientThread = clientThread;
		this.changed = changed;
	}

	void attach(String token, ProfileState state, ProfileStateRepository repository)
	{
		reset();
		this.token = token;
		this.state = state;
		this.repository = repository;
	}

	void reset()
	{
		generation++;
		revision++;
		finished.clear();
		page = 0;
		loading = false;
		state = null;
		repository = null;
		token = null;
	}

	void prune()
	{
		if (state != null && finished.keySet().removeIf(id -> !state.finished.containsKey(id)))
		{
			revision++;
		}
	}

	long revision()
	{
		return revision;
	}

	int page()
	{
		return page;
	}

	List<ProfileState.Goal> finishedGoals()
	{
		return List.copyOf(finished.values());
	}

	private boolean accepts(String capturedToken)
	{
		return state != null && repository != null && Objects.equals(token, capturedToken);
	}

	private boolean hasGoal(String id)
	{
		return state != null && (state.goals.containsKey(id) || state.finished.containsKey(id));
	}

	void finished(String capturedToken, int requestedPage)
	{
		if (!accepts(capturedToken) || loading || requestedPage < 0)
		{
			return;
		}
		int selected = Math.min(requestedPage, Math.max(0, (state.finished.size() - 1) / GoalsView.FINISHED_PAGE_SIZE));
		var refs = state.finished.values().stream()
				.sorted(Comparator.comparing((ProfileState.Finished ref) -> ref.date).reversed().thenComparing(ref -> ref.id))
				.skip((long) selected * GoalsView.FINISHED_PAGE_SIZE).limit(GoalsView.FINISHED_PAGE_SIZE).collect(Collectors.toList());
		ProfileStateRepository target = repository;
		long captured = generation;
		loading = true;
		io.execute(() ->
		{
			List<ProfileState.Goal> loaded = new ArrayList<>();
			String problem = "";
			try
			{
				for (var ref : refs)
				{
					loaded.add(target.finishedGoal(ref));
				}
			}
			catch (Exception ex)
			{
				problem = "Completed history unavailable; retry loading.";
			}
			String error = problem;
			clientThread.invoke(() ->
			{
				if (captured != generation || !accepts(capturedToken))
				{
					return;
				}
				loading = false;
				if (error.isEmpty())
				{
					finished.clear();
					loaded.stream().filter(g -> state.finished.containsKey(g.id)).forEach(g -> finished.put(g.id, g));
					page = selected;
				}
				revision++;
				changed.accept(error);
			});
		});
	}

	void daily(String capturedToken, String goalId, String start, String end, Consumer<GoalsView.HistoryPage> completed)
	{
		read(capturedToken, goalId, repo -> new GoalsView.HistoryPage(List.of(),
				repo.dailyHistory(goalId, LocalDate.parse(start), LocalDate.parse(end)), null, false, ""),
				new GoalsView.HistoryPage(List.of(), null, false, "Daily history unavailable; retry loading."), completed);
	}

	void periods(String capturedToken, String goalId, String before, Consumer<GoalsView.HistoryPage> completed)
	{
		read(capturedToken, goalId, repo ->
		{
			var loaded = repo.history(goalId, before);
			return new GoalsView.HistoryPage(loaded.periods, loaded.before, loaded.more, "");
		}, new GoalsView.HistoryPage(List.of(), before, true, "History unavailable; retry loading."), completed);
	}

	@FunctionalInterface
	private interface Read
	{
		GoalsView.HistoryPage load(ProfileStateRepository repository) throws Exception;
	}

	private void read(String capturedToken, String goalId, Read read, GoalsView.HistoryPage unavailable,
			Consumer<GoalsView.HistoryPage> completed)
	{
		if (!accepts(capturedToken) || !hasGoal(goalId))
		{
			return;
		}
		ProfileStateRepository target = repository;
		long captured = generation;
		io.execute(() ->
		{
			GoalsView.HistoryPage result;
			try
			{
				result = read.load(target);
			}
			catch (Exception ex)
			{
				result = unavailable;
			}
			GoalsView.HistoryPage reply = result;
			clientThread.invoke(() ->
			{
				if (captured == generation && accepts(capturedToken) && hasGoal(goalId))
				{
					completed.accept(reply);
				}
			});
		});
	}
}
