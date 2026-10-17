package com.mygoals.ui;

import com.mygoals.domain.GoalDateFormat;

import java.awt.Component;
import java.awt.Color;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.function.Consumer;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import net.runelite.client.util.Filepath;

/**
 * Explicit transfers with a validated preview before restore. No disk I/O or
 * ZIP work on Swing.
 */
public final class BackupControls extends JPanel
{
	public interface Backend
	{
		void export(String token, Filepath destination, Consumer<BackupResult> completed);

		void prepare(String token, Filepath source, Consumer<BackupResult> completed);

		void restore(String token, String restoreId, Consumer<BackupResult> completed);

		void cancel(String token, String restoreId);
	}

	interface Dialogs
	{
		Filepath choose(boolean save);

		boolean confirm(BackupResult preview, String character);

		void result(BackupResult result);
	}

	private final JMenuItem export = new JMenuItem("Export backup"), restore = new JMenuItem("Restore backup");
	private final JLabel progress = new JLabel();
	private final Dialogs dialogs;
	private final Runnable beforeRestore;
	private Backend backend;
	private GoalDateFormat dateFormat = GoalDateFormat.AMERICAN;

	void setDateFormat(GoalDateFormat format)
	{
		dateFormat = format;
	}

	private String token, character = "this character";
	private boolean editable, busy;
	private long request;

	public BackupControls(Runnable beforeRestore)
	{
		this(null, beforeRestore);
	}

	BackupControls(Dialogs dialogs, Runnable beforeRestore)
	{
		this.dialogs = dialogs == null ? new NativeDialogs(this) : dialogs;
		this.beforeRestore = beforeRestore;
		setName("backupControls");
		setOpaque(false);
		setAlignmentX(Component.LEFT_ALIGNMENT);
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		export.setName("exportBackup");
		restore.setName("restoreBackup");
		export.setToolTipText("Save this character's goals, progress, and complete history as a ZIP backup.");
		restore.setToolTipText("Validate a backup, review its contents, then restore it for this character.");
		progress.setName("backupProgress");
		progress.setForeground(new Color(230, 184, 92));
		add(progress);
		export.addActionListener(event -> start(false));
		restore.addActionListener(event -> start(true));
		refresh();
	}

	public void setBackend(Backend backend)
	{
		this.backend = backend;
		refresh();
	}

	JMenuItem exportItem()
	{
		return export;
	}

	JMenuItem restoreItem()
	{
		return restore;
	}

	void render(GoalsView view)
	{
		if (!Objects.equals(token, view.token))
		{
			request++;
			busy = false;
			progress.setText("");
		}
		token = view.token;
		editable = view.editable;
		if (editable)
		{
			character = view.status.split("\n", 2)[0];
		}
		refresh();
	}

	private void refresh()
	{
		export.setEnabled(editable && !busy && backend != null);
		restore.setEnabled(editable && !busy && backend != null);
		setVisible(!progress.getText().isEmpty());
	}

	private void start(boolean restoring)
	{
		if (!editable || busy || backend == null)
		{
			return;
		}
		String captured = token;
		long id = ++request;
		Filepath file = dialogs.choose(!restoring);
		if (file == null || id != request || !Objects.equals(captured, token) || !editable)
		{
			return;
		}
		busy = true;
		progress.setText(restoring ? "Checking backup…" : "Exporting backup…");
		refresh();
		if (!restoring)
		{
			backend.export(captured, file, result -> complete(captured, id, result));
			return;
		}
		backend.prepare(captured, file, preview ->
		{
			if (id != request || !Objects.equals(captured, token))
			{
				if (preview.restoreId != null)
				{
					backend.cancel(captured, preview.restoreId);
				}
				return;
			}
			if (!preview.error.isEmpty())
			{
				complete(captured, id, preview);
				return;
			}
			boolean approved = dialogs.confirm(preview, character);
			if (!approved || id != request || !Objects.equals(captured, token) || !editable)
			{
				backend.cancel(captured, preview.restoreId);
				if (id == request)
				{
					busy = false;
					progress.setText("");
					refresh();
				}
				return;
			}
			beforeRestore.run();
			progress.setText("Restoring backup…");
			refresh();
			backend.restore(captured, preview.restoreId, result -> complete(captured, id, result));
		});
	}

	private void complete(String captured, long id, BackupResult result)
	{
		if (id != request || !Objects.equals(captured, token))
		{
			return;
		}
		busy = false;
		progress.setText("");
		refresh();
		dialogs.result(result);
	}

	private static final class NativeDialogs implements Dialogs
	{
		private final BackupControls parent;

		NativeDialogs(BackupControls parent)
		{
			this.parent = parent;
		}

		private Component owner()
		{
			Component window = SwingUtilities.getWindowAncestor(parent);
			return window == null ? parent : window;
		}

		public Filepath choose(boolean save)
		{
			Filepath.Chooser chooser = new Filepath.Chooser().setAcceptsFiles().setDefaultExtension("zip")
					.addExtensionFilter("Advanced Goal Pacer backup (*.zip)", "zip")
					.setDialogTitle(save ? "Export Advanced Goal Pacer backup" : "Restore Advanced Goal Pacer backup");
			if (save)
			{
				chooser.setIsSave().setFileName(
						"my-goals-backup-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss")) + ".zip");
			}
			else
			{
				chooser.setIsOpen();
			}
			var selected = chooser.showDialog(owner());
			return selected == null || selected.isEmpty() ? null : selected.get(0);
		}

		public boolean confirm(BackupResult preview, String character)
		{
			String date = parent.dateFormat.timestamp(Instant.ofEpochMilli(preview.createdAt), ZoneId.systemDefault());
			JTextArea text = message("Restore backup for " + character + "?\n\nBackup: " + date + "\n" + preview.goals + " goals • "
					+ preview.periods + " recorded periods\n\n" +
					"This replaces your current goals, groups, progress, and history. A backup of your current data will be saved first.\n\n"
					+
					"Refresh relevant collection-log pages to recover items earned since the backup.");
			return JOptionPane.showOptionDialog(owner(), text, "Restore backup", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE,
					null, new Object[] { "Restore", "Cancel" }, "Cancel") == 0;
		}

		public void result(BackupResult result)
		{
			JOptionPane.showMessageDialog(owner(), message(result.error.isEmpty() ? result.message : result.error),
					result.error.isEmpty() ? "Advanced Goal Pacer backup" : "Backup unavailable",
					result.error.isEmpty() ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.ERROR_MESSAGE);
		}

		private static JTextArea message(String value)
		{
			JTextArea text = new JTextArea(value);
			text.setEditable(false);
			text.setOpaque(false);
			text.setLineWrap(true);
			text.setWrapStyleWord(true);
			text.setColumns(38);
			text.setFont(javax.swing.UIManager.getFont("Label.font"));
			return text;
		}
	}
}
