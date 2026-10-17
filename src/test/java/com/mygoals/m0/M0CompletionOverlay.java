package com.mygoals.m0;

import com.mygoals.celebration.CelebrationQueue;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.function.Supplier;
import net.runelite.api.Client;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/** Finite 2D effects, owned entirely by this overlay; no player or game-widget writes. */
final class M0CompletionOverlay extends Overlay
{
    private final Client client;
    private final Supplier<CelebrationQueue.Frame> frame;

    M0CompletionOverlay(Client client, Supplier<CelebrationQueue.Frame> frame)
    {
        this.client = client;
        this.frame = frame;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
        setMovable(false);
        setSnappable(false);
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        CelebrationQueue.Frame snapshot = frame.get();
        if (snapshot == null || snapshot.card == null) { return null; }
        Graphics2D g = (Graphics2D) graphics.create();
        int center = client.getViewportXOffset() + client.getViewportWidth() / 2;
        int top = client.getViewportYOffset() + 18;
        paint(g, snapshot, center, top);
        g.dispose();
        return null;
    }

    static void paint(Graphics2D g, CelebrationQueue.Frame frame, int center, int top)
    {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        double arrival = Math.min(1, frame.visibleMillis / 350.0);
        double departure = Math.min(1, (frame.card.durationMillis() - frame.visibleMillis) / 350.0);
        int y = top - (int) (85 * (1 - Math.min(arrival, departure)));
        if (frame.fireworks())
        {
            double age = frame.visibleMillis / 1000.0;
            for (int burst = 0; burst < 3; burst++)
            {
                double t = age - 0.2 - burst * 0.3;
                if (t < 0 || t > 1.3) { continue; }
                int cx = center + (burst - 1) * 130, cy = top + 110 + (burst % 2) * 30;
                g.setColor(new Color(burst == 1 ? 100 : 240, burst == 1 ? 220 : 185, 95, (int) (255 * (1 - t / 1.3))));
                for (int spark = 0; spark < 20; spark++)
                {
                    double angle = spark * Math.PI / 10;
                    int x = cx + (int) (Math.cos(angle) * 80 * t);
                    int py = cy + (int) (Math.sin(angle) * 80 * t + 28 * t * t);
                    g.fillOval(x, py, 4, 4);
                }
            }
        }
        g.setColor(new Color(43, 36, 27, 245));
        g.fillRoundRect(center - 151, y, 302, 76, 12, 12);
        g.setColor(new Color(178, 143, 77));
        g.drawRoundRect(center - 151, y, 302, 76, 12, 12);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        g.setColor(new Color(248, 211, 124));
        center(g, frame.card.title, center, y + 29);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        g.setColor(new Color(224, 216, 198));
        center(g, frame.card.detail, center, y + 51);
    }

    private static void center(Graphics2D g, String text, int x, int y)
    {
        g.drawString(text, x - g.getFontMetrics().stringWidth(text) / 2, y);
    }
}
