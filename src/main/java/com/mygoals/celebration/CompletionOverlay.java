package com.mygoals.celebration;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.runelite.api.Client;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * A noninteractive, clipped card and finite 2D sparks; effects exist only in
 * the painted frame.
 */
public final class CompletionOverlay extends Overlay
{
	private final Client client;
	private final Supplier<CelebrationQueue.Frame> frame;
	private final BooleanSupplier fireworks;

	public CompletionOverlay(Client client, Supplier<CelebrationQueue.Frame> frame, BooleanSupplier fireworks)
	{
		this.client = client;
		this.frame = frame;
		this.fireworks = fireworks;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setMovable(false);
		setSnappable(false);
		setDragTargetable(false);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		CelebrationQueue.Frame snapshot = frame.get(); // Supplier rechecks native blockers immediately before paint.
		if (snapshot == null || snapshot.card == null)
		{
			return null;
		}
		Graphics2D g = (Graphics2D) graphics.create();
		int x = client.getViewportXOffset(), y = client.getViewportYOffset(), width = client.getViewportWidth();
		g.clipRect(x, y, width, client.getViewportHeight());
		paint(g, snapshot, x + width / 2, y + 18, Math.min(340, Math.max(160, width - 24)), fireworks.getAsBoolean());
		g.dispose();
		return null;
	}

	public static void paint(Graphics2D g, CelebrationQueue.Frame frame, int center, int top, int width, boolean fireworks)
	{
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		double slide = Math.min(1, Math.min(frame.visibleMillis / 350.0, (frame.card.durationMillis() - frame.visibleMillis) / 350.0));
		int y = top - (int) (110 * (1 - slide));
		if (fireworks && frame.fireworks())
		{
			double age = frame.visibleMillis / 1000.0;
			for (int burst = 0; burst < 3; burst++)
			{
				double t = age - 0.2 - burst * 0.3;
				if (t < 0 || t > 1.3)
				{
					continue;
				}
				int cx = center + (burst - 1) * Math.min(125, width / 3), cy = top + 117 + (burst % 2) * 30;
				g.setColor(new Color(burst == 1 ? 100 : 240, burst == 1 ? 220 : 185, 95, (int) (255 * (1 - t / 1.3))));
				for (int spark = 0; spark < 20; spark++)
				{
					double angle = spark * Math.PI / 10;
					g.fillOval(cx + (int) (Math.cos(angle) * 80 * t), cy + (int) (Math.sin(angle) * 80 * t + 28 * t * t), 4, 4);
				}
			}
		}
		int left = center - width / 2;
		g.setColor(new Color(43, 36, 27, 247));
		g.fillRoundRect(left, y, width, 92, 10, 10);
		g.setColor(new Color(178, 143, 77));
		g.drawRoundRect(left, y, width, 92, 10, 10);
		g.setColor(new Color(104, 82, 46));
		g.drawLine(left + 14, y + 36, left + width - 14, y + 36);
		g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
		g.setColor(new Color(248, 211, 124));
		center(g, frame.card.title, center, y + 25, width - 24);
		g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
		g.setColor(new Color(239, 233, 219));
		center(g, frame.card.detail, center, y + 57, width - 24);
		g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
		g.setColor(new Color(206, 194, 169));
		center(g, frame.card.context, center, y + 77, width - 24);
	}

	private static void center(Graphics2D g, String text, int center, int baseline, int width)
	{
		String shown = text;
		if (g.getFontMetrics().stringWidth(shown) > width)
		{
			while (!shown.isEmpty() && g.getFontMetrics().stringWidth(shown + "…") > width)
			{
				shown = shown.substring(0, shown.length() - 1);
			}
			shown += "…";
		}
		g.drawString(shown, center - g.getFontMetrics().stringWidth(shown) / 2, baseline);
	}
}
