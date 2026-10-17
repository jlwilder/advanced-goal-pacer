package com.mygoals.ui;

import com.mygoals.domain.GoalDateFormat;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseEvent;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * Paints at most 28 days. No files, network, or engine mutations run on the
 * EDT.
 */
final class DailyPaceChart extends JPanel
{
	interface Loader
	{
		void load(String start, String end, Consumer<GoalsView.HistoryPage> completed);
	}

	private static final Color GREEN = new Color(102, 204, 128), GOLD = new Color(230, 184, 92), MUTED = new Color(175, 175, 175);
	private final JLabel range = new JLabel(), status = new JLabel();
	private final JButton previous = new JButton("‹"), next = new JButton("›"), current = new JButton("Today"), end = new JButton("End"),
			retry = new JButton("Retry");
	private final Plot plot = new Plot();
	private final Map<String, GoalsView.Day> archived = new LinkedHashMap<>();
	private GoalsView.Goal goal;
	private GoalDateFormat format = GoalDateFormat.AMERICAN;
	private Loader loader;
	private LocalDate start;
	private String loadedWindow;
	private boolean loading;

	DailyPaceChart()
	{
		setName("dailyPaceChart");
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setAlignmentX(Component.LEFT_ALIGNMENT);
		setBackground(new Color(45, 45, 45));
		setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(65, 65, 65)),
				BorderFactory.createEmptyBorder(9, 10, 9, 10)));
		JLabel heading = new JLabel("Daily progress");
		heading.setAlignmentX(Component.LEFT_ALIGNMENT);
		add(heading);
		range.setName("dailyChartRange");
		range.setForeground(MUTED);
		range.setAlignmentX(Component.LEFT_ALIGNMENT);
		add(range);
		plot.setName("dailyPacePlot");
		plot.setAlignmentX(Component.LEFT_ALIGNMENT);
		add(plot);
		JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
		controls.setOpaque(false);
		controls.setAlignmentX(Component.LEFT_ALIGNMENT);
		previous.setName("dailyChartPrevious");
		next.setName("dailyChartNext");
		current.setName("dailyChartToday");
		end.setName("dailyChartEnd");
		for (JButton button : List.of(previous, current, next, end))
		{
			controls.add(button);
		}
		previous.setToolTipText("Previous 28 days");
		next.setToolTipText("Next 28 days");
		end.setToolTipText("Show the end of the goal, or current period when no finish date is known");
		previous.addActionListener(e -> move(start.minusDays(DailyPaceSeries.WINDOW_DAYS)));
		next.addActionListener(e -> move(start.plusDays(DailyPaceSeries.WINDOW_DAYS)));
		current.addActionListener(e -> move(LocalDate.parse(goal.todayDate).minusDays(7)));
		end.addActionListener(e -> move(DailyPaceSeries.last(goal).minusDays(DailyPaceSeries.WINDOW_DAYS - 1)));
		add(controls);
		JPanel legend = new JPanel();
		legend.setName("dailyChartLegend");
		legend.setLayout(new BoxLayout(legend, BoxLayout.Y_AXIS));
		legend.setOpaque(false);
		legend.setToolTipText(
				"<html>Saved past pace stays fixed. Missed days have no credited work and increasing pace within the period.<br>Future pace assumes today's assignment is met unless deferred. Recovered gains appear on their recognition day.</html>");
		legend.add(legendEntry("Completed", GREEN, false));
		legend.add(legendEntry("Saved Pace", GOLD, false));
		legend.add(legendEntry("Forecasted pace", GOLD, true));
		legend.setAlignmentX(Component.LEFT_ALIGNMENT);
		add(legend);
		JPanel feedback = new JPanel(new BorderLayout());
		feedback.setOpaque(false);
		feedback.setAlignmentX(Component.LEFT_ALIGNMENT);
		status.setName("dailyChartStatus");
		status.setForeground(MUTED);
		feedback.add(status, BorderLayout.CENTER);
		retry.setName("retryDailyHistory");
		retry.setVisible(false);
		retry.addActionListener(e ->
		{
			loadedWindow = null;
			refresh();
		});
		feedback.add(retry, BorderLayout.EAST);
		add(feedback);
	}

	private static JLabel legendEntry(String text, Color color, boolean dashed)
	{
		JLabel label = new JLabel(text, new Icon()
		{
			@Override
			public int getIconWidth()
			{
				return 18;
			}

			@Override
			public int getIconHeight()
			{
				return 14;
			}

			@Override
			public void paintIcon(Component component, Graphics graphics, int x, int y)
			{
				Graphics2D g = (Graphics2D) graphics.create();
				try
				{
					g.setColor(color);
					if (dashed)
					{
						g.setStroke(paceStroke(true));
						g.drawLine(x, y + 7, x + 18, y + 7);
					}
					else
					{
						g.fillRect(x + 4, y + 2, 10, 10);
					}
				}
				finally
				{
					g.dispose();
				}
			}
		}, JLabel.LEFT);
		label.setForeground(MUTED);
		label.setIconTextGap(6);
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		return label;
	}

	private static BasicStroke paceStroke(boolean projected)
	{
		return projected ? new BasicStroke(1.6f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 0, new float[] { 4, 3 }, 0)
				: new BasicStroke(1.6f);
	}

	void setLoader(Loader loader)
	{
		this.loader = loader;
		if (goal != null)
		{
			refresh();
		}
	}

	void render(GoalsView.Goal goal, GoalDateFormat format)
	{
		this.goal = goal;
		this.format = format;
		if (start == null)
		{
			start = LocalDate.parse(goal.todayDate).minusDays(7);
		}
		start = clamp(start);
		refresh();
	}

	List<DailyPaceSeries.Point> points()
	{
		return plot.points;
	}

	private LocalDate clamp(LocalDate candidate)
	{
		LocalDate first = DailyPaceSeries.first(goal), lastStart = DailyPaceSeries.last(goal).minusDays(DailyPaceSeries.WINDOW_DAYS - 1);
		if (lastStart.isBefore(first))
		{
			lastStart = first;
		}
		return candidate.isBefore(first) ? first : candidate.isAfter(lastStart) ? lastStart : candidate;
	}

	private void move(LocalDate candidate)
	{
		if (goal == null || loading)
		{
			return;
		}
		start = clamp(candidate);
		status.setText("");
		retry.setVisible(false);
		refresh();
	}

	private void refresh()
	{
		plot.points = DailyPaceSeries.build(goal, archived.values(), start);
		plot.today = LocalDate.parse(goal.todayDate);
		plot.format = format;
		plot.unit = TodayCardBody.unit(goal.spec);
		LocalDate last = plot.points.isEmpty() ? start : plot.points.get(plot.points.size() - 1).date;
		range.setText(format.date(start) + " → " + format.date(last) + " · " + plot.unit + " / day");
		previous.setEnabled(!loading && start.isAfter(DailyPaceSeries.first(goal)));
		next.setEnabled(!loading && last.isBefore(DailyPaceSeries.last(goal)));
		current.setEnabled(!loading);
		end.setEnabled(!loading);
		plot.repaint();
		String window = start + ":" + last + ":" + goal.todayDate;
		LocalDate recentStart = LocalDate.parse(goal.todayDate).minusDays(31);
		if (loading || loader == null || !goal.hasOlderDays || !start.isBefore(recentStart) || window.equals(loadedWindow))
		{
			return;
		}
		loading = true;
		loadedWindow = window;
		status.setText("Loading daily history…");
		retry.setVisible(false);
		previous.setEnabled(false);
		next.setEnabled(false);
		current.setEnabled(false);
		end.setEnabled(false);
		loader.load(start.toString(), last.plusDays(1).toString(), page ->
		{
			loading = false;
			if (page.error.isEmpty())
			{
				archived.clear();
				page.days.forEach(day -> archived.put(day.periodId + "@" + day.date, day));
				status.setText("");
			}
			else
			{
				status.setText("Daily history unavailable");
				retry.setVisible(true);
			}
			refresh();
			revalidate();
			repaint();
		});
	}

	private static final class Plot extends JPanel
	{
		private static final int LEFT = 54, RIGHT = 12, TOP = 18, BOTTOM = 28;
		List<DailyPaceSeries.Point> points = List.of();
		LocalDate today;
		GoalDateFormat format = GoalDateFormat.AMERICAN;
		String unit = "XP";

		Plot()
		{
			setOpaque(false);
			setPreferredSize(new Dimension(360, 190));
			setMaximumSize(new Dimension(Integer.MAX_VALUE, 190));
			setToolTipText("");
		}

		@Override
		public String getToolTipText(MouseEvent event)
		{
			int index = (int) ((event.getX() - LEFT) / step());
			if (event.getX() < LEFT || index < 0 || index >= points.size())
			{
				return null;
			}
			DailyPaceSeries.Point point = points.get(index);
			return "<html>" + format.date(point.date) + (point.date.equals(today) ? " · Today" : "") +
					"<br>Completed: "
					+ (point.completed == null ? point.projected ? "—" : "not recorded" : amount(point.completed) + " " + unit) +
					"<br>" + (point.projected ? "Forecast pace: " : "Saved pace: ")
					+ (point.pace == null ? "not recorded" : amount(point.pace) + " " + unit) +
					(point.deferred ? "<br>Deferred to tomorrow" : "") + "</html>";
		}

		private double step()
		{
			return Math.max(1, getWidth() - LEFT - RIGHT) / (double) Math.max(1, points.size());
		}

		private static String amount(long value)
		{
			return String.format(java.util.Locale.US, "%,d", value);
		}

		private static String compact(long value)
		{
			return value >= 1_000_000 ? String.format(java.util.Locale.US, "%.1fm", value / 1_000_000.0)
					: value >= 1000 ? String.format(java.util.Locale.US, "%.1fk", value / 1000.0) : Long.toString(value);
		}

		private static double axisMaximum(long maximum)
		{
			double raw = Math.max(1, maximum / 4.0), power = Math.pow(10, Math.floor(Math.log10(raw))), fraction = raw / power;
			return power * (fraction <= 1 ? 1 : fraction <= 2 ? 2 : fraction <= 5 ? 5 : 10) * 4;
		}

		@Override
		protected void paintComponent(Graphics graphics)
		{
			super.paintComponent(graphics);
			Graphics2D g = (Graphics2D) graphics.create();
			try
			{
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				long max = 1;
				for (DailyPaceSeries.Point point : points)
				{
					max = Math.max(max, Math.max(point.completed == null ? 0 : point.completed, point.pace == null ? 0 : point.pace));
				}
				double scale = axisMaximum(max);
				int floor = getHeight() - BOTTOM, height = Math.max(1, floor - TOP);
				for (int tick = 0; tick <= 4; tick++)
				{
					int y = floor - height * tick / 4;
					g.setColor(new Color(64, 64, 64));
					g.drawLine(LEFT, y, getWidth() - RIGHT, y);
					g.setColor(MUTED);
					String label = compact(Math.round(scale * tick / 4.0));
					g.drawString(label, LEFT - 7 - g.getFontMetrics().stringWidth(label), y + 4);
				}
				double step = step();
				for (int index = 0; index < points.size(); index++)
				{
					DailyPaceSeries.Point point = points.get(index);
					int x = LEFT + (int) ((index + .5) * step);
					if (point.date.equals(today))
					{
						g.setColor(new Color(100, 100, 100));
						g.drawLine(x, TOP - 6, x, floor);
						g.setColor(MUTED);
						g.drawString("Today", Math.min(x - 12, getWidth() - 40), 12);
					}
					if (point.completed != null)
					{
						int barHeight = (int) Math.round(point.completed / scale * height), width = Math.max(2, (int) (step * .56));
						g.setColor(GREEN);
						g.fillRect(x - width / 2, floor - Math.max(1, barHeight), width, Math.max(1, barHeight));
					}
					if (point.pace != null)
					{
						int y = floor - (int) Math.round(point.pace / scale * height);
						g.setColor(GOLD);
						g.setStroke(paceStroke(point.projected));
						if (index > 0 && points.get(index - 1).pace != null)
						{
							int previousY = floor - (int) Math.round(points.get(index - 1).pace / scale * height);
							g.drawLine(LEFT + (int) ((index - .5) * step), previousY, x, y);
						}
						if (!point.projected)
						{
							g.fillOval(x - 2, y - 2, 4, 4);
						}
					}
					if (index == 0 || index == points.size() - 1 || index % 7 == 0)
					{
						String label = format.date(point.date).substring(0, 5);
						g.setColor(MUTED);
						g.drawString(label, x - g.getFontMetrics().stringWidth(label) / 2, floor + 18);
					}
				}
			}
			finally
			{
				g.dispose();
			}
		}
	}
}
