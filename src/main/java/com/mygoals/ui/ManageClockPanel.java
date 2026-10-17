package com.mygoals.ui;

import com.mygoals.domain.GoalDateFormat;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.Timer;
import javax.swing.text.DefaultCaret;

/**
 * Read-only local time from the same system clock used by the goal controller.
 */
final class ManageClockPanel extends JPanel implements AutoCloseable
{
	private static final Color GOLD = new Color(230, 184, 92), MUTED = new Color(175, 175, 175);
	private final Clock clock;
	private final Supplier<ZoneId> localZone;
	private final JLabel time = label("", "clockTime"), date = label("", "clockDate"), offset = label("", "clockOffset"),
			rollover = label("", "clockRollover");
	private final JLabel zone = label("", "clockZone");
	private final Timer ticker;
	private GoalDateFormat dateFormat = GoalDateFormat.AMERICAN;

	void setDateFormat(GoalDateFormat format)
	{
		dateFormat = format;
		refresh();
	}

	ManageClockPanel(Clock clock, Runnable close)
	{
		this(clock, ZoneId::systemDefault, close);
	}

	ManageClockPanel(Clock clock, Supplier<ZoneId> localZone, Runnable close)
	{
		this.clock = clock;
		this.localZone = localZone;
		setName("manageClockPanel");
		setLayout(new BorderLayout(0, 12));
		setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
		JPanel content = new JPanel();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		JLabel title = label("Local time", "clockHeading");
		title.setFont(title.getFont().deriveFont(java.awt.Font.BOLD, 15f));
		content.add(title);
		time.setFont(time.getFont().deriveFont(java.awt.Font.BOLD, 30f));
		time.setForeground(GOLD);
		content.add(Box.createVerticalStrut(6));
		content.add(time);
		content.add(date);
		content.add(Box.createVerticalStrut(4));
		zone.setForeground(MUTED);
		offset.setForeground(MUTED);
		rollover.setForeground(MUTED);
		content.add(zone);
		content.add(offset);
		content.add(rollover);
		content.add(Box.createVerticalStrut(14));
		JTextArea source = note("Automatically follows your computer's local clock and time zone.", 2);
		source.setForeground(MUTED);
		content.add(source);
		add(content, BorderLayout.NORTH);
		JPanel actions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 5, 0));
		JButton cancel = new JButton("Close");
		cancel.setName("closeClock");
		cancel.addActionListener(e -> close.run());
		actions.add(cancel);
		add(actions, BorderLayout.SOUTH);
		ticker = new Timer(1000, e -> refresh());
		ticker.setCoalesce(true);
		refresh();
	}

	void refresh()
	{
		ZoneId selected = localZone.get();
		ZonedDateTime now = clock.instant().atZone(selected);
		time.setText(now.format(DateTimeFormatter.ofPattern("h:mm:ss a", Locale.US)));
		date.setText(now.getDayOfWeek().getDisplayName(java.time.format.TextStyle.FULL, Locale.US) + ", "
				+ dateFormat.longDate(now.toLocalDate()));
		zone.setText("Time zone: " + selected.getId());
		offset.setText(now.format(DateTimeFormatter.ofPattern("zzzz", Locale.US)) + " · UTC"
				+ (now.getOffset().getId().equals("Z") ? "+00:00" : now.getOffset().getId()));
		rollover.setText("Next day starts: " + dateFormat.date(now.toLocalDate().plusDays(1)) + ", " +
				now.toLocalDate().plusDays(1).atStartOfDay(selected).format(DateTimeFormatter.ofPattern("h:mm a", Locale.US)));
	}

	void start()
	{
		ticker.start();
	}

	boolean ticking()
	{
		return ticker.isRunning();
	}

	@Override
	public void close()
	{
		ticker.stop();
	}

	private static JLabel label(String text, String name)
	{
		JLabel label = new JLabel(text);
		label.setName(name);
		label.putClientProperty("html.disable", true);
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		return label;
	}

	private static JTextArea note(String value, int rows)
	{
		JTextArea area = new JTextArea(value);
		area.setRows(rows);
		area.setEditable(false);
		area.setFocusable(false);
		area.setOpaque(false);
		area.setBorder(BorderFactory.createEmptyBorder());
		area.setLineWrap(true);
		area.setWrapStyleWord(true);
		area.setAlignmentX(Component.LEFT_ALIGNMENT);
		((DefaultCaret) area.getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
		return area;
	}
}
