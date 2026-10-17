package com.mygoals.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.UIManager;
import net.runelite.client.ui.FontManager;

/**
 * Creation actions, section labels and group labels have separate visual roles.
 */
final class SidebarStyle
{
	private static final Color GREEN = new Color(102, 204, 128), MUTED = new Color(180, 180, 180), LINE = new Color(65, 65, 65);
	private static final Icon ADD = new Glyph(false), FOLDER = new Glyph(true);
	static final Icon TODO = new SectionGlyph(0), COMPLETE = new SectionGlyph(1), TOMORROW = new SectionGlyph(2);

	private SidebarStyle()
	{
	}

	/**
	 * RuneScape's small face has its own pixel grid; resizing the regular face
	 * distorts its strokes.
	 */
	static void smallText(JComponent component, float reduction)
	{
		Font current = component.getFont(), small = FontManager.getRunescapeSmallFont();
		String family = current.getFamily();
		boolean runescape = family.equals(FontManager.getRunescapeFont().getFamily()) ||
				family.equals(FontManager.getRunescapeBoldFont().getFamily()) || family.equals(small.getFamily());
		component.setFont(runescape ? small : current.deriveFont(Font.PLAIN, Math.max(11f, current.getSize2D() - reduction)));
	}

	static void creation(JButton button, boolean group)
	{
		button.setHorizontalAlignment(JButton.LEFT);
		button.setIcon(group ? FOLDER : ADD);
		button.setIconTextGap(8);
		button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		button.setContentAreaFilled(!group);
		button.setOpaque(!group);
		Color background = UIManager.getColor("Button.disabledBackground");
		button.setBackground(background == null ? new Color(48, 48, 48) : new Color(background.getRGB()));
		button.setForeground(group ? MUTED : GREEN);
		if (!group)
		{
			button.setFont(button.getFont().deriveFont(java.awt.Font.PLAIN));
		}
		button.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(LINE),
				BorderFactory.createEmptyBorder(7, 9, 7, 9)));
	}

	static void group(JButton button)
	{
		button.setContentAreaFilled(false);
		button.setOpaque(false);
		button.setIcon(FOLDER);
		button.setIconTextGap(6);
		button.setForeground(new Color(190, 179, 151));
		button.setBorder(BorderFactory.createEmptyBorder(6, 2, 5, 3));
		button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		button.setToolTipText("Group: " + button.getText().substring(2) + " • Click to expand or collapse");
	}

	static void agendaSection(JButton button)
	{
		button.setContentAreaFilled(false);
		button.setOpaque(false);
		button.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, LINE),
				BorderFactory.createEmptyBorder(7, 0, 7, 2)));
		button.setMaximumSize(new Dimension(Integer.MAX_VALUE, button.getPreferredSize().height));
		button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
	}

	/**
	 * Original vector glyphs; shared by every group and action without image
	 * loading.
	 */
	private static final class Glyph implements Icon
	{
		private final boolean folder;
		private static final Path2D FOLDER_SHAPE = folder();

		Glyph(boolean folder)
		{
			this.folder = folder;
		}

		private static Path2D folder()
		{
			Path2D shape = new Path2D.Double();
			shape.moveTo(1.5, 3);
			shape.lineTo(6, 3);
			shape.lineTo(8, 5);
			shape.lineTo(14.5, 5);
			shape.lineTo(14.5, 13);
			shape.lineTo(1.5, 13);
			shape.closePath();
			return shape;
		}

		@Override
		public int getIconWidth()
		{
			return 16;
		}

		@Override
		public int getIconHeight()
		{
			return 16;
		}

		@Override
		public void paintIcon(Component component, Graphics graphics, int x, int y)
		{
			Graphics2D copy = (Graphics2D) graphics.create();
			try
			{
				copy.translate(x, y);
				copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				copy.setColor(component.getForeground());
				copy.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				if (folder)
				{
					copy.draw(FOLDER_SHAPE);
					copy.drawLine(2, 6, 14, 6);
				}
				else
				{
					copy.drawLine(3, 8, 13, 8);
					copy.drawLine(8, 3, 8, 13);
				}
			}
			finally
			{
				copy.dispose();
			}
		}
	}

	private static final class SectionGlyph implements Icon
	{
		private final int kind;

		SectionGlyph(int kind)
		{
			this.kind = kind;
		}

		@Override
		public int getIconWidth()
		{
			return 16;
		}

		@Override
		public int getIconHeight()
		{
			return 16;
		}

		@Override
		public void paintIcon(Component component, Graphics graphics, int x, int y)
		{
			Graphics2D copy = (Graphics2D) graphics.create();
			try
			{
				copy.translate(x, y);
				copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				copy.setColor(component.getForeground());
				copy.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				if (kind == 0)
				{
					for (int row = 0; row < 3; row++)
					{
						int top = 2 + row * 5;
						copy.drawRect(2, top, 3, 3);
						copy.drawLine(8, top + 1, 14, top + 1);
					}
				}
				else
				{
					copy.drawOval(1, 1, 14, 14);
					if (kind == 1)
					{
						copy.drawLine(4, 8, 7, 11);
						copy.drawLine(7, 11, 12, 5);
					}
					else
					{
						copy.drawLine(8, 4, 8, 8);
						copy.drawLine(8, 8, 11, 10);
					}
				}
			}
			finally
			{
				copy.dispose();
			}
		}
	}
}
