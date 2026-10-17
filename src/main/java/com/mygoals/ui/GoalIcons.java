package com.mygoals.ui;

import com.mygoals.collection.UniqueCatalogue;
import com.mygoals.domain.GoalSpec;
import com.mygoals.tracking.MetricCatalogue;

import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;

import net.runelite.api.Skill;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.hiscore.HiscoreSkill;

/**
 * Native skill and HiScores activity icons, shared by sidebar and detail
 * headers.
 */
final class GoalIcons
{
	private GoalIcons()
	{
	}

	static void bindItem(int itemId, JLabel label, ItemManager items)
	{
		if (items == null || !UniqueCatalogue.INSTANCE.items.containsKey(itemId))
		{
			return;
		}
		var image = items.getImage(itemId);
		if (image == null)
		{
			return;
		}
		image.addTo(label); // Keeps RuneLite's repaint subscription while the shared image loads.
		label.setIcon(new Icon()
		{
			@Override
			public int getIconWidth()
			{
				return 24;
			}

			@Override
			public int getIconHeight()
			{
				return 24;
			}

			@Override
			public void paintIcon(Component component, Graphics graphics, int x, int y)
			{
				Graphics2D copy = (Graphics2D) graphics.create();
				try
				{
					copy.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
					copy.drawImage(image, x, y, 24, 24, null);
				}
				finally
				{
					copy.dispose();
				}
			}
		});
	}

	static void bind(GoalSpec spec, JLabel label, SkillIconManager skills, SpriteManager sprites)
	{
		if (spec.trackType == GoalSpec.TrackType.EXPERIENCE)
		{
			if (skills != null)
			{
				try
				{
					label.setIcon(new ImageIcon(skills.getSkillImage(Skill.valueOf(spec.metricId))));
				}
				catch (IllegalArgumentException ignored)
				{
					// Retired content has no current icon.
				}
			}
		}
		else if (sprites != null)
		{
			HiscoreSkill metric = MetricCatalogue.findCountMetric(spec.metricId);
			if (metric == null)
			{
				return;
			}
			sprites.getSpriteAsync(metric.getSpriteId() < 0 ? HiscoreSkill.CLUE_SCROLL_ALL.getSpriteId() : metric.getSpriteId(), 0, image ->
			{
				Runnable apply = () -> label.setIcon(new ImageIcon(image));
				if (SwingUtilities.isEventDispatchThread())
				{
					apply.run();
				}
				else
				{
					SwingUtilities.invokeLater(apply);
				}
			});
		}
	}
}
