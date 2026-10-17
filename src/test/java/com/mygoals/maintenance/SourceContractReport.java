package com.mygoals.maintenance;

import com.google.gson.GsonBuilder;
import com.mygoals.tracking.MetricCatalogue;
import com.mygoals.collection.UniqueCatalogue;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.ScriptID;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.client.hiscore.HiscoreSkill;
import net.runelite.client.hiscore.HiscoreSkillType;
import net.runelite.client.RuneLiteProperties;

/**
 * Offline API evidence. This report deliberately makes no live support claims.
 */
public final class SourceContractReport
{
	public static void main(String[] args) throws Exception
	{
		Map<String, Object> report = new LinkedHashMap<>();
		report.put("kind", "compiled-api-contract");
		report.put("runeLiteVersion", RuneLiteProperties.getVersion());
		report.put("runtimeObservations", "NOT_RUN: requires a logged-in game session");
		report.put("skills", Arrays.stream(HiscoreSkill.values()).filter(s -> s.getType() == HiscoreSkillType.SKILL).count());
		List<Map<String, Object>> metrics = new ArrayList<>();
		for (HiscoreSkill skill : MetricCatalogue.countMetrics())
		{
			Map<String, Object> metric = new LinkedHashMap<>();
			metric.put("id", skill.name());
			metric.put("type", skill.getType().name());
			metric.put("provisionalSemantics", MetricCatalogue.semantics(skill).name());
			metrics.add(metric);
		}
		report.put("countMetrics", metrics);
		Map<String, Integer> bindings = new LinkedHashMap<>();
		bindings.put("notificationStart", ScriptID.NOTIFICATION_START);
		bindings.put("notificationDelay", ScriptID.NOTIFICATION_DELAY);
		bindings.put("notificationStage", VarClientID.NOTIFICATION_STAGE);
		bindings.put("notificationContainer", InterfaceID.NotificationDisplay.CONTAINER);
		bindings.put("collectionItemsContents", InterfaceID.Collection.ITEMS_CONTENTS);
		report.put("bindings", bindings);
		report.put("storageApi", net.runelite.client.util.Filepath.class.getName());
		report.put("catalogueVersion", UniqueCatalogue.INSTANCE.version);
		report.put("bossEntries", UniqueCatalogue.INSTANCE.bosses.size());
		report.put("collectionPages", UniqueCatalogue.INSTANCE.pages.size());
		report.put("eligibleItemIds", UniqueCatalogue.INSTANCE.items.size());
		Path output = Path.of(args[0]);
		Files.createDirectories(output.toAbsolutePath().getParent());
		Files.writeString(output, new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(report) + "\n",
				StandardCharsets.UTF_8);
		System.out.println("API contract report: " + output.toAbsolutePath());
		System.out.println("Static source contracts checked; this offline task does not simulate live game events.");
	}
}
