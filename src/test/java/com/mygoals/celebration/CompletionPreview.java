package com.mygoals.celebration;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/** Production painter, synthetic completions only. */
public final class CompletionPreview
{
    private CompletionPreview() {}
    public static void write(Path output) throws Exception
    {
        BufferedImage image = new BufferedImage(800, 530, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics(); g.setColor(new Color(27, 30, 28)); g.fillRect(0, 0, 800, 530);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13)); g.setColor(Color.LIGHT_GRAY);
        g.drawString("Today assignment · card only", 28, 27); g.drawString("Overall achievement · card + fireworks", 420, 27);
        paint(g, "daily", "Daily target complete!", "Mining this week", "Target: 14,286 XP · 2026-10-16", false, 205, 50);
        paint(g, "overall", "Goal complete!", "Gauntlet armour seeds", "All selected unique totals reached", true, 600, 50);
        g.setColor(Color.LIGHT_GRAY); g.drawString("Long goal names fit inside the card", 28, 310);
        paint(g, "long", "Goal complete!", "My exceptionally long custom goal name to train mining and reach level 99 with friends", "Level 99 reached", false, 205, 335);
        paint(g, "recovered", "Daily targets complete!", "3 goals completed", "Progress recovered at login or refresh", false, 600, 335);
        g.dispose(); ImageIO.write(image, "png", output.resolve("completion-cards.png").toFile());
    }
    private static void paint(Graphics2D g, String id, String title, String name, String detail, boolean overall, int center, int top)
    {
        CelebrationQueue queue = new CelebrationQueue(); queue.enqueue(new CelebrationQueue.Card(id, title, name, detail, overall));
        queue.update(0, false, true); CompletionOverlay.paint(g, queue.update(1000, false, true), center, top, 340, true);
    }
}
