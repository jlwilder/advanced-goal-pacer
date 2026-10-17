package com.mygoals.ui;

import com.mygoals.domain.GoalDateFormat;
import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;

/**
 * A local-calendar selector; no conversions through the machine's UTC offset.
 */
public final class CalendarPicker extends JPanel
{
	private final JButton choose = new JButton();
	private LocalDate date;
	private final GoalDateFormat dateFormat;
	private Runnable onChange = () ->
	{
	};

	public CalendarPicker(LocalDate date)
	{
		this(date, GoalDateFormat.AMERICAN);
	}

	public CalendarPicker(LocalDate date, GoalDateFormat dateFormat)
	{
		super(new BorderLayout());
		this.date = date;
		this.dateFormat = dateFormat;
		choose.setText(dateFormat.date(date) + "  ▾");
		choose.addActionListener(event -> popup());
		add(choose);
	}

	public LocalDate getDate()
	{
		return date;
	}

	public void setDate(LocalDate date)
	{
		if (this.date.equals(date))
		{
			return;
		}
		this.date = date;
		choose.setText(dateFormat.date(date) + "  ▾");
		onChange.run();
	}

	public void onChange(Runnable callback)
	{
		onChange = callback;
	}

	@Override
	public void setEnabled(boolean enabled)
	{
		super.setEnabled(enabled);
		if (choose != null)
		{
			choose.setEnabled(enabled);
		}
	}

	private void popup()
	{
		JPopupMenu popup = new JPopupMenu();
		JPanel calendar = new JPanel(new BorderLayout(4, 4));
		YearMonth[] month = { YearMonth.from(date) };
		Runnable[] draw = new Runnable[1];
		draw[0] = () ->
		{
			calendar.removeAll();
			JPanel header = new JPanel(new BorderLayout());
			JButton previous = new JButton("‹"), next = new JButton("›");
			JLabel title = new JLabel(month[0].format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)), JLabel.CENTER);
			previous.addActionListener(e ->
			{
				month[0] = month[0].minusMonths(1);
				draw[0].run();
			});
			next.addActionListener(e ->
			{
				month[0] = month[0].plusMonths(1);
				draw[0].run();
			});
			header.add(previous, BorderLayout.WEST);
			header.add(title, BorderLayout.CENTER);
			header.add(next, BorderLayout.EAST);
			JPanel days = new JPanel(new GridLayout(0, 7, 2, 2));
			for (String day : new String[] { "Mo", "Tu", "We", "Th", "Fr", "Sa", "Su" })
			{
				days.add(new JLabel(day, JLabel.CENTER));
			}
			int offset = month[0].atDay(1).getDayOfWeek().getValue() - 1;
			for (int blank = 0; blank < offset; blank++)
			{
				days.add(new JLabel(""));
			}
			for (int day = 1; day <= month[0].lengthOfMonth(); day++)
			{
				LocalDate selected = month[0].atDay(day);
				JButton button = new JButton(Integer.toString(day));
				button.setMargin(new java.awt.Insets(3, 5, 3, 5));
				if (selected.equals(date))
				{
					button.setForeground(new java.awt.Color(240, 187, 71));
				}
				button.addActionListener(e ->
				{
					setDate(selected);
					popup.setVisible(false);
				});
				days.add(button);
			}
			calendar.add(header, BorderLayout.NORTH);
			calendar.add(days, BorderLayout.CENTER);
			calendar.revalidate();
			calendar.repaint();
			popup.pack();
		};
		popup.add(calendar);
		draw[0].run();
		popup.show(choose, 0, choose.getHeight());
	}
}
