package com.mygoals.ui;

import com.mygoals.domain.GoalDateFormat;
import com.mygoals.domain.GoalSpec;
import com.mygoals.tracking.MetricCatalogue;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseEvent;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JViewport;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;

/**
 * Recorded quota results; overall drop/level/KC achievement never substitutes
 * for period work.
 */
final class PeriodHistoryPanel extends JPanel
{
	private static final Color GREEN = new Color(102, 204, 128), GOLD = new Color(230, 184, 92), MUTED = new Color(175, 175, 175);
	private final JLabel summary = new JLabel(), gapNote = new JLabel("⋯ Unrecorded gaps are not counted as missed.");
	private final HistoryStrip strip = new HistoryStrip();
	private final JScrollPane scroll = new JScrollPane(strip, JScrollPane.VERTICAL_SCROLLBAR_NEVER,
			JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
	private List<GoalsView.Period> rendered = List.of();
	private long revision;
	private final JButton older = new JButton("Load older periods");
	private final JLabel historyError = new JLabel();
	private final Map<String, GoalsView.Period> loaded = new LinkedHashMap<>();
	private BiConsumer<String, Consumer<GoalsView.HistoryPage>> loader;
	private GoalsView.Goal goal;
	private String before;
	private boolean more = true;

	void setDateFormat(GoalDateFormat format)
	{
		strip.dateFormat = format;
		strip.repaint();
	}

	PeriodHistoryPanel()
	{
		setName("periodHistory");
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setOpaque(false);
		setAlignmentX(Component.LEFT_ALIGNMENT);
		setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(65, 65, 65)),
				BorderFactory.createEmptyBorder(8, 0, 0, 0)));
		JPanel heading = new JPanel(new BorderLayout());
		heading.setOpaque(false);
		heading.setAlignmentX(Component.LEFT_ALIGNMENT);
		heading.add(new JLabel("Period history"), BorderLayout.WEST);
		summary.setName("periodHistorySummary");
		summary.setForeground(MUTED);
		heading.add(summary, BorderLayout.EAST);
		add(heading);
		add(Box.createVerticalStrut(3));
		scroll.setName("periodHistoryScroll");
		scroll.setBorder(null);
		scroll.setAlignmentX(Component.LEFT_ALIGNMENT);
		scroll.setPreferredSize(new Dimension(300, 54));
		scroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 54));
		add(scroll);
		JPanel legend = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
		legend.setOpaque(false);
		legend.setAlignmentX(Component.LEFT_ALIGNMENT);
		for (Object[] item : new Object[][] { { "✓ Met    ", GREEN }, { "– Not met    ", MUTED }, { "◇ Current", GOLD } })
		{
			JLabel label = new JLabel((String) item[0]);
			label.setForeground((Color) item[1]);
			legend.add(label);
		}
		add(legend);
		gapNote.setForeground(MUTED);
		gapNote.setAlignmentX(Component.LEFT_ALIGNMENT);
		add(gapNote);
		older.setName("loadOlderPeriods");
		older.setAlignmentX(Component.LEFT_ALIGNMENT);
		older.setVisible(false);
		older.setToolTipText("Load up to six older recorded months from local history.");
		older.addActionListener(e ->
		{
			if (loader == null)
			{
				return;
			}
			older.setEnabled(false);
			older.setText("Loading history…");
			historyError.setText("");
			loader.accept(before, page ->
			{
				older.setEnabled(true);
				older.setText("Load older periods");
				if (!page.error.isEmpty())
				{
					historyError.setText(page.error);
					revalidate();
					return;
				}
				page.periods.forEach(p -> loaded.put(p.id, p));
				before = page.before;
				more = page.more;
				if (goal != null)
				{
					render(goal);
				}
				revalidate();
				repaint();
			});
		});
		add(older);
		historyError.setForeground(new Color(244, 140, 126));
		historyError.setAlignmentX(Component.LEFT_ALIGNMENT);
		add(historyError);
	}

	void setHistoryLoader(BiConsumer<String, Consumer<GoalsView.HistoryPage>> loader)
	{
		this.loader = loader;
		if (goal != null)
		{
			render(goal);
		}
	}

	void render(GoalsView.Goal goal)
	{
		this.goal = goal;
		Map<String, GoalsView.Period> merged = new LinkedHashMap<>(loaded);
		goal.periods.forEach(p -> merged.put(p.id, p));
		List<GoalsView.Period> periods = new ArrayList<>(merged.values());
		periods.sort(java.util.Comparator.comparing(p -> p.start));
		long closed = periods.stream().filter(p -> !p.current).count();
		boolean canLoad = goal.hasOlderHistory && more && loader != null;
		setVisible(closed > 0 || canLoad);
		older.setVisible(canLoad);
		long met = periods.stream().filter(p -> !p.current && p.met).count();
		summary.setText(met + " / " + closed + " met" + (canLoad ? " shown" : ""));
		if (periods.equals(rendered))
		{
			return;
		}
		rendered = List.copyOf(periods);
		List<Cell> cells = new ArrayList<>();
		GoalsView.Period previous = null;
		boolean gaps = false;
		for (GoalsView.Period period : periods)
		{
			if (previous != null && LocalDate.parse(period.start).isAfter(LocalDate.parse(previous.endExclusive)))
			{
				cells.add(new Cell(null, previous.endExclusive, period.start));
				gaps = true;
			}
			cells.add(new Cell(period, period.start, period.endExclusive));
			previous = period;
		}
		gapNote.setVisible(gaps);
		var bar = scroll.getHorizontalScrollBar();
		int prior = bar.getValue();
		boolean followLatest = bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - 2;
		strip.cells = cells;
		strip.unit = goal.spec.trackType == GoalSpec.TrackType.EXPERIENCE ? "XP" : MetricCatalogue.unit(goal.spec.metricId);
		strip.revalidate();
		strip.repaint();
		long nextRevision = ++revision;
		SwingUtilities.invokeLater(() ->
		{
			if (revision == nextRevision)
			{
				bar.setValue(followLatest ? bar.getMaximum() : prior);
			}
		});
	}

	private static final class Cell
	{
		final GoalsView.Period period;
		final String start, end;

		Cell(GoalsView.Period period, String start, String end)
		{
			this.period = period;
			this.start = start;
			this.end = end;
		}
	}

	/**
	 * One canvas and a scrollbar keep multi-year daily history from creating
	 * thousands of Swing controls.
	 */
	private static final class HistoryStrip extends javax.swing.JComponent implements Scrollable
	{
		private static final int CELL = 66;
		private GoalDateFormat dateFormat = GoalDateFormat.AMERICAN;
		private List<Cell> cells = List.of();
		private String unit = "";

		HistoryStrip()
		{
			setName("periodHistoryStrip");
			setToolTipText("");
		}

		@Override
		protected void paintComponent(Graphics graphics)
		{
			Graphics2D g = (Graphics2D) graphics.create();
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			Rectangle clip = g.getClipBounds();
			int first = Math.max(0, clip.x / CELL), last = Math.min(cells.size(), (clip.x + clip.width) / CELL + 1);
			for (int i = first; i < last; i++)
			{
				Cell cell = cells.get(i);
				GoalsView.Period p = cell.period;
				int x = i * CELL + (CELL - 33) / 2;
				Color outline = p == null ? MUTED : p.current ? GOLD : p.met ? GREEN : MUTED;
				g.setColor(p == null ? new Color(45, 45, 45)
						: p.met ? new Color(43, 80, 53) : p.current ? new Color(88, 68, 35) : new Color(68, 68, 68));
				g.fillRoundRect(x, 3, 33, 21, 4, 4);
				g.setColor(outline);
				g.drawRoundRect(x, 3, 33, 21, 4, 4);
				if (p != null && p.current && !p.met)
				{
					center(g, "Now", x + 16, 18);
				}
				else if (p != null && p.met)
				{
					g.drawLine(x + 10, 13, x + 14, 17);
					g.drawLine(x + 14, 17, x + 22, 9);
				}
				else
				{
					center(g, p == null ? "⋯" : "–", x + 16, 17);
				}
				g.setColor(MUTED);
				center(g, p == null ? "Gap" : dateFormat.date(LocalDate.parse(cell.start)), i * CELL + CELL / 2, 38);
			}
			g.dispose();
		}

		private static void center(Graphics2D g, String text, int center, int baseline)
		{
			g.drawString(text, center - g.getFontMetrics().stringWidth(text) / 2, baseline);
		}

		@Override
		public String getToolTipText(MouseEvent event)
		{
			int index = event.getX() / CELL;
			if (index < 0 || index >= cells.size())
			{
				return null;
			}
			Cell cell = cells.get(index);
			GoalsView.Period p = cell.period;
			String dates = dateFormat.date(LocalDate.parse(cell.start)) + " through "
					+ dateFormat.date(LocalDate.parse(cell.end).minusDays(1));
			if (p == null)
			{
				return "<html>" + dates + "<br>No recorded periods in this gap. Recovered work belongs to its recognition period.</html>";
			}
			return "<html>" + dates + "<br>" + String.format(java.util.Locale.US, "%,d / %,d %s", p.progress, p.target, unit) +
					"<br>" + (p.current ? "Current period · " : "")
					+ (p.met ? "Quota met" : p.current ? "Quota in progress" : "Quota not met") +
					(p.stoppedEarly ? " · goal ended early" : "") + "<br>" + p.zone + "</html>";
		}

		@Override
		public Dimension getPreferredSize()
		{
			return new Dimension(Math.max(1, cells.size()) * CELL + 4, 42);
		}

		@Override
		public Dimension getPreferredScrollableViewportSize()
		{
			return new Dimension(300, 42);
		}

		@Override
		public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction)
		{
			return CELL;
		}

		@Override
		public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction)
		{
			return Math.max(CELL, visible.width - CELL);
		}

		@Override
		public boolean getScrollableTracksViewportWidth()
		{
			return getParent() instanceof JViewport && getParent().getWidth() > getPreferredSize().width;
		}

		@Override
		public boolean getScrollableTracksViewportHeight()
		{
			return true;
		}
	}
}
