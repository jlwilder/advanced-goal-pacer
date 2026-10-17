package com.mygoals.ui;

import com.mygoals.tracking.MetricCatalogue;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.hiscore.HiscoreSkill;

/**
 * One selector with search inside its popup. Typing never changes the committed
 * activity.
 */
final class ActivityPicker extends JPanel
{
	private final List<HiscoreSkill> metrics = MetricCatalogue.countMetrics().stream().sorted(Comparator.comparing(HiscoreSkill::getName))
			.collect(Collectors.toList());
	private final JButton choose = new JButton();
	private final JTextField search = new JTextField();
	private final DefaultListModel<HiscoreSkill> matches = new DefaultListModel<>();
	private final JList<HiscoreSkill> results = new JList<>(matches);
	private final JPopupMenu popup = new JPopupMenu();
	private final JLabel empty = new JLabel("No matching activities");
	private HiscoreSkill selected;
	private Runnable changed = () ->
	{
	};

	ActivityPicker(HiscoreSkill initial)
	{
		super(new BorderLayout());
		setName("countMetric");
		selected = initial;
		choose.setName("activityButton");
		choose.setHorizontalAlignment(JButton.LEFT);
		choose.setText(initial.getName() + "  ▾");
		choose.setToolTipText("Choose or search all HiScores activities and bosses");
		add(choose);
		search.setName("countSearch");
		search.getAccessibleContext().setAccessibleName("Search activities and bosses");
		JPanel body = new JPanel(new BorderLayout(0, 6));
		body.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		JPanel query = new JPanel(new BorderLayout(0, 3));
		query.add(new JLabel("Search activities and bosses"), BorderLayout.NORTH);
		query.add(search);
		body.add(query, BorderLayout.NORTH);
		results.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
		results.setFixedCellHeight(27);
		results.setCellRenderer(new DefaultListCellRenderer()
		{
			@Override
			public java.awt.Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus)
			{
				super.getListCellRendererComponent(list, value, index, selected, focus);
				putClientProperty("html.disable", true);
				setText(value instanceof HiscoreSkill ? ((HiscoreSkill) value).getName() : "");
				return this;
			}
		});
		JScrollPane scroll = new JScrollPane(results);
		scroll.setPreferredSize(new Dimension(350, 215));
		body.add(scroll);
		empty.setVisible(false);
		body.add(empty, BorderLayout.SOUTH);
		popup.setLayout(new BorderLayout());
		popup.add(body);
		choose.addActionListener(e ->
		{
			search.setText("");
			filter();
			popup.setPopupSize(Math.max(360, getWidth()), 290);
			popup.show(choose, 0, choose.getHeight());
			SwingUtilities.invokeLater(() -> search.requestFocusInWindow());
		});
		search.getDocument().addDocumentListener(new DocumentListener()
		{
			public void insertUpdate(DocumentEvent e)
			{
				filter();
			}

			public void removeUpdate(DocumentEvent e)
			{
				filter();
			}

			public void changedUpdate(DocumentEvent e)
			{
				filter();
			}
		});
		bind(search, KeyEvent.VK_DOWN, "next", () -> navigate(1));
		bind(search, KeyEvent.VK_UP, "previous", () -> navigate(-1));
		bind(search, KeyEvent.VK_ENTER, "choose", this::acceptHighlighted);
		bind(results, KeyEvent.VK_ENTER, "choose", this::acceptHighlighted);
		bind(search, KeyEvent.VK_ESCAPE, "cancel", () -> popup.setVisible(false));
		bind(results, KeyEvent.VK_ESCAPE, "cancel", () -> popup.setVisible(false));
		results.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				int index = results.locationToIndex(e.getPoint());
				if (SwingUtilities.isLeftMouseButton(e) && index >= 0 && results.getCellBounds(index, index).contains(e.getPoint()))
				{
					acceptHighlighted();
				}
			}
		});
		filter();
	}

	HiscoreSkill selected()
	{
		return selected;
	}

	void onChange(Runnable callback)
	{
		changed = callback;
	}

	JTextField searchField()
	{
		return search;
	}

	JPanel popupContents()
	{
		return (JPanel) popup.getComponent(0);
	}

	List<HiscoreSkill> filteredMetrics()
	{
		return java.util.Collections.list(matches.elements());
	}

	void select(HiscoreSkill metric)
	{
		if (!metrics.contains(metric))
		{
			throw new IllegalArgumentException("Choose a HiScores activity");
		}
		boolean different = selected != metric;
		selected = metric;
		choose.setText(metric.getName() + "  ▾");
		popup.setVisible(false);
		if (different)
		{
			changed.run();
		}
	}

	private void filter()
	{
		String query = search.getText().trim().toLowerCase(java.util.Locale.ROOT);
		matches.clear();
		metrics.stream().filter(m -> m.getName().toLowerCase(java.util.Locale.ROOT).contains(query)).forEach(matches::addElement);
		int index = matches.indexOf(selected);
		if (!matches.isEmpty())
		{
			results.setSelectedIndex(index < 0 ? 0 : index);
			results.ensureIndexIsVisible(results.getSelectedIndex());
		}
		empty.setVisible(matches.isEmpty());
	}

	private void navigate(int direction)
	{
		if (matches.isEmpty())
		{
			return;
		}
		results.setSelectedIndex(Math.max(0, Math.min(matches.size() - 1, results.getSelectedIndex() + direction)));
		results.ensureIndexIsVisible(results.getSelectedIndex());
	}

	private void acceptHighlighted()
	{
		if (results.getSelectedValue() != null)
		{
			select(results.getSelectedValue());
			choose.requestFocusInWindow();
		}
	}

	private static void bind(JComponent input, int key, String name, Runnable action)
	{
		input.getInputMap().put(KeyStroke.getKeyStroke(key, 0), name);
		input.getActionMap().put(name, new AbstractAction()
		{
			@Override
			public void actionPerformed(ActionEvent event)
			{
				action.run();
			}
		});
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
}
