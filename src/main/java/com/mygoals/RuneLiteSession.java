package com.mygoals;

import com.mygoals.tracking.ProfileIdentity;
import java.util.LinkedHashMap;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.RuneScapeProfileType;
import net.runelite.client.hiscore.HiscoreClient;
import net.runelite.client.hiscore.HiscoreEndpoint;
import com.mygoals.tracking.MetricCatalogue;
import java.util.concurrent.CompletableFuture;
import java.util.Set;
import com.mygoals.collection.CollectionLogReader;

/**
 * All RuneLite reads stay on the client thread, outside the engine and
 * asynchronous storage.
 */
final class RuneLiteSession implements MyGoalsController.Session
{
	private final Client client;
	private final ConfigManager config;
	private final HiscoreClient hiscores;
	private String checkedProfile, opaqueKey;
	private long checkedAccount;
	private Set<String> incompatible;

	RuneLiteSession(Client client, ConfigManager config, HiscoreClient hiscores)
	{
		this.client = client;
		this.config = config;
		this.hiscores = hiscores;
	}

	@Override
	public boolean loggedIn()
	{
		return client.getGameState() == GameState.LOGGED_IN;
	}

	@Override
	public boolean loading()
	{
		return client.getGameState() == GameState.LOADING;
	}

	@Override
	public String displayName()
	{
		String name = client.getLocalPlayer() == null ? null : client.getLocalPlayer().getName();
		return name == null ? "Character" : name;
	}

	@Override
	public String readyKey()
	{
		if (!loggedIn() || client.getLocalPlayer() == null || client.getAccountHash() == -1 ||
				RuneScapeProfileType.getCurrent(client) != RuneScapeProfileType.STANDARD)
		{
			return null;
		}
		String profile = config.getRSProfileKey();
		if (profile == null)
		{
			// A brand-new character may have no RuneLite profile until a plugin creates
			// one.
			// This preference establishes that official identity; goal progress stays in
			// local files.
			config.setRSProfileConfiguration("mygoals", "profileInitialized", true);
			return null;
		}
		if (profile != null && profile.equals(checkedProfile) && client.getAccountHash() == checkedAccount)
		{
			return opaqueKey;
		}
		if (profile == null || config.getRSProfiles().stream().noneMatch(p -> p.getKey().equals(profile) &&
				p.getAccountHash() == client.getAccountHash() && p.getType() == RuneScapeProfileType.STANDARD))
		{
			return null;
		}
		checkedProfile = profile;
		checkedAccount = client.getAccountHash();
		opaqueKey = ProfileIdentity.key(profile, checkedAccount);
		return opaqueKey;
	}

	@Override
	public Map<String, Long> xpSnapshot()
	{
		Map<String, Long> totals = new LinkedHashMap<>();
		for (Skill skill : Skill.values())
		{
			if (skill != Skill.OVERALL)
			{
				totals.put(skill.name(), (long) client.getSkillExperience(skill));
			}
		}
		return totals;
	}

	@Override
	public CompletableFuture<Map<String, Long>> lookupCounts()
	{
		String name = client.getLocalPlayer() == null ? null : client.getLocalPlayer().getName();
		if (name == null || name.isBlank())
		{
			throw new IllegalStateException("Character name unavailable");
		}
		HiscoreEndpoint endpoint = HiscoreEndpoint.fromWorldTypes(client.getWorldType());
		return hiscores.lookupAsync(name, endpoint).thenApply(result ->
		{
			Map<String, Long> totals = new LinkedHashMap<>();
			if (result != null)
			{
				MetricCatalogue.countMetrics().forEach(metric ->
				{
					Long total = MetricCatalogue.activityAmount(result.getSkill(metric));
					if (total != null)
					{
						totals.put(metric.name(), total);
					}
				});
			}
			return totals;
		});
	}

	@Override
	public String countLookupKey()
	{
		String key = readyKey();
		return key == null ? null : key + ":" + HiscoreEndpoint.fromWorldTypes(client.getWorldType()).name();
	}

	@Override
	public String backupIdentity()
	{
		return ProfileIdentity.backupKey(client.getAccountHash());
	}

	@Override
	public Set<String> incompatibleUniqueMetrics()
	{
		if (incompatible == null && loggedIn())
		{
			incompatible = CollectionLogReader.incompatibleMetrics(client);
		}
		return incompatible == null ? Set.of() : incompatible;
	}
}
