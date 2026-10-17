package com.mygoals.m0;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

final class BossPageCandidates
{
    static Map<String, Integer> load()
    {
        Map<String, Integer> pages = new LinkedHashMap<>();
        try (InputStreamReader reader = new InputStreamReader(
            BossPageCandidates.class.getResourceAsStream("/m0/boss-page-candidates.json"), StandardCharsets.UTF_8))
        {
            for (JsonElement element : new JsonParser().parse(reader).getAsJsonObject().getAsJsonArray("entries"))
            {
                var entry = element.getAsJsonObject();
                String id = entry.get("metricId").getAsString();
                if (pages.containsKey(id)) { throw new IllegalStateException("Duplicate boss candidate: " + id); }
                pages.put(id, entry.get("candidatePageStructId").isJsonNull() ? null : entry.get("candidatePageStructId").getAsInt());
            }
        }
        catch (java.io.IOException ex) { throw new IllegalStateException(ex); }
        return Collections.unmodifiableMap(pages);
    }
}
