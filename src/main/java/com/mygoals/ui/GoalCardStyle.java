package com.mygoals.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;

/** Common spacing, fields and progress treatment for agenda cards. */
final class GoalCardStyle
{
	static final Color MUTED = new Color(167, 167, 167), GOLD = new Color(230, 184, 92), GREEN = new Color(102, 204, 128);

	private GoalCardStyle()
	{
	}

	static JPanel pace(String caption, String amount, String tooltip, boolean green, int fraction, String prefix)
	{
		JPanel block = column();
		JLabel heading = label(caption), progress = label(amount);
		heading.setName(prefix + "ProgressLabel");
		progress.setName(prefix + "Progress");
		heading.setForeground(MUTED);
		progress.setForeground(green ? GREEN : GOLD);
		progress.setToolTipText(tooltip);
		for (JLabel part : new JLabel[] { heading, progress })
		{
			SidebarStyle.smallText(part, 2f);
		}
		progress.setHorizontalAlignment(JLabel.RIGHT);
		JPanel row = new JPanel(new BorderLayout(4, 0));
		row.setOpaque(false);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		int available = net.runelite.client.ui.PluginPanel.PANEL_WIDTH - net.runelite.client.ui.PluginPanel.SCROLLBAR_WIDTH - 10;
		boolean stacked = heading.getPreferredSize().width + progress.getPreferredSize().width + 4 > available;
		row.add(heading, stacked ? BorderLayout.NORTH : BorderLayout.WEST);
		row.add(progress, BorderLayout.CENTER);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		block.add(row);
		JProgressBar bar = new JProgressBar(0, 1000);
		bar.setName(prefix + "ProgressBar");
		bar.setAlignmentX(Component.LEFT_ALIGNMENT);
		bar.setBorderPainted(false);
		bar.setValue(fraction);
		bar.setForeground(green ? GREEN : GOLD);
		int height = 4;
		bar.setPreferredSize(new Dimension(180, height));
		bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
		block.add(Box.createVerticalStrut(4));
		block.add(bar);
		return block;
	}

	static int fieldLabelWidth()
	{
		JLabel heading = label("Target:");
		SidebarStyle.smallText(heading, 1f);
		return heading.getPreferredSize().width + 2;
	}

	static JPanel field(String caption, JComponent value)
	{
		return field(caption, value, false);
	}

	static JPanel field(String caption, JComponent value, boolean itemAligned)
	{
		JLabel heading = label(caption + ":");
		heading.setName("todayPlan" + caption + "Label");
		heading.setForeground(MUTED);
		SidebarStyle.smallText(heading, 1f);
		heading.setVerticalAlignment(JLabel.TOP);
		if (itemAligned)
		{
			JLabel item = firstLabel(value);
			if (item != null)
			{
				Dimension iconSize = item.getPreferredSize(), textSize = heading.getPreferredSize();
				int inset = item.getBaseline(iconSize.width, iconSize.height) - heading.getBaseline(textSize.width, textSize.height);
				heading.setBorder(javax.swing.BorderFactory.createEmptyBorder(Math.max(0, inset), 0, 0, 0));
			}
		}
		heading.setPreferredSize(new Dimension(fieldLabelWidth(), heading.getPreferredSize().height));
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setOpaque(false);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.add(heading, BorderLayout.WEST);
		row.add(value, BorderLayout.CENTER);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		return row;
	}

	private static JLabel firstLabel(java.awt.Container parent)
	{
		for (Component child : parent.getComponents())
		{
			if (child instanceof JLabel)
			{
				return (JLabel) child;
			}
			if (child instanceof java.awt.Container)
			{
				JLabel found = firstLabel((java.awt.Container) child);
				if (found != null)
				{
					return found;
				}
			}
		}
		return null;
	}

	static JPanel column()
	{
		JPanel panel = new JPanel();
		panel.setOpaque(false);
		panel.setAlignmentX(Component.LEFT_ALIGNMENT);
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		return panel;
	}

	static JLabel label(String text)
	{
		JLabel label = new JLabel(text)
		{
			@Override
			public Dimension getMinimumSize()
			{
				return new Dimension(0, super.getMinimumSize().height);
			}
		};
		label.putClientProperty("html.disable", true);
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		return label;
	}

	static JLabel value(String text, String name)
	{
		JLabel value = label(text);
		value.setName(name);
		value.setFont(value.getFont().deriveFont(java.awt.Font.BOLD));
		value.setToolTipText(text);
		return value;
	}
}
